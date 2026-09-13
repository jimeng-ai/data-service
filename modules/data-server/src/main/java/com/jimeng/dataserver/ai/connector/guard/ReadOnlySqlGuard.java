package com.jimeng.dataserver.ai.connector.guard;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 查询语句的只读护栏：解析 → 判类型 → 查危险构造 → 强制 LIMIT。
 *
 * <h3>★ 这一层不是承重层，别把它当成可以不验只读账号的理由</h3>
 * 只读有三层，只有第一层承重：
 * <ol>
 *   <li><b>客户侧数据库授权</b>（客户建只读账号，只 {@code GRANT SELECT}）——<b>不能被绕过，承重</b></li>
 *   <li>本类 + {@code readOnly=true} + {@code SET SESSION TRANSACTION READ ONLY}——
 *       <b>解析器可能被方言绕过</b>，见下</li>
 *   <li>提示词里的「请只写 SELECT」——什么都挡不住</li>
 * </ol>
 * jsqlparser 认的是通用 SQL 语法，客户的库可能支持它不认识的方言函数、过程调用、
 * 以及 {@code /*!50000 ...*}{@code /} 这类 MySQL 版本注释。所以
 * <b>{@code ConnectorProbeService} 那一步「试一个无害的写、期望它失败」永远不能因为有了本类而省掉。</b>
 *
 * <h3>为什么不自己剥注释</h3>
 * 直觉上应该先用正则把 {@code --} 和 {@code /* *}{@code /} 剥掉再判。<b>不要这么做</b>：
 * 手写的剥注释逻辑恰恰是被 MySQL 版本注释骗过去的地方（{@code /*!50000 DROP *}{@code /}
 * 在 MySQL 眼里是可执行代码，在朴素的剥注释逻辑眼里是注释）。这里依赖 jsqlparser 的
 * <b>解析结果类型</b>作判据——解析不出来就拒绝，这是唯一可靠的姿势。
 *
 * <h3>错误文案里不回显原始 SQL</h3>
 * 异常会一路流到模型上下文并落进 {@code ai_model_call_content}。模型自己写的语句它当然知道，
 * 但回显会让这条错误在日志、审计、库表里多存一份副本，没有必要。
 */
@Slf4j
@Component
public class ReadOnlySqlGuard {

    /**
     * @param effectiveSql   平台实际要执行的语句（可能被注入或收紧了 LIMIT）
     * @param limitInjected  平台是否动过 LIMIT
     * @param effectiveLimit 最终生效的行数上限
     */
    public record Verdict(String effectiveSql, boolean limitInjected, Integer effectiveLimit) {}

    /**
     * 危险函数。它们的共同点是<b>在一条 SELECT 里产生副作用或读到不该读的东西</b>：
     * 读服务器文件、占用连接、拿全局锁、阻塞复制线程。
     *
     * <p>用词边界匹配而不是 contains：否则一个叫 {@code sleep_minutes} 的业务列会被误杀。
     */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(load_file|sleep|benchmark|get_lock|release_lock|is_used_lock|master_pos_wait|"
                    + "source_pos_wait|sys_exec|sys_eval|lo_import|lo_export|pg_sleep|pg_read_file|dbms_lock)\\s*\\(");

    /** {@code SELECT ... INTO OUTFILE/DUMPFILE} 会往服务器磁盘写文件——jsqlparser 不总是解析成 IntoTables。 */
    private static final Pattern INTO_FILE = Pattern.compile("(?i)\\binto\\s+(out|dump)file\\b");

    /** {@code SELECT ... INTO @var} 是赋值，不是查询。 */
    private static final Pattern INTO_VAR = Pattern.compile("(?i)\\binto\\s+@");

    public Verdict check(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "查询语句不能为空");
        }
        int limit = maxRows <= 0 ? 1000 : maxRows;
        String trimmed = sql.trim();

        // ---- 文本层：只用来抓解析器可能看不见的构造，不作为主判据 ----
        if (INTO_FILE.matcher(trimmed).find() || INTO_VAR.matcher(trimmed).find()) {
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "语句包含 INTO OUTFILE / INTO DUMPFILE / INTO @变量，这属于写操作，已拒绝执行");
        }

        // ---- 解析层：主判据 ----
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (Exception e) {
            // 解析失败一律拒（fail-closed）。原始异常里带着完整 SQL，只进日志。
            log.warn("查询语句解析失败", e);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "无法解析这条查询语句。请确认它是一条标准的 SELECT 语句，"
                            + "不要使用平台无法识别的方言语法或多条语句");
        }

        List<Statement> list = statements.getStatements();
        if (list == null || list.isEmpty()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "没有解析出任何语句");
        }
        if (list.size() > 1) {
            // 堆叠语句是最经典的绕过：`SELECT 1; DROP TABLE x`。
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "一次只能执行一条语句，检测到 " + list.size() + " 条。请拆成多次调用");
        }
        Statement st = list.get(0);
        if (!(st instanceof Select select)) {
            // 类型判据，而不是关键字判据——`WITH x AS (...) SELECT` 也是 Select，
            // 而 `/*!50000 DROP*/` 这种骗不过解析器。
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "只允许执行查询（SELECT），检测到的是 " + st.getClass().getSimpleName()
                            + "。这条连接只被授予了只读权限");
        }

        // 用解析后重新序列化的文本做危险函数扫描：此时注释已被解析器消化，
        // 版本注释里的内容若真是可执行代码，也已经变成了语法树的一部分。
        String normalized = select.toString();
        var m = DANGEROUS.matcher(normalized);
        if (m.find()) {
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "语句中使用了被禁止的函数 " + m.group(1).toLowerCase(Locale.ROOT)
                            + "()，它会在客户的数据库上产生副作用或占用资源");
        }

        SelectBody body = select.getSelectBody();
        assertNoIntoTables(body);
        assertNoUnboundedCrossJoin(body);

        boolean injected = applyLimit(body, limit);
        return new Verdict(select.toString(), injected, limit);
    }

    // ---------------------------------------------------------------- 各项检查

    private void assertNoIntoTables(SelectBody body) {
        if (body instanceof PlainSelect ps) {
            if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "语句包含 SELECT ... INTO，这会写入数据，已拒绝执行");
            }
        } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
            sol.getSelects().forEach(this::assertNoIntoTables);
        }
    }

    /**
     * 拒绝<b>完全没有任何过滤条件</b>的多表交叉连接。
     *
     * <p>这一条是资源保护不是安全，所以<b>判不准时放行而不是误杀</b>：只在「有逗号连接或无 ON 的
     * join」<b>且</b>「整条语句没有 WHERE」时才拒。带 WHERE 的多表查询一律放行——
     * 关联条件写在 WHERE 里是极常见的老写法，把它拒掉会让大量合法查询报一个莫名其妙的错，
     * 而漏拦一条慢查询有 LIMIT 和 statement timeout 兜底。
     */
    private void assertNoUnboundedCrossJoin(SelectBody body) {
        if (body instanceof SetOperationList sol && sol.getSelects() != null) {
            sol.getSelects().forEach(this::assertNoUnboundedCrossJoin);
            return;
        }
        if (!(body instanceof PlainSelect ps)) {
            return;
        }
        List<Join> joins = ps.getJoins();
        if (joins == null || joins.isEmpty() || ps.getWhere() != null) {
            return;
        }
        for (Join j : joins) {
            boolean hasOn = j.getOnExpressions() != null && !j.getOnExpressions().isEmpty();
            // NATURAL JOIN 自带关联列，不算无界。
            if (!hasOn && !j.isNatural()) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "多表查询既没有 JOIN ... ON 条件也没有 WHERE 条件，这会产生笛卡尔积并可能拖垮数据库。"
                                + "请补上表之间的关联条件");
            }
        }
    }

    /**
     * 强制 LIMIT：没有就注入，有但超上限就收紧。
     *
     * <p>注意这里改的是语法树再整体序列化，而不是在 SQL 字符串尾巴上拼 {@code " LIMIT n"}——
     * 拼字符串遇到 {@code ... ORDER BY x LIMIT 5 OFFSET 10} 或以分号结尾的语句就会产出非法 SQL。
     *
     * @return 是否动过
     */
    private boolean applyLimit(SelectBody body, int maxRows) {
        if (body instanceof SetOperationList sol) {
            // UNION 的 LIMIT 挂在整个集合上，对各分支单独加限制没有意义（也改变语义）。
            return setLimitIfNeeded(sol.getLimit(), sol::setLimit, maxRows);
        }
        if (body instanceof PlainSelect ps) {
            return setLimitIfNeeded(ps.getLimit(), ps::setLimit, maxRows);
        }
        // 认不出来的 SelectBody（比如 ValuesStatement）：拒绝而不是放行。
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                "无法在这条语句上施加行数上限，已拒绝执行。请改写成标准的 SELECT");
    }

    private boolean setLimitIfNeeded(Limit current, java.util.function.Consumer<Limit> setter, int maxRows) {
        if (current == null) {
            Limit l = new Limit();
            l.setRowCount(new LongValue(maxRows));
            setter.accept(l);
            return true;
        }
        // LIMIT ALL / LIMIT NULL 等价于无上限，必须换掉。
        if (current.getRowCount() instanceof LongValue lv) {
            if (lv.getValue() <= maxRows) {
                return false;   // 模型自己写的上限更严，尊重它
            }
        }
        Limit l = new Limit();
        l.setRowCount(new LongValue(maxRows));
        // 保留原有的 offset：把 `LIMIT 100000 OFFSET 5000` 收紧成 `LIMIT 1000 OFFSET 5000`，
        // 而不是把分页起点也抹掉——后者会让模型翻页时永远拿到第一页。
        l.setOffset(current.getOffset());
        setter.accept(l);
        return true;
    }
}

package com.jimeng.dataserver.ai.connector.guard;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 写语句护栏。
 *
 * <h3>★ 它挡的是「模型写错」，不是「有人恶意」</h3>
 * 恶意由客户侧的账号授权挡（那是承重层）；这里挡的是一类更常见、也更难被发现的事故：
 * <b>模型生成了一条语法完全正确、但作用范围远超预期的 DML</b>。最典型的就是漏掉 WHERE——
 * {@code UPDATE orders SET status = 'CANCELLED'} 在语法上无可挑剔，执行下去是把整张表改掉。
 *
 * <p>所以这里最重要的一条规则不是「禁止某某语法」，而是
 * <b>{@code UPDATE} / {@code DELETE} 必须带 {@code WHERE}</b>。
 *
 * <h3>它不是唯一一道闸</h3>
 * 带了 WHERE 也可能命中远超预期的行数（{@code WHERE 1 = 1} 同样合法）。
 * 那一层由 {@code WriteOptions.maxAffectedRows} 在<b>执行之后、提交之前</b>兜底——
 * 事前估算不可靠，而不可靠的那次恰恰是要防的那次。两道闸缺一不可。
 *
 * <h3>为什么不复用 ReadOnlySqlGuard</h3>
 * 那道护栏的价值恰恰在于它<b>没有例外</b>：进去的只能是 SELECT。给它加一个「有时允许写」的分支，
 * 就等于把「这条路绝对写不了」这个可以一眼看懂的性质，换成一个要读条件才能确定的性质。
 */
@Slf4j
@Component
public class WriteSqlGuard {

    /**
     * @param effectiveSql 平台实际要执行的语句（当前不改写，保留字段是为了将来加 LIMIT 之类）
     * @param operation    {@code INSERT} / {@code UPDATE} / {@code DELETE}，用于审计与审批展示
     * @param targetTable  目标表名，用于审批时让人一眼看出改的是哪张表
     */
    public record Verdict(String effectiveSql, String operation, String targetTable) {}

    /** 与只读护栏同一份清单：这些函数在任何语句里都不该出现。 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(load_file|sleep|benchmark|get_lock|release_lock|is_used_lock|master_pos_wait|"
                    + "source_pos_wait|sys_exec|sys_eval|lo_import|lo_export|pg_sleep|pg_read_file|dbms_lock)\\s*\\(");

    private static final Pattern INTO_FILE = Pattern.compile("(?i)\\binto\\s+(out|dump)file\\b");

    public Verdict check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "语句不能为空");
        }
        String trimmed = sql.trim();

        if (INTO_FILE.matcher(trimmed).find()) {
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "语句包含 INTO OUTFILE / DUMPFILE，会往数据库服务器磁盘写文件，已拒绝执行");
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (Exception e) {
            // 解析失败一律拒（fail-closed）。原始异常带着完整语句，只进日志。
            log.warn("写语句解析失败", e);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "无法解析这条语句。请确认它是一条标准的 INSERT / UPDATE / DELETE，"
                            + "不要使用平台无法识别的方言语法或多条语句");
        }

        List<Statement> list = statements.getStatements();
        if (list == null || list.isEmpty()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "没有解析出任何语句");
        }
        if (list.size() > 1) {
            // 写操作的多语句风险远大于查询：一条合法 UPDATE 后面跟一条 DROP，前一条还会给出成功的假象。
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "一次只能执行一条写语句，检测到 " + list.size() + " 条。请拆成多次调用");
        }
        Statement st = list.get(0);

        String normalized = st.toString();
        var m = DANGEROUS.matcher(normalized);
        if (m.find()) {
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "语句中使用了被禁止的函数 " + m.group(1).toLowerCase(Locale.ROOT) + "()");
        }

        if (st instanceof Update up) {
            // ★ 本类最重要的一条。
            if (up.getWhere() == null) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "UPDATE 语句必须带 WHERE 条件。不带 WHERE 会改掉整张表——"
                                + "如果确实要更新全表，请拆成带明确条件的多次操作");
            }
            return new Verdict(normalized, "UPDATE", tableName(up.getTable()));
        }
        if (st instanceof Delete del) {
            if (del.getWhere() == null) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "DELETE 语句必须带 WHERE 条件。不带 WHERE 会清空整张表——"
                                + "如果确实要清空，请由数据库管理员在数据库侧操作");
            }
            return new Verdict(normalized, "DELETE", tableName(del.getTable()));
        }
        if (st instanceof Insert ins) {
            // INSERT ... SELECT 的作用范围由子查询决定，可能是几百万行。
            // 不直接禁（对账、归档类操作确实需要它），但交给 maxAffectedRows 那道闸兜住。
            return new Verdict(normalized, "INSERT", tableName(ins.getTable()));
        }

        // 判据是解析出来的【类型】，不是关键字匹配：DDL、TRUNCATE、REPLACE、CALL、SET 全部落到这里。
        // 尤其 REPLACE：它看着像 INSERT，实际会先 DELETE 同键的行，作用范围完全不同。
        throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                "只允许 INSERT / UPDATE / DELETE，检测到的是 " + st.getClass().getSimpleName()
                        + "。建表、改表、清空表（TRUNCATE）、REPLACE、存储过程调用一律不开放");
    }

    private static String tableName(net.sf.jsqlparser.schema.Table t) {
        return t == null ? null : t.getName();
    }
}

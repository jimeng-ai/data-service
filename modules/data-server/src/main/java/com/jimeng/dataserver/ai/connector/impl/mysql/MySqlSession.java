package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.model.WritePlan;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一次 MySQL 会话。
 *
 * <p>它<b>不持有物理连接</b>：每个方法自己从池里借、用完还。理由是一次 Agent 调用可能在
 * 会话对象上停留很久（网关的 try-with-resources 包着整个 op），而占着客户库的一条连接
 * 等模型思考是不可接受的。
 */
@Slf4j
public class MySqlSession implements ConnectorSession, QueryCapable, DescribeCapable, WriteCapable {

    /**
     * 目录一次最多返回多少个对象。大库几百张表，全量塞进上下文放不下也没用。
     *
     * <p><b>上限本身不重要，「砍掉哪一批」才重要。</b>这个 LIMIT 是数据库执行的，
     * 被砍掉的行根本不会回到 Java——所以决定去留的只能是 SQL 里的 ORDER BY，
     * 见 {@link #CATALOG_LIST_SQL}。
     */
    private static final int CATALOG_MAX = 500;

    /**
     * 「行数未知」算第几档。见 {@link #importanceTier(Long)}。
     *
     * <p>3 = 当它是一张几百行的普通表：排在真正空的表和个位数行的表<b>前面</b>，
     * 排在上千行的实表<b>后面</b>。
     */
    private static final int UNKNOWN_ROWS_TIER = 3;

    /** {@link CatalogView#ordering()}：这个连接器按什么排目录，一句人话，会进截断消息。 */
    static final String CATALOG_ORDERING =
            "按估算行数的数量级降序（行数未知的视图等按几百行量级插队），同档内按对象名升序";

    /**
     * 目录列表 SQL。<b>提成常量是为了能被单测钉住</b>：整个「按重要性截断」的修复，
     * 全部力气都在这段 ORDER BY 上，它一旦被谁顺手改回 {@code ORDER BY TABLE_NAME}，
     * 行为会<b>静默</b>退回老样子——大库里 t_ 开头的业务表整片消失，没有任何报错。
     *
     * <p>排序表达式与 {@link #importanceTier(Long)} 必须是<b>同一个函数</b>。
     * 这里用 {@code LENGTH(TABLE_ROWS)}（整数转字符串取十进制位数）而不是
     * {@code FLOOR(LOG10(TABLE_ROWS))+1}：后者走浮点，{@code LOG10(1000)} 在某些平台上
     * 会得到 2.9999999999999996 从而 FLOOR 成 2，SQL 侧和 Java 侧就会在 10 的整数次幂上分叉。
     */
    static final String CATALOG_LIST_SQL =
            "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
                    + "ORDER BY CASE WHEN TABLE_ROWS IS NULL THEN " + UNKNOWN_ROWS_TIER
                    + " WHEN TABLE_ROWS < 1 THEN 0 ELSE LENGTH(TABLE_ROWS) END DESC, TABLE_NAME ASC "
                    + "LIMIT " + CATALOG_MAX;

    /** 只读探针用的表名。刻意取一个不可能与客户业务表重名的名字。 */
    private static final String PROBE_TABLE = "__jm_readonly_probe__";

    /** {@code information_schema} 里按名字取对象时的白名单：防止把标识符拼进 SQL。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    private final ConnectorInstance instance;
    private final DataSource dataSource;
    private final String database;
    private final ReadOnlySqlGuard sqlGuard;
    private final WriteSqlGuard writeGuard;

    /**
     * 预估影响行数的超时，<b>刻意比写操作本身的超时短</b>。
     *
     * <p>它产出的是给人看的参考信息，不是执行路径的一部分：估不出来只是审批页少一个数字，
     * 而一条卡住的 COUNT 会占着客户库的连接和我们的并发闸。宁可放弃，不要拖住。
     * 对抗审查实测过：高基数列上的聚合能跑到 24 秒，远超写操作默认的 15 秒。
     */
    private static final int ESTIMATE_TIMEOUT_SEC = 5;

    MySqlSession(ConnectorInstance instance, DataSource dataSource, String database,
                 ReadOnlySqlGuard sqlGuard, WriteSqlGuard writeGuard) {
        this.instance = instance;
        this.dataSource = dataSource;
        this.database = database;
        this.sqlGuard = sqlGuard;
        this.writeGuard = writeGuard;
    }

    /** 旧签名：只读场景（探测、测试）用，不需要写护栏。 */
    MySqlSession(ConnectorInstance instance, DataSource dataSource, String database, ReadOnlySqlGuard sqlGuard) {
        this(instance, dataSource, database, sqlGuard, new WriteSqlGuard());
    }

    // ================================================================ 探测三步

    @Override
    public void ping() {
        try (Connection c = borrow();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
        } catch (SQLException e) {
            throw classify(e, "探活");
        }
    }

    /**
     * ★ 只读验证：<b>试一个无害的写，期望它失败</b>。
     *
     * <h3>为什么用 UPDATE 一张不存在的表，以及为什么「表不存在」是坏消息</h3>
     * MySQL 的权限检查发生在<b>对象存在性检查之前</b>（先看库级/表级权限，再看表在不在）。
     * 所以对一张不存在的表执行 {@code UPDATE}：
     * <ul>
     *   <li>账号<b>没有</b>该库的 UPDATE 权限 → {@code 1142} / {@code 1044}（access denied）
     *       → <b>确认只读</b> ✅</li>
     *   <li>账号<b>有</b>该库的 UPDATE 权限 → {@code 1146}（表不存在）
     *       → <b>说明它能写</b>，只是这张表碰巧不在 ❌</li>
     * </ul>
     * 这个反直觉的判据是本方法的全部要点。{@code WHERE 1 = 0} 是第二层保险：
     * 万一客户真的建了一张同名表且账号有权限，这条语句也不会改动任何行。
     *
     * <h3>按错误码判，不按消息文本判</h3>
     * 消息文本随 MySQL 版本、语言包、中间件（ProxySQL / MaxScale）而变，拿它做判据迟早失效，
     * <b>而失效的方向是「判成只读」——一个静默的安全降级</b>。错误码是稳定契约。
     *
     * <h3>★ 探针必须临时关掉连接的 readOnly，否则它什么都验不出来</h3>
     * 连接池对每条连接设了 {@code readOnly=true}（那是只读的第三道防线）。但 Connector/J 是
     * <b>在客户端就拦下</b>写语句的——{@code StatementImpl.executeUpdateInternal} 直接抛
     * {@code "Connection is read-only. Queries leading to data modification are not allowed."}，
     * {@code errorCode=0}，<b>语句根本没发到服务端</b>。于是我们永远拿不到 1142/1146，
     * 每一条连接都会被判成「无法判定」而拒绝保存。
     *
     * <p>这是一处很典型的「防护措施让检测失效」：我们自己的第二层防线挡住了对承重层的探测。
     * 所以这里显式 {@code setReadOnly(false)} 再跑探针、跑完恢复。安全性不受影响——
     * 真正决定成败的是<b>服务端</b>的权限检查，而 {@code WHERE 1 = 0} 保证即使权限放行也改不动任何行。
     */
    @Override
    public ReadOnlyVerdict verifyReadOnly() {
        String probe = "UPDATE `" + PROBE_TABLE + "` SET x = 1 WHERE 1 = 0";
        try (Connection c = borrow()) {
            try {
                c.setReadOnly(false);
            } catch (SQLException e) {
                // 关不掉就没法探。归 unknown 而不是 confirmed——「验不了」不是「验过了」。
                log.warn("只读探针无法关闭连接的 readOnly 标志 connectorId={}", instance.id(), e);
                return ReadOnlyVerdict.unknown("平台无法在这条连接上执行只读校验");
            }
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(10);
                st.executeUpdate(probe);
                // 竟然成功了：说明客户真有这张表，而且这个账号能写它。
                return ReadOnlyVerdict.writable("账号可以执行 UPDATE 语句");
            } finally {
                // 连接要还回池里，必须恢复原状，否则后续查询拿到的是一条可写连接。
                try {
                    c.setReadOnly(true);
                } catch (SQLException ignore) {
                    log.debug("恢复 readOnly 失败 connectorId={}", instance.id());
                }
            }
        } catch (SQLException e) {
            int code = e.getErrorCode();
            return switch (code) {
                // 1142 ER_TABLEACCESS_DENIED_ERROR / 1044 ER_DBACCESS_DENIED_ERROR
                case 1142, 1044 -> ReadOnlyVerdict.confirmed(
                        "数据库在表/库级别拒绝了写操作（错误码 " + code + "）");
                // 1290 ER_OPTION_PREVENTS_STATEMENT：整个服务端是 read_only 的（只读从库）
                case 1290 -> ReadOnlyVerdict.confirmed("数据库实例处于只读模式（错误码 1290）");
                // 1146 ER_NO_SUCH_TABLE：权限检查过了，说明这个账号在该库上有写权限
                case 1146 -> ReadOnlyVerdict.writable(
                        "账号具备该库的写权限（写操作没有被权限拦下，只是探针表不存在）");
                default -> {
                    // 任何判不出来的情况都归 unknown，绝不归 confirmed。
                    log.warn("只读探针返回了未预期的错误码 connectorId={} errorCode={} sqlState={}",
                            instance.id(), code, e.getSQLState(), e);
                    yield ReadOnlyVerdict.unknown("探测返回了无法判定的错误（错误码 " + code + "）");
                }
            };
        }
    }

    @Override
    public Set<Capability> probeCapabilities() {
        Set<Capability> caps = new LinkedHashSet<>();
        // 能连上就能查——ping 已经证明过了。
        caps.add(Capability.QUERY);
        caps.add(Capability.HEALTH);
        // 自描述要真的试一次：客户给的账号可能读不到 information_schema，
        // 这时 DESCRIBE 必须降级掉，否则界面上显示「支持自描述」而实际一用就报错——又一次静默降级。
        try (Connection c = borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? LIMIT 1")) {
            ps.setQueryTimeout(10);
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            caps.add(Capability.DESCRIBE);
        } catch (SQLException e) {
            log.warn("连接器自描述能力探测失败，将降级 connectorId={} errorCode={}",
                    instance.id(), e.getErrorCode(), e);
        }
        return caps;
    }

    // ================================================================ 能查

    @Override
    public QueryResult query(String statement, QueryOptions options) {
        // 第一件事就是过护栏。注意护栏是第二层防线——第一层是客户侧的只读账号，
        // 已在接入时由 verifyReadOnly() 验过。
        ReadOnlySqlGuard.Verdict verdict = sqlGuard.check(statement, options.maxRows());
        String sql = verdict.effectiveSql();

        long start = System.currentTimeMillis();
        try (Connection c = borrow()) {
            applyReadOnly(c);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(options.timeoutSec());
                // 多取一行：用来区分「正好 maxRows 行」和「还有更多」。
                // 只靠 rows.size() == maxRows 判断会把恰好等于上限的完整结果误报成截断。
                st.setMaxRows(options.maxRows() + 1);
                // fetchSize 不设 Integer.MIN_VALUE（MySQL 的流式读）：那会独占连接直到读完，
                // 而我们有 maxRows 和 maxBytes 两道闸，结果集本来就不会大到需要流式。
                try (ResultSet rs = st.executeQuery(sql)) {
                    return readResultSet(rs, options, sql, start);
                }
            }
        } catch (SQLException e) {
            throw classify(e, "查询");
        }
    }

    private QueryResult readResultSet(ResultSet rs, QueryOptions options, String sql, long start) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            // 用 label 而不是 name：`SELECT a AS 金额` 时模型该看到的是别名。
            columns.add(md.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        String reason = null;
        long bytes = 0;

        while (rs.next()) {
            if (rows.size() >= options.maxRows()) {
                truncated = true;
                reason = "命中行数上限 " + options.maxRows();
                break;
            }
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object v = safeValue(rs, i);
                row.add(v);
                bytes += estimateBytes(v);
            }
            rows.add(row);
            if (bytes > options.maxBytes()) {
                truncated = true;
                reason = "命中结果大小上限 " + (options.maxBytes() / 1024) + "KB";
                break;
            }
        }
        return new QueryResult(columns, rows, truncated, reason, sql, System.currentTimeMillis() - start);
    }

    /**
     * 把 JDBC 的值转成「能安全进模型上下文并被 JSON 序列化」的形状。
     *
     * <p>四类必须显式处理，否则要么序列化失败，要么把垃圾灌进上下文：
     * <ul>
     *   <li>{@code BigDecimal} → {@code toPlainString()}，避免科学计数法把金额写成 {@code 1.0E+8}</li>
     *   <li>时间类 → ISO 字符串。{@code java.sql.Timestamp} 的默认 toString 没有时区信息，
     *       而问数最常见的口径争议就是时区</li>
     *   <li>二进制 / BLOB → <b>只给占位</b>。把几百 KB 的二进制塞进上下文既烧 token 又毫无信息量</li>
     *   <li>超长文本 → 单值截断并标注，防止一个 TEXT 列撑爆整轮对话</li>
     * </ul>
     */
    private static final int SINGLE_VALUE_MAX = 8192;

    private Object safeValue(ResultSet rs, int idx) throws SQLException {
        Object v = rs.getObject(idx);
        if (v == null || rs.wasNull()) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof byte[] b) {
            return "<binary " + b.length + " bytes>";
        }
        if (v instanceof java.sql.Blob blob) {
            try {
                return "<blob " + blob.length() + " bytes>";
            } catch (SQLException e) {
                return "<blob>";
            }
        }
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                String s = clob.getSubString(1, (int) Math.min(len, SINGLE_VALUE_MAX));
                return len > SINGLE_VALUE_MAX ? s + "…[单值已截断]" : s;
            } catch (SQLException e) {
                return "<clob>";
            }
        }
        if (v instanceof String s && s.length() > SINGLE_VALUE_MAX) {
            return s.substring(0, SINGLE_VALUE_MAX) + "…[单值已截断]";
        }
        return v;
    }

    private static long estimateBytes(Object v) {
        if (v == null) return 4;
        if (v instanceof String s) return s.getBytes(StandardCharsets.UTF_8).length;
        return 16;
    }

    // ================================================================ 能写

    /**
     * 执行一条写语句。
     *
     * <h3>★ 三道闸，缺一不可</h3>
     * <ol>
     *   <li><b>语句护栏</b>（{@link WriteSqlGuard}）——单条 DML，且 UPDATE/DELETE 必须带 WHERE。</li>
     *   <li><b>影响行数上限 + 回滚</b>——带了 WHERE 也可能命中远超预期的行（{@code WHERE 1=1} 同样合法）。
     *       这一层只能在<b>执行之后、提交之前</b>判定：事前估算不可靠，而不可靠的那次
     *       恰恰是要防的那次。</li>
     *   <li><b>客户侧账号权限</b>——平台放行了，数据库仍可能拒。这是唯一不由我们承重的一层，
     *       也是最可靠的一层。</li>
     * </ol>
     *
     * <h3>为什么必须显式关掉连接的 readOnly</h3>
     * 连接池对每条连接设了 {@code readOnly=true}（只读的纵深防御之一），而 Connector/J 是
     * <b>在客户端就拦下</b>写语句的——不关掉的话，写操作根本发不到服务端，
     * 报的还是一个 {@code errorCode=0} 的含糊错误。用完必须恢复，否则这条连接还回池里之后
     * 会带着「可写」状态被下一次查询拿到。
     */
    @Override
    public WritePlan plan(String statement) {
        // 只过护栏，不借连接。走到这里的语句将原样进审批队列，批准后由 execute() 再跑一次同一道护栏
        // ——两次结论必然一致（同一个 guard、同一段文本），所以入队的就是将来执行的。
        WriteSqlGuard.Verdict verdict = writeGuard.check(statement);
        return new WritePlan(verdict.effectiveSql(), verdict.operation(), verdict.targetTable());
    }

    /**
     * 预估影响行数：把 {@code UPDATE t ... WHERE w} / {@code DELETE FROM t WHERE w}
     * 改写成 {@code SELECT COUNT(*) FROM t WHERE w}，在客户库上真跑一次。
     *
     * <h3>三个刻意的取舍</h3>
     * <ul>
     *   <li><b>走 AST 重写，不拼字符串。</b>从已经解析好的语法树上取表与 WHERE 再重新序列化，
     *       不会因为 WHERE 里有子查询、注释、奇怪的引号而拼错——拼错的后果是拿一个
     *       <b>看起来合理但数错了</b>的行数去给人做审批依据。</li>
     *   <li><b>INSERT 不估。</b>{@code INSERT ... SELECT} 的行数由子查询决定，
     *       跑一遍子查询才知道，代价与副作用都不可控；{@code INSERT ... VALUES} 的行数
     *       在语句里一眼可见，不需要估。两者都返回 null。</li>
     *   <li><b>任何失败都返回 null，绝不抛。</b>估不出来只是少一条参考信息，
     *       不该把一条本可以进审批队列的写请求挡在门外。</li>
     * </ul>
     *
     * <p>这个数只是<b>提交时</b>的快照，执行发生在人点批准之后。真正的防线仍是执行时的
     * {@code maxAffectedRows} 整条回滚。
     */
    @Override
    public Integer estimateAffectedRows(String statement) {
        String countSql;
        try {
            net.sf.jsqlparser.statement.Statement st = CCJSqlParserUtil.parse(statement);
            net.sf.jsqlparser.schema.Table table;
            net.sf.jsqlparser.expression.Expression where;
            if (st instanceof net.sf.jsqlparser.statement.update.Update up) {
                table = up.getTable();
                where = up.getWhere();
            } else if (st instanceof net.sf.jsqlparser.statement.delete.Delete del) {
                table = del.getTable();
                where = del.getWhere();
            } else {
                return null;   // INSERT 及其它：见类注释
            }
            if (table == null || where == null) return null;
            countSql = "SELECT COUNT(*) FROM " + table + " WHERE " + where;
        } catch (Exception e) {
            log.debug("预估影响行数：语句无法改写成 COUNT，跳过");
            return null;
        }

        try (Connection c = borrow();
             java.sql.Statement st = c.createStatement()) {
            st.setQueryTimeout(ESTIMATE_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(countSql)) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (Exception e) {
            // 超时、权限、方言差异都落这里。原始异常只进日志：它可能带 SQL 片段与主机名。
            log.warn("预估影响行数失败，按未知处理", e);
            return null;
        }
    }

    @Override
    public WriteResult execute(String statement, WriteOptions options) {
        WriteSqlGuard.Verdict verdict = writeGuard.check(statement);
        String sql = verdict.effectiveSql();

        long start = System.currentTimeMillis();
        try (Connection c = borrow()) {
            boolean originalAutoCommit = c.getAutoCommit();
            try {
                c.setReadOnly(false);
                // 手动事务：拿到影响行数之后才决定 commit 还是 rollback。
                c.setAutoCommit(false);
                int affected;
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(options.timeoutSec());
                    affected = st.executeUpdate(sql);
                }
                if (affected > options.maxAffectedRows()) {
                    c.rollback();
                    log.warn("写操作影响行数超限已回滚 connectorId={} table={} affected={} max={}",
                            instance.id(), verdict.targetTable(), affected, options.maxAffectedRows());
                    // 平台自己的行数闸：语句虽然发到了客户库，但事务已回滚，数据库本身并没有拒绝。
                    // 模型的下一步是收窄 WHERE 分批做，不是去要权限。
                    throw ConnectorException.of(ConnectorErrorCode.GUARD_BLOCKED,
                            "这条语句会影响 " + affected + " 行，超过平台单次上限 "
                                    + options.maxAffectedRows() + " 行。"
                                    + "已回滚，数据未被修改。请缩小 WHERE 的范围后分批执行");
                }
                c.commit();
                return new WriteResult(affected, sql, System.currentTimeMillis() - start);
            } catch (ConnectorException e) {
                throw e;
            } catch (SQLException e) {
                safeRollback(c);
                throw classify(e, "写入");
            } finally {
                // 还回池之前恢复原状：池里的连接是复用的，带着 autoCommit=false 或 readOnly=false
                // 回去，下一次查询就会拿到一条状态不对的连接——而且不报错。
                restore(c, originalAutoCommit);
            }
        } catch (SQLException e) {
            throw classify(e, "写入");
        }
    }

    private void safeRollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("写操作回滚失败 connectorId={}", instance.id(), e);
        }
    }

    private void restore(Connection c, boolean originalAutoCommit) {
        try {
            c.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            log.warn("恢复 autoCommit 失败 connectorId={}", instance.id(), e);
        }
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            log.warn("恢复 readOnly 失败 connectorId={}", instance.id(), e);
        }
    }

    // ================================================================ 能自描述

    /**
     * 排序用的临时二元组：目录条目 + 它的重要性档位。
     *
     * <p>package-private 而不是 private：排序规则是这次修复的全部内容，
     * 得让单测能直接钉住它，而这个仓库里没有 testcontainers / H2，连不了真库。
     * 只在排序时存在，从不序列化。
     */
    record Ranked(CatalogEntry entry, int tier) {
        String name() {
            return entry.name();
        }
    }

    /** 档位降序、同档按名字升序。见 {@link #importanceTier(Long)} 里的两条理由。 */
    static final Comparator<Ranked> BY_IMPORTANCE =
            Comparator.<Ranked>comparingInt(Ranked::tier).reversed().thenComparing(Ranked::name);

    /**
     * 目录的重要性档位：<b>估算行数的十进制位数</b>，越大越靠前。
     *
     * <h3>为什么是「数量级」而不是直接拿行数排</h3>
     * {@code TABLE_ROWS} 对 InnoDB 是<b>估算值</b>——来自索引统计的随机采样，
     * 同一张表两次查能差几倍，刚建或刚 ANALYZE 过的表还可能是 0。
     * 拿一个会抖的数做全序，排在第 200 名和第 201 名的两张表每次刷新都可能互换，
     * 而第 200 名正好是 {@code ConnectorSchemaService.MAX_OBJECTS} 的刀口——
     * 换位就意味着<b>保留下来的集合变了</b>，结构漂移检测会永远报一对 ADDED/REMOVED，
     * 挂在上面的语义跟着反复 STALE、反复恢复。一个永远在响的报警等于没有报警。
     * <p>取数量级之后，一张表要跨一档得让估算值动 <b>10 倍</b>——那已经不是采样噪声，是真的变了。
     *
     * <h3>为什么同档一定要用名字兜底</h3>
     * 同一个数量级里的表之间没有可信的高下之分，这时唯一重要的性质是<b>可重复</b>。
     * 表名在一个库里唯一、且不会自己变，是天然的确定性 tie-break。
     *
     * <h3>★ NULL 不是 0</h3>
     * VIEW 的 {@code TABLE_ROWS} <b>恒为 NULL</b>，某些引擎、统计信息不可用时也是 NULL。
     * 把 NULL 当 0，等于让客户 DBA 亲手建的 {@code v_xxx} 汇总视图——
     * 业务语义最浓、名字最像人话的那一批对象——整批沉到表尾被截掉。
     * 所以「不知道」单独放在 {@link #UNKNOWN_ROWS_TIER} 档，
     * 即「当它是一张几百行的普通表」：排在真正空的表前面，排在上千行的实表后面。
     * 这是个折中，但方向是对的——宁可让一个没人用的视图占掉一个名额，也不要整类对象消失。
     *
     * <p>用位数而不是 {@code log10}：SQL 侧走 {@code LENGTH(TABLE_ROWS)}，纯整数，没有浮点，
     * 两边算的是同一个函数。理由见 {@link #CATALOG_LIST_SQL}。
     *
     * @param approxRows {@code information_schema} 给的估算行数，{@code null} = 不知道
     */
    static int importanceTier(Long approxRows) {
        if (approxRows == null) {
            return UNKNOWN_ROWS_TIER;
        }
        if (approxRows < 1) {
            return 0;
        }
        return Long.toString(approxRows).length();
    }

    @Override
    public CatalogView catalog() {
        String countSql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
        try (Connection c = borrow()) {
            applyReadOnly(c);
            int total;
            try (PreparedStatement ps = c.prepareStatement(countSql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            List<Ranked> ranked = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(CATALOG_LIST_SQL)) {
                ps.setQueryTimeout(15);
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String comment = rs.getString("TABLE_COMMENT");
                        // MySQL 对视图的 TABLE_COMMENT 会返回字面量 "VIEW"，那不是注释，去掉免得误导模型。
                        if ("VIEW".equals(comment)) comment = null;
                        long raw = rs.getLong("TABLE_ROWS");
                        // ★ getLong 把 SQL NULL 读成 0。VIEW 的 TABLE_ROWS 恒为 NULL，
                        //   不做这一步区分，所有视图都会被当成空表排到最后然后被截掉。
                        Long rows = rs.wasNull() ? null : raw;
                        // TABLE_ROWS 对 InnoDB 是估算值，差几倍很常见。附上「约」字，
                        // 免得模型把它当成 COUNT(*) 的答案直接回给用户。
                        // 未知（null）和 0 都不写行数：前者没数可写，后者可能只是没统计过，
                        // 写「约 0 行」会让模型断言这张表是空的。
                        String withRows = (comment == null || comment.isBlank() ? "" : comment)
                                + (rows != null && rows > 0
                                ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
                        ranked.add(new Ranked(new CatalogEntry(rs.getString("TABLE_NAME"),
                                rs.getString("TABLE_TYPE"), withRows.isBlank() ? null : withRows),
                                importanceTier(rows)));
                    }
                }
            }
            // SQL 已经按同一个表达式排过一遍了，这里再排不是白做，两件事：
            // 一、SQL 那遍决定的是【哪 CATALOG_MAX 个能回来】，这件事只有数据库能做，Java 救不回来；
            // 二、这一遍决定【回来的这些以什么顺序交出去】。SQL 的 TABLE_NAME tie-break 走
            //    information_schema 那一列的排序规则，受 lower_case_table_names 影响，可能大小写不敏感；
            //    换一台 MySQL 就换一种顺序，而快照顺序不稳定正是漂移检测报假警的来源。
            //    把最终顺序钉在 Java 这边，同一个库在哪台机器上都得到同一份目录。
            ranked.sort(BY_IMPORTANCE);
            List<CatalogEntry> entries = ranked.stream().map(Ranked::entry).toList();
            return new CatalogView("TABLE", entries, total > entries.size(), total, CATALOG_ORDERING);
        } catch (SQLException e) {
            throw classify(e, "列目录");
        }
    }

    @Override
    public ObjectDetail describe(String object) {
        if (object == null || !IDENT_RE.matcher(object.trim()).matches()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "表名含有非法字符，只允许字母、数字、下划线和 $");
        }
        String name = object.trim();
        String colSql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT, COLUMN_KEY, EXTRA, COLUMN_DEFAULT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = borrow()) {
            applyReadOnly(c);
            List<FieldDetail> fields = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(colSql)) {
                ps.setQueryTimeout(15);
                ps.setString(1, database);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> extra = new ArrayList<>();
                        String key = rs.getString("COLUMN_KEY");
                        if ("PRI".equals(key)) extra.add("主键");
                        else if ("UNI".equals(key)) extra.add("唯一");
                        else if ("MUL".equals(key)) extra.add("有索引");
                        String ex = rs.getString("EXTRA");
                        if (ex != null && !ex.isBlank()) extra.add(ex);
                        String def = rs.getString("COLUMN_DEFAULT");
                        if (def != null) extra.add("默认 " + def);
                        fields.add(new FieldDetail(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("COLUMN_TYPE"),
                                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                                rs.getString("COLUMN_COMMENT"),
                                extra.isEmpty() ? null : String.join(" / ", extra)));
                    }
                }
            }
            if (fields.isEmpty()) {
                throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "库中不存在名为「" + name + "」的表，或当前账号看不到它。请先用 conn_catalog 确认表名");
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("database", database);
            extra.put("hint", "字段注释来自数据库本身，可能过时或为空。口径不明确时请向用户确认，不要自行假定。");
            return new ObjectDetail(name, "TABLE", null, fields, extra);
        } catch (SQLException e) {
            throw classify(e, "看结构");
        }
    }

    // ================================================================ 公共

    @Override
    public void close() {
        // 无状态：连接每次借还，这里没有需要释放的东西。
        // 保留空实现是为了让调用方统一用 try-with-resources，将来若改成持有连接不必改调用方。
    }

    private Connection borrow() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 双保险：JDBC 层的 {@code setReadOnly} 是「提示」（连接池已经设过一次），
     * {@code SET SESSION TRANSACTION READ ONLY} 是服务端<b>强制</b>。
     * 两者挡的东西不完全重合，而这一层本来就不承重，所以一道都不省。
     */
    private void applyReadOnly(Connection c) {
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("setReadOnly 失败，继续用会话级只读 connectorId={}", instance.id(), e);
        }
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            st.execute("SET SESSION TRANSACTION READ ONLY");
        } catch (SQLException e) {
            // 有些中间件（ProxySQL / 部分云 RDS 代理）不支持这条。不致命——承重层是客户侧的账号权限，
            // 这里只是纵深防御的一层，失败记日志继续。
            log.debug("SET SESSION TRANSACTION READ ONLY 失败 connectorId={} errorCode={}",
                    instance.id(), e.getErrorCode(), e);
        }
    }

    /**
     * {@code SQLException} → 统一错误语义。
     *
     * <p><b>{@code safeDetail} 一律是本方法里写死的常量句子。</b>
     * {@code e.getMessage()} 常带完整 SQL、主机名、有时带连接参数，而这个异常会被
     * 工具层转成返回值回灌模型、并落进 {@code ai_model_call_content}。原始异常只走日志。
     */
    ConnectorException classify(SQLException e, String action) {
        log.warn("MySQL 连接器{}失败 connectorId={} errorCode={} sqlState={}",
                action, instance.id(), e.getErrorCode(), e.getSQLState(), e);

        if (e instanceof SQLTimeoutException) {
            return ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
        }
        int code = e.getErrorCode();
        switch (code) {
            case 1205:      // ER_LOCK_WAIT_TIMEOUT
            case 3024:      // ER_QUERY_TIMEOUT（max_execution_time）
                return ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
            case 1044:      // ER_DBACCESS_DENIED_ERROR
            case 1142:      // ER_TABLEACCESS_DENIED_ERROR
            case 1143:      // ER_COLUMNACCESS_DENIED_ERROR
                return ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "这个数据库账号没有访问该对象的权限");
            case 1045:      // ER_ACCESS_DENIED_ERROR
                return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED, null);
            case 1146:      // ER_NO_SUCH_TABLE
            case 1049:      // ER_BAD_DB_ERROR
                return ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "指定的表或库不存在。请先用 conn_catalog 确认对象名称");
            case 1054:      // ER_BAD_FIELD_ERROR
                return ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "查询里引用了不存在的列。请先用 conn_describe 确认字段名");
            case 1064:      // ER_PARSE_ERROR
                return ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                        "数据库拒绝了这条语句：语法错误。请检查后重写");
            case 1040:      // ER_CON_COUNT_ERROR
            case 1203:      // ER_TOO_MANY_USER_CONNECTIONS
                return ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                        "数据库当前连接数已满，请稍后重试");
            default:
                break;
        }
        String state = e.getSQLState();
        if (state != null) {
            if (state.startsWith("28")) {
                return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED, null);
            }
            if (state.startsWith("08")) {
                return ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null);
            }
            if (state.startsWith("42")) {
                return ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "数据库拒绝了这次访问：对象不存在或权限不足");
            }
        }
        return ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR,
                "数据库返回了错误（错误码 " + code + "）");
    }
}

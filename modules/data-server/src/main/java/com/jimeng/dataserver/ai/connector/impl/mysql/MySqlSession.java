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
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
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
public class MySqlSession implements ConnectorSession, QueryCapable, DescribeCapable {

    /** 目录一次最多返回多少个对象。大库几百张表，全量塞进上下文放不下也没用。 */
    private static final int CATALOG_MAX = 500;

    /** 只读探针用的表名。刻意取一个不可能与客户业务表重名的名字。 */
    private static final String PROBE_TABLE = "__jm_readonly_probe__";

    /** {@code information_schema} 里按名字取对象时的白名单：防止把标识符拼进 SQL。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    private final ConnectorInstance instance;
    private final DataSource dataSource;
    private final String database;
    private final ReadOnlySqlGuard sqlGuard;

    MySqlSession(ConnectorInstance instance, DataSource dataSource, String database, ReadOnlySqlGuard sqlGuard) {
        this.instance = instance;
        this.dataSource = dataSource;
        this.database = database;
        this.sqlGuard = sqlGuard;
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
     */
    @Override
    public ReadOnlyVerdict verifyReadOnly() {
        String probe = "UPDATE `" + PROBE_TABLE + "` SET x = 1 WHERE 1 = 0";
        try (Connection c = borrow();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(10);
            st.executeUpdate(probe);
            // 竟然成功了：说明客户真有这张表，而且这个账号能写它。
            return ReadOnlyVerdict.writable("账号可以执行 UPDATE 语句");
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

    // ================================================================ 能自描述

    @Override
    public CatalogView catalog() {
        String countSql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
        String listSql = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
                + "ORDER BY TABLE_NAME LIMIT " + CATALOG_MAX;
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
            List<CatalogEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(listSql)) {
                ps.setQueryTimeout(15);
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String comment = rs.getString("TABLE_COMMENT");
                        // MySQL 对视图的 TABLE_COMMENT 会返回字面量 "VIEW"，那不是注释，去掉免得误导模型。
                        if ("VIEW".equals(comment)) comment = null;
                        long rows = rs.getLong("TABLE_ROWS");
                        // TABLE_ROWS 对 InnoDB 是估算值，差几倍很常见。附上「约」字，
                        // 免得模型把它当成 COUNT(*) 的答案直接回给用户。
                        String withRows = (comment == null || comment.isBlank() ? "" : comment)
                                + (rows > 0 ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
                        entries.add(new CatalogEntry(rs.getString("TABLE_NAME"),
                                rs.getString("TABLE_TYPE"), withRows.isBlank() ? null : withRows));
                    }
                }
            }
            return new CatalogView("TABLE", entries, total > entries.size(), total);
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

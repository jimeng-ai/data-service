package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 连接器的纯逻辑部分。
 *
 * <p>不连真库：仓库里没有 testcontainers / H2，也不该为此引入——
 * 这里要验的三件事（URL 拼装、只读判定表、异常归类）恰好都不需要真实连接。
 */
class MySqlConnectorTest {

    private final ConnectorProperties properties = new ConnectorProperties();
    private final MySqlConnector connector = new MySqlConnector(
            mock(CustomerDataSourceManager.class), new ReadOnlySqlGuard(), new WriteSqlGuard(), properties);

    private static ConnectorInstance inst(Map<String, Object> params, String credential) {
        return new ConnectorInstance(1L, "t1", "MYSQL", "crm", "CRM 库", params, credential, "direct");
    }

    private static Map<String, Object> baseParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", "db.example.com");
        m.put("port", 3306);
        m.put("database", "shop");
        m.put("username", "readonly");
        return m;
    }

    // ================================================================ JDBC URL

    @Nested
    @DisplayName("JDBC URL 安全参数")
    class JdbcUrl {

        @Test
        void 安全参数一个都不能少() {
            String url = MySqlConnector.buildJdbcUrl("db.example.com", 3306, "shop", false, 10);
            // 每一条都对应一个真实的攻击面，少一条就是开了一个口子。
            assertTrue(url.contains("allowMultiQueries=false"), "堆叠语句");
            assertTrue(url.contains("allowLoadLocalInfile=false"), "LOAD DATA LOCAL INFILE");
            assertTrue(url.contains("allowUrlInLocalInfile=false"), "URL 形式的 local infile");
            assertTrue(url.contains("autoDeserialize=false"), "反序列化 RCE");
            assertTrue(url.contains("allowPublicKeyRetrieval=false"), "中间人窃取明文密码");
            assertTrue(url.contains("zeroDateTimeBehavior=CONVERT_TO_NULL"));
            assertTrue(url.contains("connectTimeout=10000"));
            assertTrue(url.contains("socketTimeout="));
        }

        @Test
        void useSsl参数如实反映() {
            assertTrue(MySqlConnector.buildJdbcUrl("h", 3306, "d", true, 10).contains("useSSL=true"));
            assertTrue(MySqlConnector.buildJdbcUrl("h", 3306, "d", false, 10).contains("useSSL=false"));
        }
    }

    @Nested
    @DisplayName("host / database 注入防护")
    class InjectionGuard {

        /**
         * ★ 这是一条真实的提权路径：能往 host 或 database 里塞 ? 和 &，
         * 就能追加 ?allowLoadLocalInfile=true，把上面那一整排安全参数全部关掉。
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "db.example.com?allowLoadLocalInfile=true",
                "db.example.com&autoDeserialize=true",
                "db.example.com/other",
                "db.example.com:3307",
                "db example.com",
                "db.example.com#x"
        })
        void 非法host被拒(String host) {
            Map<String, Object> p = baseParams();
            p.put("host", host);
            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> connector.open(inst(p, "pwd")));
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "shop?allowLoadLocalInfile=true",
                "shop&x=1",
                "shop/x",
                "shop x"
        })
        void 非法database被拒(String db) {
            Map<String, Object> p = baseParams();
            p.put("database", db);
            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> connector.open(inst(p, "pwd")));
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        @Test
        void 缺少密码时明确报错() {
            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> connector.open(inst(baseParams(), null)));
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        @Test
        void 端口越界被拒() {
            Map<String, Object> p = baseParams();
            p.put("port", 70000);
            assertEquals(ConnectorErrorCode.CONFIG_ERROR,
                    assertThrows(ConnectorException.class, () -> connector.open(inst(p, "pwd"))).getCode());
        }
    }

    // ================================================================ 只读判定

    /**
     * ★ 本测试类里最重要的一组。
     *
     * <p>判定依据反直觉：MySQL 先检查权限、后检查表存在性，所以对一张不存在的表执行 UPDATE，
     * 「表不存在(1146)」反而说明这个账号<b>有</b>写权限。写反了不会有任何报错，
     * 只会让所有可写账号被判成只读——一个静默的安全降级。
     */
    @Nested
    @DisplayName("只读探针的错误码判定表")
    class ReadOnlyProbe {

        private ReadOnlyVerdict verdictFor(SQLException toThrow, boolean succeed) throws SQLException {
            DataSource ds = mock(DataSource.class);
            Connection c = mock(Connection.class);
            Statement st = mock(Statement.class);
            when(ds.getConnection()).thenReturn(c);
            when(c.createStatement()).thenReturn(st);
            if (succeed) {
                when(st.executeUpdate(anyString())).thenReturn(0);
            } else {
                when(st.executeUpdate(anyString())).thenThrow(toThrow);
            }
            MySqlSession session = new MySqlSession(
                    inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard());
            return session.verifyReadOnly();
        }

        private SQLException mysqlError(int code) {
            return new SQLException("msg", "42000", code);
        }

        @Test
        void 表权限被拒_1142_判为确认只读() throws SQLException {
            ReadOnlyVerdict v = verdictFor(mysqlError(1142), false);
            assertTrue(v.readOnly());
            assertTrue(v.acceptable());
        }

        @Test
        void 库权限被拒_1044_判为确认只读() throws SQLException {
            assertTrue(verdictFor(mysqlError(1044), false).acceptable());
        }

        @Test
        void 服务端只读_1290_判为确认只读() throws SQLException {
            assertTrue(verdictFor(mysqlError(1290), false).acceptable());
        }

        @Test
        void 表不存在_1146_判为可写() throws SQLException {
            // 反直觉但正确：权限检查已经过了，说明这个账号在该库上有 UPDATE 权限。
            ReadOnlyVerdict v = verdictFor(mysqlError(1146), false);
            assertFalse(v.readOnly());
            assertFalse(v.undetermined());
            assertFalse(v.acceptable());
        }

        @Test
        void 写操作竟然成功_判为可写() throws SQLException {
            ReadOnlyVerdict v = verdictFor(null, true);
            assertFalse(v.acceptable());
            assertFalse(v.undetermined());
        }

        @Test
        void 未知错误码_判为不确定而不是只读() throws SQLException {
            // 「不知道」绝不能当成「只读」——那就等于没验。
            ReadOnlyVerdict v = verdictFor(mysqlError(9999), false);
            assertTrue(v.undetermined());
            assertFalse(v.acceptable());
        }
    }

    // ================================================================ 异常归类

    @Nested
    @DisplayName("SQLException 归类与脱敏")
    class ErrorClassification {

        private final MySqlSession session = new MySqlSession(
                inst(baseParams(), "pwd"), mock(DataSource.class), "shop", new ReadOnlySqlGuard());

        @Test
        void 认证失败() {
            assertEquals(ConnectorErrorCode.AUTH_FAILED,
                    session.classify(new SQLException("m", "28000", 1045), "查询").getCode());
        }

        @Test
        void 连不上() {
            assertEquals(ConnectorErrorCode.UNREACHABLE,
                    session.classify(new SQLException("m", "08S01", 0), "查询").getCode());
        }

        @Test
        void 权限不足() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    session.classify(new SQLException("m", "42000", 1142), "查询").getCode());
        }

        @Test
        void 表不存在() {
            assertEquals(ConnectorErrorCode.NOT_FOUND,
                    session.classify(new SQLException("m", "42S02", 1146), "查询").getCode());
        }

        @Test
        void 超时() {
            assertEquals(ConnectorErrorCode.TIMEOUT,
                    session.classify(new java.sql.SQLTimeoutException("m"), "查询").getCode());
            assertEquals(ConnectorErrorCode.TIMEOUT,
                    session.classify(new SQLException("m", "HY000", 3024), "查询").getCode());
        }

        @Test
        void 连接数满被归为限流() {
            assertEquals(ConnectorErrorCode.RATE_LIMITED,
                    session.classify(new SQLException("m", "HY000", 1040), "查询").getCode());
        }

        /**
         * ★ 工具结果会被完整 JSON 化回灌模型、并落进 ai_model_call_content。
         * 原始 SQLException 的 message 常带完整 SQL 与主机名——一个字节都不准漏出去。
         */
        @Test
        void 原始异常消息绝不出现在对外文案里() {
            String leaky = "Unknown column 'x' in 'SELECT * FROM salary WHERE name=\"张三\"' on db-prod-01.internal";
            ConnectorException e = session.classify(new SQLException(leaky, "42S22", 1054), "查询");
            assertFalse(e.getMessage().contains("salary"));
            assertFalse(e.getMessage().contains("db-prod-01"));
            assertFalse(e.getMessage().contains("张三"));
            assertFalse(String.valueOf(e.getSafeDetail()).contains("salary"));
            assertFalse(String.valueOf(e.getSafeDetail()).contains("db-prod-01"));
        }
    }

    // ================================================================ 参数定义

    @Nested
    @DisplayName("参数定义驱动校验")
    class ParamSpecValidation {

        @Test
        void 必填缺失被发现() {
            List<String> errs = connector.paramSpec().validate(Map.of("host", "h"));
            assertFalse(errs.isEmpty());
        }

        @Test
        void 端口范围被校验() {
            Map<String, Object> p = new LinkedHashMap<>(baseParams());
            p.put("password", "x");
            p.put("port", 70000);
            assertFalse(connector.paramSpec().validate(p).isEmpty());
        }

        @Test
        void 未声明的参数被拒绝() {
            // 悄悄忽略一个拼错的字段名，等于让客户以为自己配上了。
            Map<String, Object> p = new LinkedHashMap<>(baseParams());
            p.put("password", "x");
            p.put("hostt", "typo");
            assertFalse(connector.paramSpec().validate(p).isEmpty());
        }

        @Test
        void 合法参数通过() {
            Map<String, Object> p = new LinkedHashMap<>(baseParams());
            p.put("password", "x");
            assertTrue(connector.paramSpec().validate(p).isEmpty());
        }

        @Test
        void 密码是敏感字段_不会进config_json() {
            assertTrue(connector.paramSpec().secretNames().contains("password"));
            Map<String, Object> p = new LinkedHashMap<>(baseParams());
            p.put("password", "super-secret");
            Map<String, Object> nonSecret = connector.paramSpec().normalizeNonSecret(p);
            assertFalse(nonSecret.containsKey("password"));
            assertFalse(String.valueOf(nonSecret).contains("super-secret"));
        }

        @Test
        void 数字按字符串传入也能解析() {
            // 全局 write_numbers_as_strings=true，前端回传的数字很可能是字符串。
            Map<String, Object> p = new LinkedHashMap<>(baseParams());
            p.put("password", "x");
            p.put("port", "3307");
            assertTrue(connector.paramSpec().validate(p).isEmpty());
            assertEquals(3307, connector.paramSpec().normalizeNonSecret(p).get("port"));
        }
    }

    /**
     * 目录的<b>排序</b>，也就是「500 张表里哪 200 张能拿到说明书」。
     *
     * <p>这里没有一条测试在测「排得好不好」——排序质量本来就没有客观标准。
     * 测的是三件会静默出错的事：<b>被砍掉的不再是「表名排在后面的」</b>、
     * <b>估算值抖动不会换掉保留的集合</b>、<b>视图不等于空表</b>。
     * 三件事错了都不报错，只表现为「模型答不上来」。
     */
    @Nested
    @DisplayName("目录按重要性排序")
    class CatalogOrdering {

        private MySqlSession.Ranked t(String name, Long approxRows) {
            return new MySqlSession.Ranked(new CatalogEntry(name, "BASE TABLE", null),
                    MySqlSession.importanceTier(approxRows));
        }

        private List<String> order(List<MySqlSession.Ranked> rows) {
            List<MySqlSession.Ranked> copy = new ArrayList<>(rows);
            copy.sort(MySqlSession.BY_IMPORTANCE);
            return copy.stream().map(MySqlSession.Ranked::name).toList();
        }

        // ---------------------------------------------------------- 档位本身

        @Test
        @DisplayName("档位就是十进制位数")
        void 档位是位数() {
            assertEquals(0, MySqlSession.importanceTier(0L));
            assertEquals(1, MySqlSession.importanceTier(1L));
            assertEquals(1, MySqlSession.importanceTier(9L));
            assertEquals(2, MySqlSession.importanceTier(10L));
            assertEquals(3, MySqlSession.importanceTier(999L));
            assertEquals(4, MySqlSession.importanceTier(1_000L));
            assertEquals(4, MySqlSession.importanceTier(8_371L));
            assertEquals(8, MySqlSession.importanceTier(12_000_000L));
        }

        /**
         * ★ 10 的整数次幂是 {@code FLOOR(LOG10(x))+1} 最容易出错的地方：
         * {@code LOG10(1000)} 在某些平台会返回 2.9999999999999996。
         * 这里用位数就是为了避开它——SQL 侧和 Java 侧必须算出同一个函数。
         */
        @Test
        @DisplayName("10 的整数次幂不掉档")
        void 整十次幂边界精确() {
            assertEquals(4, MySqlSession.importanceTier(1_000L));
            assertEquals(3, MySqlSession.importanceTier(999L));
            assertEquals(7, MySqlSession.importanceTier(1_000_000L));
            assertEquals(6, MySqlSession.importanceTier(999_999L));
            assertFalse(MySqlSession.CATALOG_LIST_SQL.contains("LOG10"),
                    "SQL 侧一旦改回浮点 LOG10，就会和 Java 侧在整十次幂上分叉");
        }

        // ---------------------------------------------------------- NULL ≠ 0

        /**
         * ★ VIEW 的 TABLE_ROWS 恒为 NULL。把 NULL 当 0，客户 DBA 亲手建的
         * {@code v_xxx} 汇总视图——业务语义最浓的那一批——会整批沉底被截掉。
         */
        @Test
        @DisplayName("行数未知不等于空表")
        void 未知行数排在空表前面() {
            assertTrue(MySqlSession.importanceTier(null) > MySqlSession.importanceTier(0L));
            assertTrue(MySqlSession.importanceTier(null) > MySqlSession.importanceTier(9L));
            assertEquals(List.of("v_sales_summary", "t_empty_log"),
                    order(List.of(t("t_empty_log", 0L), t("v_sales_summary", null))));
        }

        /** 但「不知道」也不该压过一张实打实的大表：它只是被当成一张几百行的普通表。 */
        @Test
        @DisplayName("行数未知不压过上千行的实表")
        void 未知行数排在大表后面() {
            assertTrue(MySqlSession.importanceTier(null) < MySqlSession.importanceTier(1_000L));
            assertEquals(List.of("t_ord_mst", "v_any_view"),
                    order(List.of(t("v_any_view", null), t("t_ord_mst", 8_000_000L))));
        }

        // ---------------------------------------------------------- 这次修复的正题

        /**
         * ★ 本切片存在的理由：按表名排序时，{@code t_ord_mst}（800 万行的订单主表）
         * 排在 {@code act_audit_log}（12 行）后面，500 张表的库里就会整片消失在刀口之外。
         */
        @Test
        @DisplayName("大表压过字典序靠前的小表")
        void 行数压过表名() {
            assertEquals(List.of("t_ord_mst", "act_audit_log"),
                    order(List.of(t("act_audit_log", 12L), t("t_ord_mst", 8_000_000L))));
        }

        /** 同档之间没有可信的高下之分，此时唯一重要的性质是可重复——按名字升序。 */
        @Test
        @DisplayName("同档按对象名升序")
        void 同档按名字() {
            assertEquals(List.of("a_tbl", "b_tbl", "c_tbl"),
                    order(List.of(t("c_tbl", 5_000L), t("a_tbl", 9_000L), t("b_tbl", 1_200L))));
        }

        /**
         * ★★ 确定性：这是选「数量级」而不是「原始行数」的全部理由。
         *
         * <p>TABLE_ROWS 是索引统计的采样估算，同一张没动过的表两次查能差几倍。
         * 直接按原始值排序，刀口（第 200 名）两侧的表每次刷新都可能互换——
         * 保留的集合一变，结构漂移检测就永远报一对 ADDED/REMOVED，
         * 挂在上面的语义反复 STALE、反复恢复。一个永远在响的报警等于没有报警。
         */
        @Test
        @DisplayName("估算值在同一数量级内抖动，顺序一模一样")
        void 估算抖动不改变顺序() {
            List<String> run1 = order(List.of(
                    t("t_ord_mst", 1_200_000L), t("t_ord_item", 8_400_000L),
                    t("t_usr", 42_000L), t("t_cfg", 130L)));
            // 同一个库，什么都没改，只是统计又采样了一次：每张表的估算值都变了，但数量级没变。
            List<String> run2 = order(List.of(
                    t("t_ord_mst", 3_900_000L), t("t_ord_item", 2_100_000L),
                    t("t_usr", 91_000L), t("t_cfg", 640L)));
            assertEquals(run1, run2);
            // 顺带钉住它确实是按数量级分的层，不是碰巧全按名字排。
            assertEquals(List.of("t_ord_item", "t_ord_mst", "t_usr", "t_cfg"), run1);
        }

        // ---------------------------------------------------------- 刀口在 SQL 里

        /**
         * ★ 截断是数据库执行 LIMIT 时发生的，被砍掉的行<b>根本不会回到 Java</b>。
         * 所以 ORDER BY 必须在 SQL 里、且必须在 LIMIT 之前；
         * 谁把它顺手改回 {@code ORDER BY TABLE_NAME}，行为会静默退回老样子。
         */
        @Test
        @DisplayName("排序在 SQL 里，且排在 LIMIT 之前")
        void 排序发生在数据库侧() {
            String sql = MySqlSession.CATALOG_LIST_SQL;
            int orderBy = sql.indexOf("ORDER BY");
            int limit = sql.indexOf("LIMIT");
            assertTrue(orderBy > 0, "目录 SQL 必须自己排序，Java 侧排序救不回被 LIMIT 砍掉的行");
            assertTrue(limit > orderBy, "ORDER BY 必须在 LIMIT 之前，否则截断的仍是任意一批");
            assertTrue(sql.contains("TABLE_ROWS IS NULL"), "NULL（视图）必须单独处理，不能落进 0");
            assertTrue(sql.contains("LENGTH(TABLE_ROWS)"), "档位 = 十进制位数，与 importanceTier 同一个函数");
            assertTrue(sql.contains("DESC"), "行数多的在前");
            assertTrue(sql.contains("TABLE_NAME ASC"), "同档必须有确定性 tie-break");
            assertFalse(sql.startsWith("SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS "
                            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME"),
                    "退回按表名排序 = 中文业务库里 t_ 开头的核心表整片拿不到说明书");
        }

        /** 排序依据要能被上层原样转述进截断消息，不能让上层自己编一句。 */
        @Test
        @DisplayName("排序依据对外可见")
        void 排序依据写在明面上() {
            assertNotNull(MySqlSession.CATALOG_ORDERING);
            assertTrue(MySqlSession.CATALOG_ORDERING.contains("行数"));
        }

        // ---------------------------------------------------------- 第二排序键：被声明外键引用的次数

        private MySqlSession.Ranked t(String name, Long approxRows, int refs) {
            return new MySqlSession.Ranked(new CatalogEntry(name, "BASE TABLE", null),
                    MySqlSession.importanceTier(approxRows), refs);
        }

        @Test
        @DisplayName("同一数量级内，被外键引用多的排前面，再按名字")
        void 同档先看被引用次数() {
            assertEquals(List.of("m_user", "m_sku", "a_log", "b_log"),
                    order(List.of(t("b_log", 5_000L, 0), t("m_sku", 6_000L, 2),
                            t("a_log", 7_000L, 0), t("m_user", 3_000L, 9))));
        }

        /**
         * 被引用次数不越档：一张被很多外键指着的几行字典表，不该压过没声明外键的千万行订单表——
         * 否则同一套业务在「写了外键」和「没写外键」的两个库里会拿到完全不同的截断结果。
         */
        @Test
        @DisplayName("被引用次数不越过数量级")
        void 被引用次数不越档() {
            assertEquals(List.of("t_ord_mst", "t_dict"),
                    order(List.of(t("t_dict", 12L, 40), t("t_ord_mst", 8_000_000L, 0))));
        }

        /**
         * ★ 如实钉住「对多数库它是空操作」：一个外键都没声明时，新排序与只按「数量级 + 名字」的旧排序逐行一致。
         * 我们自己的库就是 0 个外键。别把这个键读成「按重要性排序」的第二个信号——对这些库它什么都不做。
         */
        @Test
        @DisplayName("★ 库里一个外键都没声明时，顺序与旧规则逐行一致")
        void 没有外键时是空操作() {
            List<MySqlSession.Ranked> rows = List.of(
                    t("t_usr", 42_000L, 0), t("t_cfg", 130L, 0), t("t_ord_item", 8_400_000L, 0),
                    t("v_sales", null, 0), t("t_ord_mst", 1_200_000L, 0), t("t_empty", 0L, 0));
            Comparator<MySqlSession.Ranked> previousRule =
                    Comparator.<MySqlSession.Ranked>comparingInt(MySqlSession.Ranked::tier).reversed()
                            .thenComparing(MySqlSession.Ranked::name);
            List<MySqlSession.Ranked> byPrevious = new ArrayList<>(rows);
            byPrevious.sort(previousRule);
            assertEquals(byPrevious.stream().map(MySqlSession.Ranked::name).toList(), order(rows));
        }

        /** 外键声明是 DDL，不随统计采样抖动：行数估算在档内抖、被引用次数不变，顺序就一模一样。 */
        @Test
        @DisplayName("行数估算在档内抖动、外键不变 → 顺序不变，最后仍按名字兜底")
        void 加了外键键仍然确定() {
            List<String> run1 = order(List.of(t("b_tbl", 1_200L, 1), t("a_tbl", 9_900L, 1), t("c_tbl", 4_000L, 3)));
            List<String> run2 = order(List.of(t("b_tbl", 8_800L, 1), t("a_tbl", 1_100L, 1), t("c_tbl", 2_500L, 3)));
            assertEquals(run1, run2);
            assertEquals(List.of("c_tbl", "a_tbl", "b_tbl"), run1, "同档同引用数时按名字升序兜底");
        }

        /**
         * ★ 刀口在 SQL 里：被引用次数必须是 ORDER BY 的第二个键，在 TABLE_NAME 之前、LIMIT 之前。
         * 只在 Java 侧排是救不回被 LIMIT 砍掉的行的。
         */
        @Test
        @DisplayName("★ SQL：数量级 → 被引用次数 → 表名，都在 LIMIT 之前")
        void 外键计数在SQL的排序键里() {
            String sql = MySqlSession.CATALOG_LIST_SQL;
            int orderBy = sql.indexOf("ORDER BY");
            int tier = sql.indexOf("LENGTH(TABLE_ROWS)", orderBy);
            int refs = sql.indexOf("REF_N DESC", orderBy);
            int name = sql.indexOf("TABLE_NAME ASC", orderBy);
            int limit = sql.indexOf("LIMIT", orderBy);
            assertTrue(orderBy > 0 && tier > orderBy && refs > tier && name > refs && limit > name, sql);
        }

        @Test
        @DisplayName("SQL：数的是声明过的外键条数，且统计限定在本库")
        void 外键子查询的写法() {
            String sql = MySqlSession.CATALOG_LIST_SQL;
            assertTrue(sql.contains("information_schema.KEY_COLUMN_USAGE"), sql);
            assertTrue(sql.contains("REFERENCED_TABLE_NAME IS NOT NULL"), sql);
            // KEY_COLUMN_USAGE 一列一行：COUNT(*) 会把一条两列的复合外键数成 2。
            assertTrue(sql.contains("COUNT(DISTINCT TABLE_NAME, CONSTRAINT_NAME)"), sql);
            // 5.7 上 TABLE_SCHEMA 不是常量，会把整个实例所有库的表定义都打开一遍。
            assertTrue(sql.contains("TABLE_SCHEMA = ? AND REFERENCED_TABLE_SCHEMA = ?"), sql);
            // 两个视图的名字列排序规则不保证一致：两边都显式转成同一种。
            assertEquals(2, sql.split("COLLATE utf8mb4_general_ci", -1).length - 1, sql);
            assertTrue(sql.contains("COALESCE(jm_ref.REF_CNT, 0) AS REF_N"), "没被引用的表必须是 0 而不是 NULL");
        }

        /** 三个 ? 少绑一个是运行期才炸的错，而且炸在模型每次都会调的 conn_catalog 上。 */
        @Test
        @DisplayName("? 的个数与绑定次数一致")
        void 占位符个数与绑定一致() {
            long marks = MySqlSession.CATALOG_LIST_SQL.chars().filter(ch -> ch == '?').count();
            assertEquals(MySqlSession.CATALOG_LIST_PARAMS, marks);
        }

        @Test
        @DisplayName("排序依据里写明「只数声明过的外键、没声明的库全是 0」")
        void 排序依据如实说外键的局限() {
            assertTrue(MySqlSession.CATALOG_ORDERING.contains("外键"), MySqlSession.CATALOG_ORDERING);
            assertTrue(MySqlSession.CATALOG_ORDERING.contains("全是 0"), MySqlSession.CATALOG_ORDERING);
            assertTrue(MySqlSession.CATALOG_ORDERING.endsWith("再按对象名升序"), MySqlSession.CATALOG_ORDERING);
        }

        /** 端到端走一遍 catalog()：三个参数都绑上，REF_N 读出来并参与 Java 侧的最终排序。 */
        @Test
        @DisplayName("catalog()：绑满三个参数，并按 REF_N 排出最终顺序")
        void catalog端到端() throws SQLException {
            MySqlSession.resetCatalogFallbackForTest();
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            when(ds.getConnection()).thenReturn(conn);
            Statement engine = engineStatement("8.0.46", MYSQL_COMMENT, null);
            when(conn.createStatement()).thenReturn(engine);
            PreparedStatement countPs = mock(PreparedStatement.class);
            PreparedStatement listPs = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.CATALOG_LIST_SQL.equals(inv.getArgument(0)) ? listPs : countPs);
            ResultSet countRs = mock(ResultSet.class);
            when(countRs.next()).thenReturn(true, false);
            when(countRs.getInt(1)).thenReturn(3);
            when(countPs.executeQuery()).thenReturn(countRs);
            ResultSet listRs = resultSet(List.of(
                    row("TABLE_NAME", "b_log", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 5_000L, "REF_N", 0),
                    row("TABLE_NAME", "a_cfg", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 7_000L, "REF_N", 0),
                    row("TABLE_NAME", "m_user", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 6_000L, "REF_N", 4)));
            when(listPs.executeQuery()).thenReturn(listRs);

            CatalogView v = new MySqlSession(inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard()).catalog();

            for (int i = 1; i <= MySqlSession.CATALOG_LIST_PARAMS; i++) {
                verify(listPs).setString(i, "shop");
            }
            assertEquals(List.of("m_user", "a_cfg", "b_log"), v.entries().stream().map(CatalogEntry::name).toList());
            assertEquals(MySqlSession.CATALOG_ORDERING, v.ordering());
        }
    }

    // ================================================================ 目录用哪条 SQL

    /**
     * conn_catalog 热路径用哪条 SQL。
     *
     * <p>钉的几件事错了都不报错：选择跟着「这一次新 SQL 跑没跑成」走，每次部署重启都可能换一种顺序，200 处的刀口两侧 REMOVED/ADDED 来回翻；
     * 外键子查询在 Doris / StarRocks 上报方言错误时 conn_catalog 跟着整条坏掉；兜底之后交出去的排序说明还写着「按被外键引用的次数」；
     * 权限 / 认证 / 连不上这类该被看见的故障被兜底吞掉；5.7 上新 SQL 次次超时，conn_catalog 与定时刷新次次失败（线上跑兜底 SQL 时是好的）；
     * 新 SQL 偶尔超时时整条抛 TIMEOUT，而不是这一次拿兜底 SQL 作答；超时后连接已被连接池踢掉，兜底还在那条死连接上跑。
     */
    @Nested
    @DisplayName("★ 目录用哪条 SQL：只由跨重启不变的事实决定（引擎、版本、表数），方言错误兜底，超时只这一次兜底作答")
    class CatalogPlanRule {

        @BeforeEach
        void clearBefore() {
            MySqlSession.resetCatalogFallbackForTest();
        }

        @AfterEach
        void clearAfter() {
            // 方言错误的记忆是静态的：不清掉，别的用例（比如 catalog端到端）会被带进兜底路径。
            MySqlSession.resetCatalogFallbackForTest();
        }

        /** 一个假库：引擎事实、对象数、两条列表 SQL 各自成功还是失败，都可配。每次 open() 都是一套新的假件。 */
        private final class Db {
            Long id = 4242L;
            String credential = "pwd";
            int total = 3;
            String version = "8.0.46";
            String comment = MYSQL_COMMENT;
            SQLException engineFailure;
            SQLException primaryFailure;
            SQLException fallbackFailure;
            PreparedStatement primaryPs;
            PreparedStatement fallbackPs;
            /** 最近一次 open() 的 DataSource：用来数借了几次连接（超时后没被池踢掉的连接要接着用，不能一次调用占两条）。 */
            DataSource ds;

            Db engine(String v, String c) {
                version = v;
                comment = c;
                return this;
            }

            MySqlSession open() throws SQLException {
                DataSource ds = mock(DataSource.class);
                this.ds = ds;
                Connection conn = mock(Connection.class);
                when(ds.getConnection()).thenReturn(conn);
                // 先把引擎事实的假件造好再交给 thenReturn：在 thenReturn(...) 的参数里再去 stub 另一个 mock，Mockito 会报未完成的 stubbing。
                Statement engine = engineStatement(version, comment, engineFailure);
                when(conn.createStatement()).thenReturn(engine);
                PreparedStatement countPs = mock(PreparedStatement.class);
                PreparedStatement primary = mock(PreparedStatement.class);
                PreparedStatement fallback = mock(PreparedStatement.class);
                primaryPs = primary;
                fallbackPs = fallback;
                when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (MySqlSession.CATALOG_LIST_SQL.equals(sql)) return primary;
                    if (MySqlSession.CATALOG_LIST_SQL_FALLBACK.equals(sql)) return fallback;
                    return countPs;
                });
                ResultSet countRs = mock(ResultSet.class);
                when(countRs.next()).thenReturn(true, false);
                when(countRs.getInt(1)).thenReturn(total);
                when(countPs.executeQuery()).thenReturn(countRs);

                if (primaryFailure != null) {
                    when(primary.executeQuery()).thenThrow(primaryFailure);
                } else {
                    ResultSet rs = resultSet(List.of(
                            row("TABLE_NAME", "b_log", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 5_000L, "REF_N", 0),
                            row("TABLE_NAME", "a_cfg", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 7_000L, "REF_N", 0),
                            row("TABLE_NAME", "m_user", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 6_000L, "REF_N", 4)));
                    when(primary.executeQuery()).thenReturn(rs);
                }
                if (fallbackFailure != null) {
                    when(fallback.executeQuery()).thenThrow(fallbackFailure);
                } else {
                    ResultSet rs = resultSet(List.of(
                            row("TABLE_NAME", "b_log", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 5_000L),
                            row("TABLE_NAME", "a_cfg", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 7_000L),
                            row("TABLE_NAME", "m_user", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 6_000L)));
                    // 兜底 SQL 没有 REF_N 这一列：真驱动读它会抛，假件也抛——钉住兜底路径不去读它。
                    doThrow(new SQLException("Column 'REF_N' not found.", "S0022")).when(rs).getInt("REF_N");
                    when(fallback.executeQuery()).thenReturn(rs);
                }
                ConnectorInstance inst = new ConnectorInstance(id, "t1", "MYSQL", "crm", "CRM 库", baseParams(),
                        credential, "direct");
                return new MySqlSession(inst, ds, "shop", new ReadOnlySqlGuard());
            }
        }

        private static final List<String> PRIMARY_ORDER = List.of("m_user", "a_cfg", "b_log");
        private static final List<String> FALLBACK_ORDER = List.of("a_cfg", "b_log", "m_user");

        private List<String> names(CatalogView v) {
            return v.entries().stream().map(CatalogEntry::name).toList();
        }

        // ---------------------------------------------------------- 规则本身

        @Test
        @DisplayName("★ 规则判定表：认出别家引擎或读不出 → 兜底；版本低于 8 → 兜底；对象数过千 → 兜底；其余 → 新 SQL")
        void 规则判定表() {
            MySqlSession.EngineFacts mysql = MySqlSession.EngineFacts.of("8.0.46", MYSQL_COMMENT);
            int max = MySqlSession.CATALOG_PRIMARY_MAX_OBJECTS;
            assertEquals(MySqlSession.CatalogPlan.PRIMARY, MySqlSession.planCatalog(3, mysql));
            assertEquals(MySqlSession.CatalogPlan.PRIMARY, MySqlSession.planCatalog(max, mysql), "恰好等于上限仍用新 SQL");
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_SIZE, MySqlSession.planCatalog(max + 1, mysql));

            // MySQL 本家 8 及以上的几种常见写法：Percona、云厂商自编译、没有版本注释、9.x 创新版。
            // （5.7 的写法不再在这里：它现在按版本走兜底，见「版本低于 8」那一条用例。）
            for (MySqlSession.EngineFacts f : List.of(
                    MySqlSession.EngineFacts.of("8.0.36-28", "Percona Server (GPL), Release 28, Revision 47601f19"),
                    MySqlSession.EngineFacts.of("8.0.25-log", "Source distribution"),
                    MySqlSession.EngineFacts.of("8.0.28", null),
                    MySqlSession.EngineFacts.of("9.1.0", MYSQL_COMMENT))) {
                assertEquals(MySqlSession.CatalogPlan.PRIMARY, MySqlSession.planCatalog(3, f), f.toString());
            }
            // 兼容协议的别家引擎：名字在版本号或版本注释里，大小写不论。
            for (MySqlSession.EngineFacts f : List.of(
                    MySqlSession.EngineFacts.of("5.7.99", "Doris version doris-2.1.7-rc03-443e87e203"),
                    MySqlSession.EngineFacts.of("8.0.33", "StarRocks version 3.3.2-857dd73"),
                    MySqlSession.EngineFacts.of("5.7.99", "SelectDB Core version selectdb-doris-3.0.3"),
                    MySqlSession.EngineFacts.of("8.0.11-TiDB-v7.5.1",
                            "TiDB Server (Apache License 2.0) Community Edition, MySQL 8.0 compatible"),
                    MySqlSession.EngineFacts.of("5.7.25-OceanBase_CE-v4.2.1.2", "OceanBase_CE 4.2.1.2"),
                    MySqlSession.EngineFacts.of("10.6.12-MariaDB-1:10.6.12+maria~ubu2004", "mariadb.org binary distribution"),
                    MySqlSession.EngineFacts.of("8.0.30-Vitess", "Version: 17.0.0"))) {
                assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE, MySqlSession.planCatalog(3, f), f.toString());
            }
            // 读不出来 = 不是 MySQL（真 MySQL 不可能答不上这两个值）。
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE, MySqlSession.planCatalog(3, MySqlSession.EngineFacts.UNREADABLE));
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE,
                    MySqlSession.planCatalog(3, MySqlSession.EngineFacts.of(null, MYSQL_COMMENT)));
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE, MySqlSession.planCatalog(3, null));
            // 引擎先于规模。
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE,
                    MySqlSession.planCatalog(5_000, MySqlSession.EngineFacts.of("5.7.99", "Doris version doris-2.1.7")));
        }

        @Test
        @DisplayName("读引擎事实的 SQL 就是本地 MySQL 8.0.46 上实测过的那一条")
        void 引擎事实SQL形状() {
            assertEquals("SELECT VERSION() AS V, @@version_comment AS C", MySqlSession.ENGINE_FACTS_SQL);
        }

        // ---------------------------------------------------------- ★ 跨重启一致

        /**
         * ★ 本组存在的理由。清空进程内记忆就是一次部署重启：同样的事实，重启前后必须交出逐行相同的目录——
         * 否则刀口落在 REF_N 不同的那一档时，每次推 main 都是一对 REMOVED/ADDED、一批 STALE、一轮增量推导。
         */
        @Test
        @DisplayName("★ 跨重启：MySQL 小库 / Doris / 过千的库 / 名单外引擎报方言错误 / MySQL 5.7，重启前后顺序逐行一致")
        void 跨重启顺序一致() throws SQLException {
            Db small = new Db();
            Db doris = new Db().engine("5.7.99", "Doris version doris-2.1.7-rc03");
            Db large = new Db();
            large.total = MySqlSession.CATALOG_PRIMARY_MAX_OBJECTS + 1;
            Db unknownEngine = new Db();
            unknownEngine.primaryFailure = new SQLException("dialect", "HY000", 1105);
            // 5.7：按版本走兜底。版本号是跨重启不变的事实，重启前后必须交出同一份目录。
            Db mysql57 = new Db().engine("5.7.44-log", "MySQL Community Server (GPL)");

            for (Db db : List.of(small, doris, large, unknownEngine, mysql57)) {
                MySqlSession.resetCatalogFallbackForTest();
                CatalogView before = db.open().catalog();
                MySqlSession.resetCatalogFallbackForTest();   // 部署重启：进程内的记忆全没了
                CatalogView after = db.open().catalog();

                assertEquals(names(before), names(after));
                assertEquals(before.ordering(), after.ordering());
            }
        }

        @Test
        @DisplayName("★ 别家引擎（Doris）：新 SQL 一次都不试，直接兜底，排序说明写明没按引用次数排")
        void 别家引擎直接兜底() throws SQLException {
            Db db = new Db().engine("5.7.99", "Doris version doris-2.1.7-rc03");

            CatalogView v = db.open().catalog();

            verify(db.primaryPs, never()).executeQuery();
            verify(db.fallbackPs).setString(1, "shop");
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());
            // 兜底路径 refs 全是 0：同档按名字，m_user 不再因为被引用而排前面。
            assertEquals(FALLBACK_ORDER, names(v));
        }

        @Test
        @DisplayName("★ 对象数过千：新 SQL 一次都不试；恰好一千仍用新 SQL")
        void 对象数过千直接兜底() throws SQLException {
            Db large = new Db();
            large.total = MySqlSession.CATALOG_PRIMARY_MAX_OBJECTS + 1;
            CatalogView v = large.open().catalog();
            verify(large.primaryPs, never()).executeQuery();
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());
            assertEquals(MySqlSession.CATALOG_PRIMARY_MAX_OBJECTS + 1, v.total());

            Db edge = new Db();
            edge.total = MySqlSession.CATALOG_PRIMARY_MAX_OBJECTS;
            assertEquals(MySqlSession.CATALOG_ORDERING, edge.open().catalog().ordering());
            verify(edge.fallbackPs, never()).executeQuery();
        }

        // ---------------------------------------------------------- ★ 版本低于 8 走兜底

        /**
         * ★ 5.7 没有事务型数据字典，新 SQL 比兜底多开一整遍表定义，几百张表的库就越过 15 秒超时——而线上跑兜底 SQL 时是好的。
         * 版本号跨重启不变，所以按它分支；读不出主版本号按低版本算（证明不了是 8，就用线上跑过的那条）。
         */
        @Test
        @DisplayName("★ 版本低于 8（5.7 / 5.6 / Aurora 2 / 读不出主版本）→ 兜底；8 及以上 → 新 SQL；引擎规则在版本之前，版本在规模之前")
        void 版本低于8走兜底() {
            for (MySqlSession.EngineFacts f : List.of(
                    MySqlSession.EngineFacts.of("5.7.44-log", "MySQL Community Server (GPL)"),
                    MySqlSession.EngineFacts.of("5.7.44", "Source distribution"),
                    MySqlSession.EngineFacts.of("5.7.12", null),
                    MySqlSession.EngineFacts.of("5.6.51", MYSQL_COMMENT),
                    MySqlSession.EngineFacts.of("unknown", MYSQL_COMMENT))) {
                assertEquals(MySqlSession.CatalogPlan.FALLBACK_VERSION, MySqlSession.planCatalog(3, f), f.toString());
            }
            for (MySqlSession.EngineFacts f : List.of(
                    MySqlSession.EngineFacts.of("8.0.46", MYSQL_COMMENT),
                    MySqlSession.EngineFacts.of("8.4.3", MYSQL_COMMENT),
                    MySqlSession.EngineFacts.of("9.1.0", MYSQL_COMMENT))) {
                assertEquals(MySqlSession.CatalogPlan.PRIMARY, MySqlSession.planCatalog(3, f), f.toString());
            }
            // 先后：Doris 自报 5.7.99，仍归「引擎」；5.7 的大库归「版本」。
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_ENGINE,
                    MySqlSession.planCatalog(3, MySqlSession.EngineFacts.of("5.7.99", "Doris version doris-2.1.7")));
            assertEquals(MySqlSession.CatalogPlan.FALLBACK_VERSION,
                    MySqlSession.planCatalog(5_000, MySqlSession.EngineFacts.of("5.7.44-log", MYSQL_COMMENT)));
        }

        @Test
        @DisplayName("主版本号：只取开头连续的数字，读不出 → -1")
        void 主版本号解析() {
            assertEquals(8, MySqlSession.majorVersion("8.0.46"));
            assertEquals(5, MySqlSession.majorVersion("5.7.44-log"));
            assertEquals(8, MySqlSession.majorVersion("8.0.36-28"));
            assertEquals(10, MySqlSession.majorVersion("10.6.12-MariaDB"));
            assertEquals(9, MySqlSession.majorVersion(" 9.1.0"));
            assertEquals(-1, MySqlSession.majorVersion("v8.0"));
            assertEquals(-1, MySqlSession.majorVersion(""));
            assertEquals(-1, MySqlSession.majorVersion(null));
            assertEquals(-1, MySqlSession.majorVersion("123456789012.0"));
        }

        @Test
        @DisplayName("★ 端到端：5.7 上新 SQL 一次都不试，直接用线上跑过的兜底 SQL，排序说明写明没按引用次数排")
        void 版本低于8端到端() throws SQLException {
            Db db = new Db().engine("5.7.44-log", "MySQL Community Server (GPL)");

            CatalogView v = db.open().catalog();

            verify(db.primaryPs, never()).executeQuery();
            verify(db.fallbackPs).setString(1, "shop");
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());
            assertEquals(FALLBACK_ORDER, names(v));
        }

        // ---------------------------------------------------------- ★ 超时：这一次兜底作答，不记住

        /**
         * ★ 热路径上答得出来比顺序每次一样要紧。上一轮让新 SQL 超时原样抛 TIMEOUT：一个新 SQL 次次超时的库，conn_catalog 永久不可用、
         * 定时刷新永久失败。现在这一次拿兜底 SQL 作答、排序说明如实写兜底那句，并且不留记忆（不能退回「记在进程里、每次重启重赌」的老路）。
         */
        @Test
        @DisplayName("★ 新 SQL 超时（驱动超时 / max_execution_time / 被 KILL / 锁等待 / SQLState 70）→ 这一次兜底作答不抛；不记住；连接没被踢就接着用")
        void 超时这一次兜底作答() throws SQLException {
            for (SQLException timeout : List.of(new java.sql.SQLTimeoutException("slow"),
                    new SQLException("m", "HY000", 3024), new SQLException("m", "70100", 1317),
                    new SQLException("m", "HY000", 1205), new SQLException("m", "70100", 0))) {
                Db db = new Db();
                db.primaryFailure = timeout;

                MySqlSession s = db.open();
                CatalogView v = assertDoesNotThrow(s::catalog, timeout.toString());

                verify(db.primaryPs).executeQuery();
                verify(db.fallbackPs).setString(1, "shop");
                verify(db.fallbackPs).executeQuery();
                assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering(), "排序说明必须是这一次实际用的那条");
                assertEquals(FALLBACK_ORDER, names(v));
                assertEquals(3, v.total());
                // 假连接没被「池」踢掉（isClosed = false）：接着用原连接，一次调用只借一条。
                verify(db.ds, times(1)).getConnection();

                // 不记住：循环里刻意不清记忆，下一次照常先试新 SQL、按新 SQL 的顺序交出。
                Db next = new Db();
                CatalogView after = next.open().catalog();
                verify(next.primaryPs).executeQuery();
                assertEquals(MySqlSession.CATALOG_ORDERING, after.ordering(), "超时被记成了「只能兜底」");
                assertEquals(PRIMARY_ORDER, names(after));
            }
        }

        /**
         * ★ 线上真实路径：客户库的池是 HikariCP，驱动超时抛 SQLTimeoutException 后 Hikari 把这条连接踢出池、代理换成已关闭，
         * 之后在它上面的任何操作都报 08003。兜底要是还在原连接上跑，这一次照样失败（UNREACHABLE），修复等于没做。
         */
        @Test
        @DisplayName("★ 超时后原连接已被池踢掉（isClosed = true）→ 另借一条跑兜底并归还；原连接上不再发兜底 SQL")
        void 超时后连接被踢另借一条() throws SQLException {
            DataSource ds = mock(DataSource.class);
            Connection dead = mock(Connection.class);
            Connection fresh = mock(Connection.class);
            when(ds.getConnection()).thenReturn(dead, fresh);

            Statement engine = engineStatement("8.0.46", MYSQL_COMMENT, null);
            when(dead.createStatement()).thenReturn(engine);
            PreparedStatement countPs = mock(PreparedStatement.class);
            PreparedStatement primary = mock(PreparedStatement.class);
            when(dead.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.CATALOG_LIST_SQL.equals(inv.getArgument(0)) ? primary : countPs);
            ResultSet countRs = mock(ResultSet.class);
            when(countRs.next()).thenReturn(true, false);
            when(countRs.getInt(1)).thenReturn(3);
            when(countPs.executeQuery()).thenReturn(countRs);
            when(primary.executeQuery()).thenThrow(new java.sql.SQLTimeoutException("Statement cancelled due to timeout"));
            // Hikari 踢掉之后 isClosed() 就是 true（它只比对 delegate 是不是 CLOSED_CONNECTION）。
            when(dead.isClosed()).thenReturn(true);

            Statement freshSt = mock(Statement.class);
            when(fresh.createStatement()).thenReturn(freshSt);
            PreparedStatement fallback = mock(PreparedStatement.class);
            when(fresh.prepareStatement(MySqlSession.CATALOG_LIST_SQL_FALLBACK)).thenReturn(fallback);
            ResultSet rs = resultSet(List.of(
                    row("TABLE_NAME", "b_log", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 5_000L),
                    row("TABLE_NAME", "a_cfg", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 7_000L),
                    row("TABLE_NAME", "m_user", "TABLE_TYPE", "BASE TABLE", "TABLE_ROWS", 6_000L)));
            when(fallback.executeQuery()).thenReturn(rs);
            MySqlSession s = new MySqlSession(inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard());

            CatalogView v = assertDoesNotThrow(s::catalog);

            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());
            assertEquals(FALLBACK_ORDER, names(v));
            verify(ds, times(2)).getConnection();
            verify(fallback).setString(1, "shop");
            verify(dead, never()).prepareStatement(MySqlSession.CATALOG_LIST_SQL_FALLBACK);
            // 另借的那条要归还，并且和主路径一样先设只读。
            verify(fresh).close();
            verify(fresh).setReadOnly(true);
        }

        @Test
        @DisplayName("超时后兜底也超时 → 照常抛 TIMEOUT（不吞真故障），也不记住")
        void 超时后兜底也超时() throws SQLException {
            Db db = new Db();
            db.primaryFailure = new java.sql.SQLTimeoutException("slow");
            db.fallbackFailure = new java.sql.SQLTimeoutException("slow too");
            MySqlSession failing = db.open();

            ConnectorException e = assertThrows(ConnectorException.class, failing::catalog);

            assertEquals(ConnectorErrorCode.TIMEOUT, e.getCode());
            verify(db.fallbackPs).executeQuery();
            assertEquals(MySqlSession.CATALOG_ORDERING, new Db().open().catalog().ordering());
        }

        // ---------------------------------------------------------- 保险：方言错误

        @ParameterizedTest(name = "errorCode={0} sqlState={1}")
        @CsvSource({"1105, HY000", "1064, 42000", "1109, 42S02", "1054, 42S22", "1273, HY000"})
        @DisplayName("★ 规则放行、新 SQL 却报方言错误（名单外的引擎，含 42 类的缺视图 / 缺列）→ 走兜底")
        void 方言错误走兜底(int code, String state) throws SQLException {
            Db db = new Db();
            db.primaryFailure = new SQLException("dialect", state, code);

            CatalogView v = db.open().catalog();

            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());
            assertEquals(FALLBACK_ORDER, names(v));
            verify(db.fallbackPs).setString(1, "shop");
            assertEquals(3, v.total());
        }

        @Test
        @DisplayName("方言错误在本进程里记住：同一条连接下一次不再先试新 SQL，交出的顺序与再试一次完全相同")
        void 方言错误记住() throws SQLException {
            Db first = new Db();
            first.primaryFailure = new SQLException("dialect", "HY000", 1105);
            CatalogView a = first.open().catalog();

            Db second = new Db();
            CatalogView b = second.open().catalog();

            verify(second.primaryPs, never()).executeQuery();
            assertEquals(names(a), names(b));
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, b.ordering());
        }

        @Test
        @DisplayName("★ 引擎事实读不出：方言不认 → 按不是 MySQL 兜底、新 SQL 不试；超时 / 连不上 → 原样上抛，不替它挑 SQL")
        void 引擎事实读不出() throws SQLException {
            Db dialect = new Db();
            dialect.engineFailure = new SQLException("Unknown system variable 'version_comment'", "HY000", 1193);
            CatalogView v = dialect.open().catalog();
            verify(dialect.primaryPs, never()).executeQuery();
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, v.ordering());

            for (SQLException transientFailure : List.of(new java.sql.SQLTimeoutException("slow"),
                    new SQLException("gone", "08S01", 0))) {
                Db flaky = new Db();
                flaky.engineFailure = transientFailure;
                MySqlSession s = flaky.open();
                assertThrows(ConnectorException.class, s::catalog, transientFailure.toString());
                verify(flaky.primaryPs, never()).executeQuery();
                verify(flaky.fallbackPs, never()).executeQuery();
            }
        }

        @ParameterizedTest(name = "errorCode={0} sqlState={1} → {2}")
        @CsvSource({"1142, 42000, FORBIDDEN", "1044, 42000, FORBIDDEN", "1045, 28000, AUTH_FAILED",
                "0, 08S01, UNREACHABLE", "1040, HY000, RATE_LIMITED"})
        @DisplayName("★ 权限 / 认证 / 连不上 / 连接数满 → 原样上抛，不兜底、不记住")
        void 这些故障不兜底(int code, String state, ConnectorErrorCode expected) throws SQLException {
            Db db = new Db();
            db.primaryFailure = new SQLException("m", state, code);
            MySqlSession failing = db.open();
            ConnectorException e = assertThrows(ConnectorException.class, failing::catalog);

            assertEquals(expected, e.getCode());
            verify(db.fallbackPs, never()).executeQuery();

            // 没有被记成「只能兜底」：下一次照常先试新 SQL。
            CatalogView next = new Db().open().catalog();
            assertEquals(MySqlSession.CATALOG_ORDERING, next.ordering());
        }

        @Test
        @DisplayName("兜底 SQL 也失败 → 抛兜底的那个错误，而且不记住（问题不在外键子查询上）")
        void 兜底也失败() throws SQLException {
            Db db = new Db();
            db.primaryFailure = new SQLException("dialect", "HY000", 1105);
            db.fallbackFailure = new SQLException("gone", "08S01", 0);
            MySqlSession failing = db.open();
            ConnectorException e = assertThrows(ConnectorException.class, failing::catalog);
            assertEquals(ConnectorErrorCode.UNREACHABLE, e.getCode());

            Db next = new Db();
            CatalogView v = next.open().catalog();

            verify(next.primaryPs).executeQuery();
            assertEquals(MySqlSession.CATALOG_ORDERING, v.ordering());
        }

        @Test
        @DisplayName("参数或凭据改过（poolKey 变了）→ 重新试新 SQL")
        void 改过配置重新试() throws SQLException {
            Db db = new Db();
            db.primaryFailure = new SQLException("dialect", "HY000", 1105);
            db.open().catalog();

            Db rotated = new Db();
            rotated.credential = "pwd-rotated";
            CatalogView afterRotate = rotated.open().catalog();

            verify(rotated.primaryPs).executeQuery();
            assertEquals(MySqlSession.CATALOG_ORDERING, afterRotate.ordering());
            assertEquals(PRIMARY_ORDER, names(afterRotate));
        }

        @Test
        @DisplayName("没有 id 的实例（还没保存的连接）照样兜底，只是不记住")
        void 没有id不记() throws SQLException {
            Db unsaved = new Db();
            unsaved.id = null;
            unsaved.primaryFailure = new SQLException("dialect", "HY000", 1105);
            assertEquals(MySqlSession.CATALOG_ORDERING_FALLBACK, unsaved.open().catalog().ordering());

            Db again = new Db();
            again.id = null;
            again.open().catalog();

            verify(again.primaryPs).executeQuery();
        }

        @Test
        @DisplayName("兜底 SQL 就是上一版线上那一条：不碰 KEY_COLUMN_USAGE，一个 ?，数量级 → 表名，在 LIMIT 之前")
        void 兜底SQL形状() {
            String sql = MySqlSession.CATALOG_LIST_SQL_FALLBACK;
            assertEquals("SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = ? ORDER BY CASE WHEN TABLE_ROWS IS NULL THEN 3 WHEN TABLE_ROWS < 1 THEN 0 "
                    + "ELSE LENGTH(TABLE_ROWS) END DESC, TABLE_NAME ASC LIMIT 500", sql);
            assertFalse(sql.contains("KEY_COLUMN_USAGE"), sql);
            assertEquals(MySqlSession.CATALOG_LIST_FALLBACK_PARAMS, sql.chars().filter(ch -> ch == '?').count());
        }

        @Test
        @DisplayName("兜底的排序说明与原句不同：明说这次没按被外键引用的次数排")
        void 兜底排序说明() {
            assertNotEquals(MySqlSession.CATALOG_ORDERING, MySqlSession.CATALOG_ORDERING_FALLBACK);
            assertTrue(MySqlSession.CATALOG_ORDERING_FALLBACK.contains("没有按被外键引用的次数排序"),
                    MySqlSession.CATALOG_ORDERING_FALLBACK);
            assertFalse(MySqlSession.CATALOG_ORDERING_FALLBACK.contains("先按被外键引用的次数降序"),
                    MySqlSession.CATALOG_ORDERING_FALLBACK);
            assertTrue(MySqlSession.CATALOG_ORDERING_FALLBACK.startsWith("按估算行数的数量级降序"),
                    MySqlSession.CATALOG_ORDERING_FALLBACK);
        }

        /** 本地 MySQL 8.0.46 实测：缺视图 1109 (42S02)、缺列 1054 (42S22)、未知排序规则 1273 (HY000)。 */
        @Test
        @DisplayName("★ 判定表：按错误码判，42 类的方言错误不被当成权限不足；超时与被打断不算方言错误")
        void 判定表() {
            // ★ 时有时无的失败一律不算：拿它们换 SQL，顺序就跟着运气翻。
            assertFalse(MySqlSession.catalogFallbackEligible(new java.sql.SQLTimeoutException("t")));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "HY000", 3024)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "70100", 1317)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "HY000", 1205)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "70100", 1969)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "70100", 0)));
            assertTrue(MySqlSession.catalogFallbackEligible(new SQLException("m", "42S02", 1109)));
            assertTrue(MySqlSession.catalogFallbackEligible(new SQLException("m", "42S22", 1054)));
            assertTrue(MySqlSession.catalogFallbackEligible(new SQLException("m", "HY000", 1105)));
            assertTrue(MySqlSession.catalogFallbackEligible(new SQLException("m")));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "42000", 1142)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "42000", 1227)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "28000", 1045)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "28000", 0)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "08S01", 0)));
            assertFalse(MySqlSession.catalogFallbackEligible(new SQLException("m", "HY000", 1203)));
            assertFalse(MySqlSession.catalogFallbackEligible(null));
            // 同一个 1109，classify 会说 FORBIDDEN——这正是不能借 classify 的分类来判的原因。
            MySqlSession s = new MySqlSession(inst(baseParams(), "pwd"), mock(DataSource.class), "shop",
                    new ReadOnlySqlGuard());
            assertEquals(ConnectorErrorCode.FORBIDDEN, s.classify(new SQLException("m", "42S02", 1109), "列目录").getCode());
        }
    }

    // ================================================================ 唯一键采集

    /**
     * 唯一键采集（含主键、含多列唯一索引）。
     *
     * <p>这里钉的三件事错了都不报错：组合主键成员被写成「主键」，模型当它单列唯一去 join；
     * 追加的说明里带了「唯一」两个字，值域剖析把组合键成员当唯一列整批跳过；
     * 读不到索引时写一个空列表，把「不知道」说成「没有唯一键」。
     */
    @Nested
    @DisplayName("唯一键采集")
    class UniqueKeyCapture {

        private MySqlSession.KeyPart part(String index, int seq, String column) {
            return new MySqlSession.KeyPart(index, seq, column);
        }

        private MySqlSession.UniqueKey key(String name, String... cols) {
            return new MySqlSession.UniqueKey(name, "PRIMARY".equals(name), List.of(cols));
        }

        @Test
        @DisplayName("按索引分组、键内按 SEQ_IN_INDEX、主键在前；含函数键部分的索引整条丢掉")
        void 分组与顺序() {
            List<MySqlSession.UniqueKey> keys = MySqlSession.groupUniqueKeys(List.of(
                    part("uk_tenant_code", 2, "code"),
                    part("fx_lower_email", 1, null),
                    part("PRIMARY", 1, "id"),
                    part("uk_tenant_code", 1, "tenant_id")));

            assertEquals(2, keys.size(), keys.toString());
            assertEquals("PRIMARY", keys.get(0).name());
            assertTrue(keys.get(0).primary());
            assertEquals(List.of("id"), keys.get(0).columns());
            assertEquals("uk_tenant_code", keys.get(1).name());
            assertEquals(List.of("tenant_id", "code"), keys.get(1).columns(), "键内顺序必须按 SEQ_IN_INDEX，不按返回顺序");
        }

        @Test
        @DisplayName("组合键成员才追加说明；单列就唯一的、不在任何键里的、读不到索引的都不追加")
        void 组合键成员说明() {
            List<MySqlSession.UniqueKey> keys = List.of(
                    key("PRIMARY", "order_id", "line_no"), key("uk_sku", "sku_code"));

            assertEquals("组合键成员（order_id, line_no），单独这一列可能重复",
                    MySqlSession.compositeMemberNote("line_no", keys));
            assertEquals("组合键成员（order_id, line_no），单独这一列可能重复",
                    MySqlSession.compositeMemberNote("ORDER_ID", keys), "MySQL 列名不区分大小写");
            assertNull(MySqlSession.compositeMemberNote("sku_code", keys), "单列唯一键：它本来就唯一");
            assertNull(MySqlSession.compositeMemberNote("remark", keys));
            assertNull(MySqlSession.compositeMemberNote("line_no", null), "读不到索引时不能凭空说它是组合键成员");
        }

        @Test
        @DisplayName("一列属于两个组合键时取列数少的；它另有单列唯一键时不追加")
        void 多个组合键时的取法() {
            assertEquals("组合键成员（tenant_id, code），单独这一列可能重复",
                    MySqlSession.compositeMemberNote("code", List.of(
                            key("PRIMARY", "tenant_id", "code", "version"), key("uk_tc", "tenant_id", "code"))));
            assertNull(MySqlSession.compositeMemberNote("id", List.of(
                    key("uk_tenant_id", "tenant_id", "id"), key("PRIMARY", "id"))));
        }

        /**
         * ★ {@code SemanticValueProfiler} 按子串认「主键」「唯一」。追加句里只要出现「唯一」，
         * 组合唯一键的成员列（按地区、按日期这种低基数维度）就会被当成唯一列、整批不做值域剖析，而且不报错。
         */
        @Test
        @DisplayName("★ 追加的说明里不含「主键」「唯一」；含这两个词的中文列名也不列出")
        void 追加句不碰值域剖析的判据() {
            String note = MySqlSession.compositeMemberNote("region",
                    List.of(key("uk_region_day", "region", "day")));
            assertNotNull(note);
            assertFalse(note.contains("主键"), note);
            assertFalse(note.contains("唯一"), note);

            String cn = MySqlSession.compositeMemberNote("批次", List.of(key("uk_x", "唯一码", "批次")));
            assertNotNull(cn);
            assertFalse(cn.contains("唯一"), cn);
        }

        // ------------------------------------------------------------------ describe() 端到端

        private ObjectDetail describeWith(List<Map<String, Object>> columns, SQLException keyFailure,
                                          List<Map<String, Object>> keyRows) throws SQLException {
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(mock(Statement.class));
            PreparedStatement colPs = mock(PreparedStatement.class);
            PreparedStatement keyPs = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.UNIQUE_KEYS_SQL.equals(inv.getArgument(0)) ? keyPs : colPs);
            ResultSet colRs = resultSet(columns);
            when(colPs.executeQuery()).thenReturn(colRs);
            if (keyFailure != null) {
                when(keyPs.executeQuery()).thenThrow(keyFailure);
            } else {
                ResultSet keyRs = resultSet(keyRows);
                when(keyPs.executeQuery()).thenReturn(keyRs);
            }
            return new MySqlSession(inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard())
                    .describe("t_ord_dtl");
        }

        private List<Map<String, Object>> orderLineColumns() {
            return List.of(
                    row("COLUMN_NAME", "order_id", "COLUMN_TYPE", "bigint(20)", "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI"),
                    row("COLUMN_NAME", "line_no", "COLUMN_TYPE", "int(11)", "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI"),
                    row("COLUMN_NAME", "sku", "COLUMN_TYPE", "varchar(32)", "IS_NULLABLE", "YES", "COLUMN_KEY", "MUL",
                            "COLUMN_COMMENT", "商品编码"));
        }

        @Test
        @DisplayName("★ 联合主键：唯一键进对象级 extra，成员列追加说明，带「主键」的列数不变")
        void describe带回唯一键() throws SQLException {
            ObjectDetail d = describeWith(orderLineColumns(), null, List.of(
                    row("INDEX_NAME", "PRIMARY", "SEQ_IN_INDEX", 2, "COLUMN_NAME", "line_no"),
                    row("INDEX_NAME", "PRIMARY", "SEQ_IN_INDEX", 1, "COLUMN_NAME", "order_id")));

            assertEquals(List.of(Map.of("name", "PRIMARY", "primary", true,
                            "columns", List.of("order_id", "line_no"))),
                    d.extra().get(MySqlSession.EXTRA_UNIQUE_KEYS));

            Map<String, FieldDetail> byName = new LinkedHashMap<>();
            d.fields().forEach(f -> byName.put(f.name(), f));
            assertEquals("主键 / 组合键成员（order_id, line_no），单独这一列可能重复", byName.get("order_id").extra());
            assertEquals("有索引", byName.get("sku").extra());
            // 值域剖析靠数「主键」出现在几列上区分单列主键与联合主键成员，这个数不能被追加句改变。
            assertEquals(2, d.fields().stream().filter(f -> f.extra() != null && f.extra().contains("主键")).count());
            assertEquals(0, d.fields().stream().filter(f -> f.extra() != null && f.extra().contains("唯一")).count());
        }

        /** 读不到索引 ≠ 没有唯一键。放一个空列表，语义层会据此认定「目标列不属于任何组合键」。 */
        @Test
        @DisplayName("★ 读唯一键失败：describe 照常返回，extra 里没有 unique_keys（不是空列表）")
        void 读不到唯一键不等于没有() throws SQLException {
            ObjectDetail d = describeWith(orderLineColumns(), new SQLException("denied", "42000", 1142), null);

            assertEquals(3, d.fields().size());
            assertFalse(d.extra().containsKey(MySqlSession.EXTRA_UNIQUE_KEYS), d.extra().toString());
            assertEquals("主键", d.fields().get(0).extra(), "读不到索引时不追加任何组合键说明");
        }

        @Test
        @DisplayName("表确实没有唯一键：放空列表，与「读不到」区分开")
        void 没有唯一键是空列表() throws SQLException {
            ObjectDetail d = describeWith(List.of(
                    row("COLUMN_NAME", "msg", "COLUMN_TYPE", "text", "IS_NULLABLE", "YES")), null, List.of());
            assertEquals(List.of(), d.extra().get(MySqlSession.EXTRA_UNIQUE_KEYS));
        }

        /** 两边互不 import，靠这一条保证写进快照的键名和语义层读的是同一个。 */
        @Test
        @DisplayName("快照里的键名与语义层读取的键名一致")
        void 键名两边一致() {
            assertEquals(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, MySqlSession.EXTRA_UNIQUE_KEYS);
        }
    }

    // ================================================================ 对象是否存在（契约 K-1）

    /**
     * {@code existingObjects}：结构刷新在同一个会话里问「上一份快照里这些名字现在还在不在」。
     *
     * <p>钉的几件事错了都不报错：查不成时返回空集合（被读成「全删光了」，整片 STALE）；一个带 4 字节字符的名字让整条查询失败
     * （本地 MySQL 8.0.46 实测 ERROR 3988），其余名字全部变成「不知道」；按标识符白名单滤掉中文表名，把真实存在的表报成不存在；
     * 失败时抛出去，把一次成功的拉取否决掉。
     */
    @Nested
    @DisplayName("★ existingObjects：一条查询、只查给定的名字；查不成返回 null（不知道），从不抛")
    class ExistingObjects {

        private DataSource ds;
        private PreparedStatement ps;
        private final List<String> preparedSqls = new ArrayList<>();

        private MySqlSession session(List<String> storedNames, SQLException failure) throws SQLException {
            ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            ps = mock(PreparedStatement.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(mock(Statement.class));
            when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
                preparedSqls.add(inv.getArgument(0));
                return ps;
            });
            if (failure != null) {
                when(ps.executeQuery()).thenThrow(failure);
            } else {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String n : storedNames) {
                    rows.add(row("TABLE_NAME", n));
                }
                ResultSet rs = resultSet(rows);
                when(ps.executeQuery()).thenReturn(rs);
            }
            return new MySqlSession(inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard());
        }

        @Test
        @DisplayName("★ 返回库里存在的那几个（含视图、含中文名），一条查询，绑库名 + 去重后的每个名字")
        void 只查给定的名字() throws SQLException {
            MySqlSession s = session(List.of("t_cust", "v_ord", "订单表"), null);

            Set<String> got = s.existingObjects(List.of("t_cust", "v_ord", "gone_tbl", "订单表", "t_cust"));

            assertEquals(Set.of("t_cust", "v_ord", "订单表"), got);
            assertEquals(1, preparedSqls.size(), "一次调用只发一条查询");
            assertEquals(MySqlSession.existingObjectsSql(4), preparedSqls.get(0), "重复的名字只绑一次");
            verify(ps).setString(1, "shop");
            verify(ps).setString(2, "t_cust");
            verify(ps).setString(3, "v_ord");
            verify(ps).setString(4, "gone_tbl");
            verify(ps).setString(5, "订单表");
            verify(ps, times(5)).setString(anyInt(), anyString());
        }

        /** lower_case_table_names=1 时库里存小写、比较不分大小写：返回库里的写法，与目录列出来的名字一致。 */
        @Test
        @DisplayName("按库里存的写法返回")
        void 按库里存的写法返回() throws SQLException {
            assertEquals(Set.of("t_cust"), session(List.of("t_cust"), null).existingObjects(List.of("T_CUST")));
        }

        @Test
        @DisplayName("SQL 形状：TABLE_SCHEMA = ? 在前，占位符个数 = 名字数，名字一个都不拼进 SQL")
        void SQL形状() {
            assertEquals("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (?, ?, ?)",
                    MySqlSession.existingObjectsSql(3));
            assertEquals(1, MySqlSession.EXISTING_OBJECTS_SQL_PREFIX.chars().filter(ch -> ch == '?').count());
        }

        @Test
        @DisplayName("入参为空 / null → 空集合，不借连接")
        void 空入参() throws SQLException {
            MySqlSession s = session(List.of(), null);
            assertEquals(Set.of(), s.existingObjects(List.of()));
            assertEquals(Set.of(), s.existingObjects(null));
            verify(ds, never()).getConnection();
        }

        /** 这些名字不可能是 MySQL 对象名（BMP 以外的字符 / 超过 64 个字符 / 以空格结尾 / U+0000 / 空串 / null）。 */
        @Test
        @DisplayName("★ 不可能存在的名字不进查询、算作不存在；全都不可能时不借连接")
        void 不可能存在的名字不进查询() throws SQLException {
            MySqlSession s = session(List.of("t_cust"), null);
            List<String> input = Arrays.asList("t_cust", "😀tbl", "x".repeat(65), "t_cust ", "a b", "", null);

            assertEquals(Set.of("t_cust"), s.existingObjects(input));
            assertEquals(MySqlSession.existingObjectsSql(1), preparedSqls.get(0),
                    "带 4 字节字符的参数会让整条查询报 3988，其余名字跟着变成「不知道」");

            MySqlSession none = session(List.of(), null);
            assertEquals(Set.of(), none.existingObjects(List.of("😀", "y".repeat(65))));
            verify(ds, never()).getConnection();

            assertTrue(MySqlSession.couldBeMySqlObjectName("x".repeat(64)));
            assertTrue(MySqlSession.couldBeMySqlObjectName("订单表"), "中文表名真实存在，不能按标识符白名单滤掉");
            assertTrue(MySqlSession.couldBeMySqlObjectName("t-ord.2024"));
        }

        /** ★ 空集合的意思是「都不存在」。查不成时返回它，调用方会把整份快照当成被删光。 */
        @Test
        @DisplayName("★ 查询失败 / 借不到连接 / 未受检异常 → null（不知道），不是空集合，也不抛")
        void 失败返回null() throws SQLException {
            for (SQLException failure : List.of(new SQLException("gone", "08S01", 0),
                    new SQLException("Conversion from collation impossible", "HY000", 3988),
                    new java.sql.SQLTimeoutException("slow"),
                    new SQLException("denied", "42000", 1142))) {
                assertNull(session(List.of(), failure).existingObjects(List.of("t_cust")), failure.toString());
            }

            MySqlSession poolDown = session(List.of(), null);
            when(ds.getConnection()).thenThrow(new SQLException("pool exhausted", "08001", 0));
            assertNull(poolDown.existingObjects(List.of("t_cust")));

            MySqlSession driverBug = session(List.of(), null);
            when(ps.executeQuery()).thenThrow(new IllegalStateException("driver bug"));
            assertNull(driverBug.existingObjects(List.of("t_cust")));
        }

        @Test
        @DisplayName("名字数超过单次上限 → null（不知道），并且不查")
        void 超过上限() throws SQLException {
            List<String> many = IntStream.rangeClosed(0, MySqlSession.EXISTING_OBJECTS_MAX_NAMES)
                    .mapToObj(i -> "t" + i).toList();
            MySqlSession s = session(List.of(), null);

            assertNull(s.existingObjects(many));
            verify(ds, never()).getConnection();
        }
    }

    // ================================================================ JDBC 假件

    private static final String MYSQL_COMMENT = "MySQL Community Server - GPL";

    /**
     * catalog() 先读引擎事实：返回一个会回 {@code VERSION()} / {@code @@version_comment} 的 Statement
     * （applyReadOnly 用的也是它，execute 在假件上什么都不做）。
     *
     * @param failure 非 null 时读引擎事实就抛它
     */
    private static Statement engineStatement(String version, String comment, SQLException failure) throws SQLException {
        Statement st = mock(Statement.class);
        if (failure != null) {
            when(st.executeQuery(MySqlSession.ENGINE_FACTS_SQL)).thenThrow(failure);
        } else {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(version);
            when(rs.getString(2)).thenReturn(comment);
            when(st.executeQuery(MySqlSession.ENGINE_FACTS_SQL)).thenReturn(rs);
        }
        return st;
    }

    /** 一行：键值交替。用 HashMap 是因为列值可以是 null（Map.of 不收 null）。 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 按行吐数据的假 ResultSet：按列标签取值，wasNull 跟着最近一次取值走。 */
    private static ResultSet resultSet(List<Map<String, Object>> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        boolean[] lastNull = {false};
        when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
        when(rs.getString(anyString())).thenAnswer(inv -> {
            Object v = rows.get(cursor[0]).get(inv.<String>getArgument(0));
            lastNull[0] = v == null;
            return v == null ? null : String.valueOf(v);
        });
        when(rs.getLong(anyString())).thenAnswer(inv -> {
            Object v = rows.get(cursor[0]).get(inv.<String>getArgument(0));
            lastNull[0] = v == null;
            return v == null ? 0L : ((Number) v).longValue();
        });
        when(rs.getInt(anyString())).thenAnswer(inv -> {
            Object v = rows.get(cursor[0]).get(inv.<String>getArgument(0));
            lastNull[0] = v == null;
            return v == null ? 0 : ((Number) v).intValue();
        });
        when(rs.wasNull()).thenAnswer(inv -> lastNull[0]);
        return rs;
    }
}

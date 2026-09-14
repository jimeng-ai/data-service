package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    }
}

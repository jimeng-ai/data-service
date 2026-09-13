package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            mock(CustomerDataSourceManager.class), new ReadOnlySqlGuard(), properties);

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
}

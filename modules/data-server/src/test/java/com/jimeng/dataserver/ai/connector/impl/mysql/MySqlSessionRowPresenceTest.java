package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MySQL 侧的「这张表有没有行」探测（缺陷 B19）。
 *
 * <p>这一组要钉死的只有一句话：<b>「没探到」不是「是空的」</b>。
 * 探测失败被记成 {@code FALSE}，下游会给模型盖上「这张表一行数据都没有」的标记，
 * 模型照着这句话回答用户——查询成功、没有报错、结论是编的。这正是缺陷清单里反复出现的那一类。
 *
 * <p>不连真库：验的是调用编排与失败归类，mock 掉 JDBC 三件套正合适（仓库里没有 testcontainers / H2）。
 */
class MySqlSessionRowPresenceTest {

    private DataSource ds;
    private Connection conn;
    private MySqlSession session;
    /** 每个表名对应的桩：返回 true = 有行，false = 空表，抛出的异常 = 探测失败。 */
    private final Map<String, Object> stubs = new HashMap<>();
    /** 实际发出去的语句，按顺序。 */
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(mock(Statement.class));
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            executed.add(sql);
            Object stub = stubs.get(tableOf(sql));
            if (stub instanceof SQLException e) {
                throw e;
            }
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(Boolean.TRUE.equals(stub));
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        });
        session = new MySqlSession(instance(), ds, "shop", new ReadOnlySqlGuard());
    }

    /** 从 {@code SELECT 1 FROM `t` LIMIT 1} 里抠出表名。 */
    private static String tableOf(String sql) {
        int a = sql.indexOf('`');
        int b = sql.lastIndexOf('`');
        return a < 0 || b <= a ? sql : sql.substring(a + 1, b);
    }

    private static ConnectorInstance instance() {
        return new ConnectorInstance(1L, "t1", "MYSQL", "crm", "CRM 库",
                Map.of("host", "db.example.com"), "pwd", "direct", WritePolicy.FORBIDDEN);
    }

    // ================================================================ 正常两态

    @Test
    @DisplayName("空表 → FALSE，有数据的表 → TRUE")
    void 两态各自探到() {
        stubs.put("D1_COMPANYCODE", false);
        stubs.put("t_ord", true);

        Map<String, Boolean> r = session.probeRowPresence(List.of("D1_COMPANYCODE", "t_ord"), 5_000L);

        assertEquals(Boolean.FALSE, r.get("D1_COMPANYCODE"));
        assertEquals(Boolean.TRUE, r.get("t_ord"));
    }

    /**
     * ★ 语句必须是命中即停的 {@code LIMIT 1}。
     *
     * <p>{@code COUNT(*)} 在 InnoDB 上是真的全表/全索引扫——为了一个布尔付全表的代价；
     * {@code information_schema.TABLE_ROWS} 是抽样估算值，对「是不是恰好 0 行」并不可靠，
     * 而这里要的恰恰就是那个精确的边界。
     */
    @Test
    @DisplayName("★ 用 SELECT 1 ... LIMIT 1，不用 COUNT(*)，也不读 information_schema")
    void 探测语句命中即停() {
        stubs.put("t_ord", true);

        session.probeRowPresence(List.of("t_ord"), 5_000L);

        assertEquals(1, executed.size());
        String sql = executed.get(0).toUpperCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("LIMIT 1"), "必须命中即停。实际: " + executed.get(0));
        assertFalse(sql.contains("COUNT("), "COUNT(*) 是全表扫，为了一个布尔付不起。实际: " + executed.get(0));
        assertFalse(sql.contains("INFORMATION_SCHEMA"), "TABLE_ROWS 是估算值，判不了「恰好 0 行」。实际: " + executed.get(0));
    }

    // ================================================================ ★ 失败一律落到「不知道」

    /** ★ 本组最要紧的一条。 */
    @Test
    @DisplayName("★ 这一张探测失败 → 键不在（不知道），绝不是 FALSE（空表）")
    void 单张失败记成不知道而不是空表() {
        stubs.put("no_grant", new SQLException("access denied", "42000", 1142));
        stubs.put("t_ord", true);

        Map<String, Boolean> r = session.probeRowPresence(List.of("no_grant", "t_ord"), 5_000L);

        assertFalse(r.containsKey("no_grant"),
                "没权限 = 不知道。记成 FALSE 会让模型告诉用户「这张表里一行数据都没有」");
        assertNull(r.get("no_grant"));
        assertEquals(Boolean.TRUE, r.get("t_ord"), "一张表失败不影响其余的表");
    }

    @Test
    @DisplayName("★ 超时 → 不知道，而且后面的表照常探（连接没坏）")
    void 超时只影响这一张() {
        stubs.put("slow_view", new SQLTimeoutException("statement timeout", "HY000", 3024));
        stubs.put("t_ord", true);

        Map<String, Boolean> r = session.probeRowPresence(List.of("slow_view", "t_ord"), 5_000L);

        assertFalse(r.containsKey("slow_view"));
        assertEquals(Boolean.TRUE, r.get("t_ord"),
                "一次锁等待不该把后面 199 张表全部变成「不知道」");
    }

    /** 连接级的失败继续发语句只是把同一个失败重复 N 遍，还要占满整个预算。已探到的照常返回。 */
    @Test
    @DisplayName("连接断了 → 中止整批，已探到的照常返回")
    void 连接断掉时中止整批() {
        stubs.put("a_ok", true);
        stubs.put("b_gone", new SQLException("server gone", "08S01", 2006));
        stubs.put("c_never", false);

        Map<String, Boolean> r = session.probeRowPresence(List.of("a_ok", "b_gone", "c_never"), 5_000L);

        assertEquals(Boolean.TRUE, r.get("a_ok"));
        assertFalse(r.containsKey("b_gone"));
        assertFalse(r.containsKey("c_never"), "中止之后的表是「不知道」，不是「空」");
        assertEquals(List.of("a_ok", "b_gone").size(), executed.size(), "中止之后不该再发语句");
    }

    @Test
    @DisplayName("借不到连接 → 整批不知道，不抛")
    void 借不到连接时整批不知道() throws SQLException {
        when(ds.getConnection()).thenThrow(new SQLException("pool exhausted", "08004", 1040));

        Map<String, Boolean> r = session.probeRowPresence(List.of("t_ord"), 5_000L);

        assertTrue(r.isEmpty(), "拿不到连接就是全不知道；抛出去会让一次成功的结构拉取被一个叠加信息否决");
    }

    /**
     * 名字要拼进反引号里，白名单是注入防线，不能为了多探一张表放宽。
     * 过不了的名字按<b>不知道</b>处理——不是「不存在」，也不是「空」。
     */
    @Test
    @DisplayName("名字过不了标识符白名单 → 不发语句，且记成不知道")
    void 非法名字不发语句() {
        stubs.put("t_ord", true);

        Map<String, Boolean> r = session.probeRowPresence(List.of("t`ord; DROP TABLE x", "订单表", "t_ord"), 5_000L);

        assertEquals(List.of("SELECT 1 FROM `t_ord` LIMIT 1"), executed);
        assertFalse(r.containsKey("t`ord; DROP TABLE x"));
        assertFalse(r.containsKey("订单表"));
    }

    // ================================================================ 成本上限

    @Test
    @DisplayName("预算 <= 0 → 一条语句都不发，全部不知道")
    void 预算耗尽时不发语句() {
        stubs.put("t_ord", true);

        Map<String, Boolean> r = session.probeRowPresence(List.of("t_ord"), 0L);

        assertTrue(r.isEmpty());
        assertTrue(executed.isEmpty(), "预算是挂钟上限，用完就收手——剩下的留成「不知道」，不是「空」");
    }

    @Test
    @DisplayName("一次最多探 200 个对象，超出的按不知道")
    void 名字数有上限() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < MySqlSession.ROW_PRESENCE_MAX_NAMES + 50; i++) {
            names.add("t_" + i);
            stubs.put("t_" + i, true);
        }

        Map<String, Boolean> r = session.probeRowPresence(names, 60_000L);

        assertEquals(MySqlSession.ROW_PRESENCE_MAX_NAMES, r.size());
        assertFalse(r.containsKey("t_" + (MySqlSession.ROW_PRESENCE_MAX_NAMES + 10)));
    }

    @Test
    @DisplayName("空入参不惊动客户的库")
    void 空入参不开连接() {
        assertTrue(session.probeRowPresence(List.of(), 5_000L).isEmpty());
        assertTrue(session.probeRowPresence(null, 5_000L).isEmpty());
        assertTrue(executed.isEmpty());
    }

    // ================================================================ 中止判据本身

    /**
     * ★ 中止判据只认「这条连接废了」，不认「这一句失败了」。
     * 借 {@code catalogFallbackEligible} 那套判据来当中止条件，会让一次锁等待
     * 把后面所有表全部变成「不知道」——一个静默的大范围降级。
     */
    @Test
    @DisplayName("★ 中止判据：只有连接级失败才中止，超时和权限不中止")
    void 中止判据只认连接级失败() {
        assertTrue(MySqlSession.rowPresenceAbortsBatch(new SQLException("gone", "08S01", 2006)));
        assertTrue(MySqlSession.rowPresenceAbortsBatch(new SQLException("lost", "HY000", 2013)));
        assertTrue(MySqlSession.rowPresenceAbortsBatch(new SQLException("too many", "08004", 1040)));

        assertFalse(MySqlSession.rowPresenceAbortsBatch(new SQLTimeoutException("timeout", "HY000", 3024)));
        assertFalse(MySqlSession.rowPresenceAbortsBatch(new SQLException("denied", "42000", 1142)));
        assertFalse(MySqlSession.rowPresenceAbortsBatch(new SQLException("no such table", "42S02", 1146)));
        assertFalse(MySqlSession.rowPresenceAbortsBatch(null));
    }
}

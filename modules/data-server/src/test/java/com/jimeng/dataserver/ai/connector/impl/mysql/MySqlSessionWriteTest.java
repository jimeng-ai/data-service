package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 写通道的事务与连接状态。
 *
 * <p>这四件事全都<b>坏得无声无息</b>，所以必须有回归保护：
 *
 * <ul>
 *   <li><b>超限不回滚</b> —— 一条漏了 WHERE 或命中范围过大的语句直接提交，
 *       事后只能从备份恢复。</li>
 *   <li><b>忘了 setReadOnly(false)</b> —— Connector/J 在<b>客户端</b>就拦下写语句，
 *       语句根本没发到服务端，报的是一个 {@code errorCode=0} 的含糊错误，
 *       排查时会一路怀疑到客户的库权限上去。</li>
 *   <li><b>finally 没恢复连接状态</b> —— 连接带着 {@code autoCommit=false} /
 *       {@code readOnly=false} 还回池里，下一次查询拿到它之后行为不对<b>而且不报错</b>。</li>
 *   <li><b>护栏之前就去连库</b> —— 白占客户的一条连接，更要命的是护栏若被绕过，
 *       语句已经在数据库里了。</li>
 * </ul>
 *
 * <p>不连真库：这里验的是调用编排，mock 掉 JDBC 三件套正合适（仓库里也没有 testcontainers / H2）。
 */
class MySqlSessionWriteTest {

    /** 上限刻意取小，让「超限」这条用例的意图一眼可见。 */
    private static final WriteOptions OPTS = new WriteOptions(200, 10);
    private static final String SQL = "UPDATE orders SET status = 'PAID' WHERE id = 7";

    private DataSource ds;
    private Connection conn;
    private Statement st;
    private MySqlSession session;

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        st = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(st);
        // 池里借出来的连接是 autoCommit=true 的；finally 要恢复的就是这个值。
        when(conn.getAutoCommit()).thenReturn(true);
        session = new MySqlSession(instance(), ds, "shop", new ReadOnlySqlGuard(), new WriteSqlGuard());
    }

    private static ConnectorInstance instance() {
        return new ConnectorInstance(1L, "t1", "MYSQL", "crm", "CRM 库",
                Map.of("host", "db.example.com"), "pwd", "direct", WritePolicy.AUTO);
    }

    // ================================================================ ★ 影响行数上限

    /**
     * ★ 这一组是整个写通道的最后一道闸。
     *
     * <p>语句护栏只能保证「带了 WHERE」，保证不了「WHERE 命中的行数合理」
     * （{@code WHERE 1 = 1} 语法完全合法）。而影响行数只能在<b>执行之后、提交之前</b>拿到——
     * 事前估算不可靠，而不可靠的那一次恰恰是要防的那一次。
     * 所以「先执行、看行数、再决定 commit 还是 rollback」这个顺序不是实现细节，是设计。
     */
    @Nested
    @DisplayName("影响行数上限与回滚")
    class AffectedRowsLimit {

        @Test
        void 超限时回滚且不提交且抛错() throws SQLException {
            when(st.executeUpdate(anyString())).thenReturn(201);

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> session.execute(SQL, OPTS));

            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
            verify(conn).rollback();
            verify(conn, never()).commit();
            // 文案要让人（和模型）确信数据没变，否则接下来会有人去手工「补救」一次本没发生的修改。
            assertTrue(e.getMessage().contains("已回滚"), "实际文案: " + e.getMessage());
            assertTrue(e.getMessage().contains("201"));
        }

        @Test
        void 正好等于上限时提交() throws SQLException {
            // 边界要放行：上限写的是「最多改这么多行」，不是「少于这么多行」。
            // 差一位会让一个正好撞上限的正常批量操作被无谓地拒掉。
            when(st.executeUpdate(anyString())).thenReturn(200);

            WriteResult r = session.execute(SQL, OPTS);

            assertEquals(200, r.affectedRows());
            verify(conn).commit();
            verify(conn, never()).rollback();
        }

        @Test
        void 未超限时提交并返回实际执行的语句() throws SQLException {
            when(st.executeUpdate(anyString())).thenReturn(3);

            WriteResult r = session.execute(SQL, OPTS);

            assertEquals(3, r.affectedRows());
            // effectiveStatement 是事后唯一能核对「到底执行了什么」的东西，不能是空的。
            assertTrue(r.effectiveStatement().toUpperCase().startsWith("UPDATE"));
            verify(conn).commit();
            verify(conn, never()).rollback();
        }

        @Test
        void 零行也算成功提交() throws SQLException {
            // 0 行不是失败：WHERE 没命中是一种正常结果。
            // 提示模型「这可能不是你以为的成功」是 WriteResult 的职责，不是在这里抛错。
            when(st.executeUpdate(anyString())).thenReturn(0);

            assertEquals(0, session.execute(SQL, OPTS).affectedRows());
            verify(conn).commit();
        }
    }

    // ================================================================ 连接状态

    @Nested
    @DisplayName("连接状态的开关与恢复")
    class ConnectionState {

        /**
         * ★ 不关掉 readOnly，Connector/J 会在客户端直接抛
         * 「Connection is read-only…」，{@code errorCode=0}，语句根本没发出去。
         * 症状是「写不了」而错误信息里完全看不出原因——很容易被误判成客户的账号权限问题。
         */
        @Test
        void 执行前关掉了连接的readOnly() throws SQLException {
            when(st.executeUpdate(anyString())).thenReturn(1);

            session.execute(SQL, OPTS);

            verify(conn).setReadOnly(false);
        }

        @Test
        void 成功路径恢复了autoCommit与readOnly() throws SQLException {
            when(st.executeUpdate(anyString())).thenReturn(1);

            session.execute(SQL, OPTS);

            // 恢复的是借出来时的原值，不是写死的 true——池的配置将来可能变。
            verify(conn).setAutoCommit(true);
            verify(conn).setReadOnly(true);
        }

        @Test
        void 超限回滚后同样恢复了连接状态() throws SQLException {
            // 失败路径才是真正会被忘掉的那条：这条连接紧接着就被下一次查询借走。
            when(st.executeUpdate(anyString())).thenReturn(5000);

            assertThrows(ConnectorException.class, () -> session.execute(SQL, OPTS));

            verify(conn).setAutoCommit(true);
            verify(conn).setReadOnly(true);
        }

        @Test
        void 数据库报错后同样恢复了连接状态() throws SQLException {
            when(st.executeUpdate(anyString())).thenThrow(new SQLException("m", "42000", 1142));

            assertThrows(ConnectorException.class, () -> session.execute(SQL, OPTS));

            verify(conn).setAutoCommit(true);
            verify(conn).setReadOnly(true);
        }
    }

    // ================================================================ 数据库报错

    @Nested
    @DisplayName("数据库报错")
    class SqlFailure {

        @Test
        void 先回滚再按错误码归类抛出() throws SQLException {
            when(st.executeUpdate(anyString())).thenThrow(new SQLException("m", "42000", 1142));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> session.execute(SQL, OPTS));

            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
            // 顺序有意义：回滚必须发生在把连接状态还原、还回池之前。
            InOrder order = inOrder(conn);
            order.verify(conn).rollback();
            order.verify(conn).setAutoCommit(true);
            verify(conn, never()).commit();
        }

        @Test
        void 原始异常消息不进对外文案() throws SQLException {
            // 这个异常会被工具层转成返回值回灌模型，并落进 ai_model_call_content。
            String leaky = "Duplicate entry for key 'PRIMARY' in 'salary' on db-prod-01.internal";
            when(st.executeUpdate(anyString())).thenThrow(new SQLException(leaky, "23000", 1062));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> session.execute(SQL, OPTS));

            assertFalse(e.getMessage().contains("salary"));
            assertFalse(e.getMessage().contains("db-prod-01"));
            assertFalse(String.valueOf(e.getSafeDetail()).contains("db-prod-01"));
        }
    }

    // ================================================================ 护栏在连库之前

    /**
     * ★ 护栏不过就<b>根本不去借连接</b>。
     *
     * <p>两个理由，后一个才是重点：一是不白占客户库的一条连接；
     * 二是万一将来有人把护栏挪到 {@code executeUpdate} 之后，语句<b>已经执行了</b>，
     * 再回滚也来不及（DDL 根本回滚不了）。把「先护栏、后连库」这个顺序钉死在测试里。
     */
    @Nested
    @DisplayName("语句护栏在连库之前")
    class GuardBeforeConnect {

        private void assertRejectedWithoutConnecting(String sql) throws SQLException {
            assertThrows(ConnectorException.class, () -> session.execute(sql, OPTS));
            verify(ds, never()).getConnection();
        }

        @Test
        void UPDATE不带WHERE_不连库() throws SQLException {
            assertRejectedWithoutConnecting("UPDATE orders SET status = 'CANCELLED'");
        }

        @Test
        void DELETE不带WHERE_不连库() throws SQLException {
            assertRejectedWithoutConnecting("DELETE FROM orders");
        }

        @Test
        void DROP_不连库() throws SQLException {
            assertRejectedWithoutConnecting("DROP TABLE orders");
        }

        @Test
        void 堆叠语句_不连库() throws SQLException {
            assertRejectedWithoutConnecting("UPDATE orders SET status = 1 WHERE id = 1; DROP TABLE orders");
        }
    }

    @Test
    void 语句超时按护栏参数下发() throws SQLException {
        // 写操作持有锁，超时比查询更要紧：拖久了阻塞的是客户的业务。
        when(st.executeUpdate(anyString())).thenReturn(1);

        session.execute(SQL, new WriteOptions(200, 7));

        verify(st).setQueryTimeout(7);
    }
}

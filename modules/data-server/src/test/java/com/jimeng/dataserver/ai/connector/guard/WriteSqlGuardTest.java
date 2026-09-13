package com.jimeng.dataserver.ai.connector.guard;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写语句护栏。
 *
 * <p><b>这个类里最重要的一组是「必须带 WHERE」。</b>它挡的不是攻击，而是一类语法上无可挑剔、
 * 执行下去却改掉整张表的事故语句（{@code UPDATE orders SET status = 'CANCELLED'}）。
 * 这种语句不会报错、不会触发任何告警，回滚要靠备份——所以它必须在进数据库之前就被拦。
 *
 * <p>沿用只读护栏那份测试的写法：<b>每一条「该拒的」都配一条「长得像但合法的」</b>。
 * 只测拒绝很容易写出一个把所有写语句都拒掉的护栏，而那在测试里是全绿的，
 * 直到上线后发现写通道根本不能用。
 */
class WriteSqlGuardTest {

    private final WriteSqlGuard guard = new WriteSqlGuard();

    private ConnectorException reject(String sql) {
        return assertThrows(ConnectorException.class, () -> guard.check(sql));
    }

    // ================================================================ ★ 必须带 WHERE

    /**
     * ★ 本测试类的核心。
     *
     * <p>拒的那一半和放行的那一半同样重要：护栏若把带 WHERE 的正常语句也拒掉，
     * 运营会绕过平台直接上库改数据，那比没有护栏更糟。
     */
    @Nested
    @DisplayName("UPDATE / DELETE 必须带 WHERE")
    class WhereRequired {

        @Test
        void UPDATE_不带WHERE_被拒() {
            ConnectorException e = reject("UPDATE orders SET status = 'CANCELLED'");
            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
            // 文案要说清「为什么拒」和「该怎么办」：模型拿到的就是这句话，
            // 只说「被拒绝」它会原样重试一遍。
            assertTrue(e.getMessage().contains("WHERE"));
        }

        @Test
        void UPDATE_带WHERE_放行() {
            var v = guard.check("UPDATE orders SET status = 'CANCELLED' WHERE id = 2");
            assertEquals("UPDATE", v.operation());
            assertNotNull(v.effectiveSql());
        }

        @Test
        void DELETE_不带WHERE_被拒() {
            ConnectorException e = reject("DELETE FROM orders");
            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains("WHERE"));
        }

        @Test
        void DELETE_带WHERE_放行() {
            var v = guard.check("DELETE FROM orders WHERE id = 2");
            assertEquals("DELETE", v.operation());
        }

        @Test
        void 恒真WHERE也放行_那一层交给影响行数上限() {
            // 刻意记下这条：WHERE 1 = 1 语法合法、护栏拦不住，
            // 它由 MySqlSession 里「影响行数超限就回滚」那道闸兜底。
            // 想在这里做语义判断是条死路——WHERE status IS NOT NULL 一样能命中全表。
            assertNotNull(guard.check("UPDATE orders SET status = 1 WHERE 1 = 1"));
        }

        @Test
        void 子查询作为WHERE条件放行() {
            assertNotNull(guard.check("UPDATE orders SET status = 1 WHERE id IN (SELECT id FROM refunds)"));
        }
    }

    // ================================================================ 允许的三种

    @Nested
    @DisplayName("只允许 INSERT / UPDATE / DELETE")
    class AllowedStatements {

        @Test
        void INSERT_放行_它没有WHERE的概念() {
            // 不能因为「没有 WHERE」就套用上面那条规则：INSERT 的作用范围写死在 VALUES 里。
            var v = guard.check("INSERT INTO audit_log(op) VALUES('x')");
            assertEquals("INSERT", v.operation());
            assertEquals("audit_log", v.targetTable());
        }

        @Test
        void INSERT_SELECT_放行() {
            // 对账、归档确实要用它。作用范围由子查询决定、可能是几百万行，
            // 但那一层同样交给影响行数上限，而不是在这里一刀切禁掉。
            var v = guard.check("INSERT INTO orders_archive (id, amount) "
                    + "SELECT id, amount FROM orders WHERE created_at < '2024-01-01'");
            assertEquals("INSERT", v.operation());
        }

        @Test
        void INSERT_ON_DUPLICATE_KEY_UPDATE_放行() {
            // 它仍然是一条 Insert，作用范围受 VALUES 约束，与 REPLACE 有本质区别（见下）。
            assertEquals("INSERT",
                    guard.check("INSERT INTO stat(d, n) VALUES('2026-01-01', 1) "
                            + "ON DUPLICATE KEY UPDATE n = n + 1").operation());
        }

        @Test
        void SELECT_被拒() {
            // 这是写通道，查询该走 conn_query。放行 SELECT 等于给只读护栏开了一条旁路。
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("SELECT * FROM orders").getCode());
        }
    }

    @Nested
    @DisplayName("DDL 与伪装成写的语句")
    class DdlAndLookalikes {

        @Test
        void DROP_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("DROP TABLE orders").getCode());
        }

        @Test
        void TRUNCATE_被拒() {
            // TRUNCATE 没有 WHERE 可带，效果等同「DELETE 全表」还不可回滚——
            // 恰恰是上面那条规则要防的事，必须单独挡住，不能因为它不是 Delete 类型就漏过去。
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("TRUNCATE TABLE orders").getCode());
        }

        @Test
        void ALTER_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("ALTER TABLE orders ADD COLUMN memo VARCHAR(64)").getCode());
        }

        @Test
        void CREATE_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("CREATE TABLE tmp_x (id INT)").getCode());
        }

        /**
         * ★ REPLACE 是这一组里最容易被放过的一条。
         *
         * <p>它长得像 INSERT、名字里也没有 DELETE，实际语义是<b>先删掉同键的行再插入</b>。
         * 一条 {@code REPLACE INTO orders} 会把那一行除指定列之外的字段全部重置成默认值，
         * 而调用方以为自己只是「插入或更新」。判据是解析出来的类型（jsqlparser 归为 Upsert），
         * 不是关键字长得像不像 INSERT。
         */
        @Test
        void REPLACE_INTO_被拒() {
            ConnectorException e = reject("REPLACE INTO orders (id, status) VALUES (1, 'PAID')");
            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
        }

        @Test
        void CALL_存储过程_被拒() {
            // 存储过程里干了什么平台完全看不见，护栏的任何一条都管不到它。
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("CALL settle_orders(1)").getCode());
        }

        @Test
        void SET_会话变量_被拒() {
            // SET autocommit = 0 会把框架的事务控制掀翻：影响行数超限时那条 rollback 就不再兜底。
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("SET autocommit = 0").getCode());
        }
    }

    // ================================================================ 单条语句

    @Nested
    @DisplayName("单条语句")
    class SingleStatement {

        /**
         * 写通道的堆叠语句比查询通道危险得多：前一条合法 UPDATE 会正常返回影响行数，
         * 调用方看到的是一次成功，而后面那条 DROP 同样执行了。
         */
        @Test
        void 堆叠语句被拒() {
            ConnectorException e = reject("UPDATE t SET a = 1 WHERE id = 1; DROP TABLE t");
            assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
        }

        @Test
        void 尾随分号的单条语句放行() {
            // 尾随分号是极常见的写法，误杀它会让一堆正常语句报一个莫名其妙的错。
            assertNotNull(guard.check("UPDATE orders SET status = 1 WHERE id = 2;"));
        }
    }

    // ================================================================ 危险函数与写文件

    @Nested
    @DisplayName("危险函数")
    class DangerousFunctions {

        @Test
        void SLEEP_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("UPDATE orders SET memo = CONCAT(memo, SLEEP(5)) WHERE id = 1").getCode());
        }

        @Test
        void LOAD_FILE_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("UPDATE orders SET memo = LOAD_FILE('/etc/passwd') WHERE id = 1").getCode());
        }

        @Test
        void 大小写混写也被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("UPDATE orders SET memo = SlEeP(1) WHERE id = 1").getCode());
        }

        @Test
        void 列名含sleep字样的合法语句放行() {
            // 词边界匹配的意义：sleep_minutes 是个正常列名，被误杀的话客户的传感器表就写不了。
            assertNotNull(guard.check("UPDATE sensor_data SET sleep_minutes = 5 WHERE id = 1"));
        }
    }

    @Nested
    @DisplayName("写文件到数据库服务器")
    class IntoFile {

        @Test
        void INTO_OUTFILE_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("SELECT * FROM orders INTO OUTFILE '/tmp/x.csv'").getCode());
        }

        @Test
        void INTO_DUMPFILE_被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("SELECT memo FROM orders INTO DUMPFILE '/tmp/x.bin'").getCode());
        }

        @Test
        void 列名含outfile字样的合法语句放行() {
            // 正则要求 into 与 outfile 相邻，单独出现的列名不该被拖下水。
            assertNotNull(guard.check("UPDATE export_task SET outfile_path = '/data/a.csv' WHERE id = 1"));
        }

        @Test
        void 表名以outfile开头的INSERT放行() {
            // 这条专门验词边界：INTO outfile_task 里 "into" 和 "outfile" 确实相邻，
            // 少了词边界就会把一张正常的导出任务表整个封掉。
            assertNotNull(guard.check("INSERT INTO outfile_task(path) VALUES('/data/a.csv')"));
        }
    }

    // ================================================================ fail-closed

    @Nested
    @DisplayName("解析失败 fail-closed")
    class ParseFailure {

        @Test
        void 解析不了的语句被拒() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, reject("UPDAAATE orders SEEET x = 1").getCode());
        }

        @Test
        void 错误文案不回显原始SQL() {
            // 这个异常会被工具层转成返回值回灌模型，并完整落进 ai_model_call_content。
            // 解析失败的语句往往正是模型拼错的那一条，里面带着客户的真实表名。
            ConnectorException e = reject("UPDAAATE secret_salary_table SEEET x = 1");
            assertFalse(e.getMessage().contains("secret_salary_table"));
            assertFalse(String.valueOf(e.getSafeDetail()).contains("secret_salary_table"));
        }

        @Test
        void 空语句被拒() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR,
                    assertThrows(ConnectorException.class, () -> guard.check("   ")).getCode());
        }

        @Test
        void null语句被拒() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR,
                    assertThrows(ConnectorException.class, () -> guard.check(null)).getCode());
        }
    }

    // ================================================================ Verdict 内容

    /**
     * operation 与 targetTable 不是装饰：审批界面靠它们让人一眼看出「要改哪张表、做什么操作」。
     * 解析错了，人看到的就是一条与实际不符的审批单，而他还是会点确认。
     */
    @Nested
    @DisplayName("Verdict 的操作类型与目标表")
    class VerdictContent {

        @Test
        void UPDATE的目标表() {
            var v = guard.check("UPDATE orders SET status = 1 WHERE id = 2");
            assertEquals("UPDATE", v.operation());
            assertEquals("orders", v.targetTable());
        }

        @Test
        void DELETE的目标表() {
            var v = guard.check("DELETE FROM order_items WHERE order_id = 2");
            assertEquals("DELETE", v.operation());
            assertEquals("order_items", v.targetTable());
        }

        @Test
        void 带别名时取的是表名而不是别名() {
            var v = guard.check("UPDATE orders o SET o.status = 1 WHERE o.id = 2");
            assertEquals("orders", v.targetTable());
        }

        @Test
        void effectiveSql是平台真正要执行的那一条() {
            // 当前不改写，但审计与审批展示的都是这个字段——它必须始终等于真正发给数据库的语句，
            // 将来一旦加上改写（比如补 LIMIT），这条断言会提醒改的人同步更新审批展示。
            var v = guard.check("update orders set status = 1 where id = 2");
            assertNotNull(v.effectiveSql());
            assertTrue(v.effectiveSql().toUpperCase().startsWith("UPDATE"));
        }
    }
}

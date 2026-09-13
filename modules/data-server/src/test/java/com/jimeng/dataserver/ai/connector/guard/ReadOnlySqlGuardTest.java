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
 * SQL 只读护栏。
 *
 * <p>每一条「该拒的」都配一条「长得像但合法的」——只测拒绝不测放行，很容易写出一个
 * 把所有查询都拒掉的护栏，那在测试里是全绿的。
 */
class ReadOnlySqlGuardTest {

    private final ReadOnlySqlGuard guard = new ReadOnlySqlGuard();

    private ConnectorException reject(String sql) {
        return assertThrows(ConnectorException.class, () -> guard.check(sql, 1000));
    }

    @Nested
    @DisplayName("单条语句")
    class SingleStatement {

        @Test
        void 堆叠语句被拒() {
            ConnectorException e = reject("SELECT 1; DROP TABLE users");
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, e.getCode());
        }

        @Test
        void 语句里带分号但只有一条_放行() {
            // 尾随分号是很常见的写法，不该被当成两条。
            assertNotNull(guard.check("SELECT id FROM orders;", 1000));
        }
    }

    @Nested
    @DisplayName("只允许 SELECT")
    class OnlySelect {

        @Test
        void UPDATE_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("UPDATE orders SET status = 1").getCode());
        }

        @Test
        void DELETE_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("DELETE FROM orders").getCode());
        }

        @Test
        void DROP_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("DROP TABLE orders").getCode());
        }

        @Test
        void INSERT_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("INSERT INTO orders(id) VALUES (1)").getCode());
        }

        @Test
        void WITH_开头的_CTE_查询放行() {
            // 判据是解析出来的类型是 Select，不是「首个关键字是 SELECT」。
            var v = guard.check("WITH t AS (SELECT id FROM orders) SELECT * FROM t", 1000);
            assertNotNull(v.effectiveSql());
        }

        @Test
        void 列名里含_update_字样的查询放行() {
            assertNotNull(guard.check("SELECT update_time FROM orders", 1000));
        }
    }

    @Nested
    @DisplayName("写副作用")
    class SideEffects {

        @Test
        void INTO_OUTFILE_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED,
                    reject("SELECT * FROM orders INTO OUTFILE '/tmp/x.csv'").getCode());
        }

        @Test
        void INTO_变量_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED,
                    reject("SELECT COUNT(*) INTO @c FROM orders").getCode());
        }

        @Test
        void 普通列名含_into_字样的查询放行() {
            assertNotNull(guard.check("SELECT intro FROM products", 1000));
        }
    }

    @Nested
    @DisplayName("危险函数")
    class DangerousFunctions {

        @Test
        void SLEEP_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("SELECT SLEEP(10)").getCode());
        }

        @Test
        void LOAD_FILE_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED,
                    reject("SELECT LOAD_FILE('/etc/passwd')").getCode());
        }

        @Test
        void BENCHMARK_被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED,
                    reject("SELECT BENCHMARK(1000000, MD5('x'))").getCode());
        }

        @Test
        void 大小写混写也被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED, reject("SELECT SlEeP(1)").getCode());
        }

        @Test
        void 名字里含_sleep_的业务列放行() {
            // 词边界匹配的意义：sleep_minutes 是个合法列名，不该被误杀。
            assertNotNull(guard.check("SELECT sleep_minutes FROM sensor_data", 1000));
        }
    }

    @Nested
    @DisplayName("解析失败 fail-closed")
    class ParseFailure {

        @Test
        void 解析不了的语句被拒() {
            ConnectorException e = reject("SELEKT * FRM orders");
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        @Test
        void 错误文案不回显原始SQL() {
            // 异常会流到模型上下文并落进 ai_model_call_content，不该在那里多存一份 SQL 副本。
            ConnectorException e = reject("SELEKT * FRM secret_table_name");
            assertFalse(e.getMessage().contains("secret_table_name"));
            assertFalse(String.valueOf(e.getSafeDetail()).contains("secret_table_name"));
        }
    }

    @Nested
    @DisplayName("笛卡尔积")
    class CartesianProduct {

        @Test
        void 无ON无WHERE的多表连接被拒() {
            assertEquals(ConnectorErrorCode.GUARD_BLOCKED,
                    reject("SELECT * FROM a, b").getCode());
        }

        @Test
        void 关联条件写在WHERE里的老写法放行() {
            // 这是极常见的写法，误杀它会让一堆合法查询报一个莫名其妙的错。
            assertNotNull(guard.check("SELECT * FROM a, b WHERE a.id = b.a_id", 1000));
        }

        @Test
        void 有ON条件的JOIN放行() {
            assertNotNull(guard.check("SELECT * FROM a JOIN b ON a.id = b.a_id", 1000));
        }

        @Test
        void 单表无WHERE放行() {
            assertNotNull(guard.check("SELECT * FROM orders", 1000));
        }
    }

    @Nested
    @DisplayName("强制 LIMIT")
    class LimitEnforcement {

        @Test
        void 没有LIMIT时注入() {
            var v = guard.check("SELECT id FROM orders", 100);
            assertTrue(v.limitInjected());
            assertEquals(100, v.effectiveLimit());
            assertTrue(v.effectiveSql().toUpperCase().contains("LIMIT 100"));
        }

        @Test
        void LIMIT超上限时收紧() {
            var v = guard.check("SELECT id FROM orders LIMIT 999999", 100);
            assertTrue(v.limitInjected());
            assertTrue(v.effectiveSql().toUpperCase().contains("LIMIT 100"));
            assertFalse(v.effectiveSql().contains("999999"));
        }

        @Test
        void LIMIT已小于上限时不改写() {
            var v = guard.check("SELECT id FROM orders LIMIT 5", 100);
            assertFalse(v.limitInjected());
            assertTrue(v.effectiveSql().toUpperCase().contains("LIMIT 5"));
        }

        @Test
        void 收紧LIMIT时保留OFFSET() {
            // 抹掉 offset 会让模型翻页时永远拿到第一页——一个不报错的逻辑错误。
            var v = guard.check("SELECT id FROM orders LIMIT 999999 OFFSET 500", 100);
            assertTrue(v.effectiveSql().contains("500"));
        }

        @Test
        void UNION也被加上LIMIT() {
            var v = guard.check("SELECT id FROM a UNION SELECT id FROM b", 50);
            assertTrue(v.limitInjected());
            assertTrue(v.effectiveSql().toUpperCase().contains("LIMIT 50"));
        }
    }

    @Test
    void 空语句被拒() {
        assertEquals(ConnectorErrorCode.CONFIG_ERROR,
                assertThrows(ConnectorException.class, () -> guard.check("   ", 1000)).getCode());
    }
}

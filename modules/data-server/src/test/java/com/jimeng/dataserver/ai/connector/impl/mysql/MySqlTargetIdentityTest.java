package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MySqlConnector#targetIdentity}：判断「这个库是不是已经接过了」。
 *
 * <p>它只服务于一句提醒，但两条归一化规则都是刻意的，错了会让提醒出现在错误的时候：
 * <b>不含用户名</b>（否则最该提醒的场景反而不提醒），<b>库名不归一大小写</b>（否则会误报）。
 */
class MySqlTargetIdentityTest {

    private final MySqlConnector connector = new MySqlConnector(null, null, null, null);

    private static ConnectorInstance inst(Map<String, Object> params) {
        return new ConnectorInstance(1L, "t1", "MYSQL", "c1", "C1", params, "pwd", "direct");
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("host + port + database 唯一确定一个目标")
    void 基本形状() {
        assertEquals("mysql://db.example.com:3306/erp",
                connector.targetIdentity(inst(params("host", "db.example.com", "port", 3306, "database", "erp"))));
    }

    @Test
    @DisplayName("port 缺省按 3306")
    void 端口缺省() {
        assertEquals("mysql://db.example.com:3306/erp",
                connector.targetIdentity(inst(params("host", "db.example.com", "database", "erp"))));
    }

    @Test
    @DisplayName("★ 不含用户名：一条只读、一条可写连同一个库，正是最该提醒的那一种")
    void 不含用户名() {
        String readonly = connector.targetIdentity(
                inst(params("host", "db.example.com", "database", "erp", "username", "ro_user")));
        String writable = connector.targetIdentity(
                inst(params("host", "db.example.com", "database", "erp", "username", "rw_user")));

        assertEquals(readonly, writable);
    }

    @Test
    @DisplayName("主机名按 DNS 规则不区分大小写，可以归一")
    void 主机名归一() {
        assertEquals(
                connector.targetIdentity(inst(params("host", "db.example.com", "database", "erp"))),
                connector.targetIdentity(inst(params("host", "DB.Example.COM", "database", "erp"))));
    }

    @Test
    @DisplayName("★ 库名不归一大小写：MySQL 在 Linux 上区分，归一会把两个库判成一个——误提示比漏提示糟")
    void 库名不归一() {
        assertNotEquals(
                connector.targetIdentity(inst(params("host", "db.example.com", "database", "erp"))),
                connector.targetIdentity(inst(params("host", "db.example.com", "database", "ERP"))));
    }

    @Test
    @DisplayName("host 或 database 缺失时返回 null：宁可不提醒，也不能拿半个标识去比")
    void 参数不全返回null() {
        assertNull(connector.targetIdentity(inst(params("database", "erp"))));
        assertNull(connector.targetIdentity(inst(params("host", "db.example.com"))));
        assertNull(connector.targetIdentity(inst(params("host", "  ", "database", "erp"))));
    }
}

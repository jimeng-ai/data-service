package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.impl.mysql.MySqlConnector;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.GrantRequest;
import com.jimeng.dataserver.ai.connector.spi.GrantScript;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.ParamSpec;
import com.jimeng.dataserver.ai.connector.spi.ParamType;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 授权脚本生成。
 *
 * <p>不连库不起 Spring：生成脚本本来就是纯函数（表单几个字段 → 一段文本），
 * 而它最需要被钉住的那条性质——<b>用户输入不可能变成可执行的语句</b>——恰好只靠纯逻辑就能验。
 *
 * <p>用真的 {@link MySqlConnector} + 真的 {@link ConnectorRegistry}，只把数据源管理器 mock 掉：
 * 这里要验的是方言拼装与校验，换成 mock 连接器就等于把被测对象换成了测试替身。
 */
class GrantScriptServiceTest {

    /** 一个声明了能力、但不提供授权脚本的类型（现实里就是 HTTP：授权发生在对方系统里，没有 SQL 可给）。 */
    private static final Connector NO_SCRIPT_CONNECTOR = new Connector() {
        @Override
        public String kind() {
            return "HTTP";
        }

        @Override
        public String displayName() {
            return "HTTP 接口";
        }

        @Override
        public ParamSpec paramSpec() {
            return ParamSpec.of(ParamField.of("baseUrl", "地址", ParamType.STRING, true, "接口根地址"));
        }

        @Override
        public Set<Capability> declaredCapabilities() {
            return Set.of(Capability.INVOKE);
        }

        @Override
        public ConnectorSession open(ConnectorInstance instance) {
            throw new UnsupportedOperationException("测试桩不建连接");
        }
    };

    private final GrantScriptService service = new GrantScriptService(new ConnectorRegistry(List.of(
            new MySqlConnector(mock(CustomerDataSourceManager.class), new ReadOnlySqlGuard(),
                    new WriteSqlGuard(), new ConnectorProperties()),
            NO_SCRIPT_CONNECTOR)));

    private static GrantRequest req(String db, String user, String host,
                                    List<String> tables, WritePolicy policy) {
        return new GrantRequest(db, user, host, tables, policy);
    }

    private static GrantRequest readonlyWholeDb() {
        return req("shop", "jm_readonly", "%", List.of(), WritePolicy.FORBIDDEN);
    }

    private static String sqlOf(GrantScript s) {
        return s.getSql();
    }

    private static long grantLines(String sql) {
        return sql.lines().filter(l -> l.startsWith("GRANT ")).count();
    }

    // ================================================================ 权限随写策略变化

    @Nested
    @DisplayName("授哪些权限由写策略决定")
    class Privileges {

        @Test
        @DisplayName("只读策略只授 SELECT")
        void readonlyGrantsSelectOnly() {
            String sql = sqlOf(service.generate("MYSQL", readonlyWholeDb()));

            assertTrue(sql.contains("GRANT SELECT ON `shop`.* TO 'jm_readonly'@'%';"), sql);
            // 这三个词一个都不该出现：只读连接多授一个 DELETE，平台侧的闸再严也兜不住。
            assertFalse(sql.contains("INSERT"), sql);
            assertFalse(sql.contains("UPDATE"), sql);
            assertFalse(sql.contains("DELETE"), sql);
        }

        @Test
        @DisplayName("写需审批授全套 DML —— 审批是平台侧的事，数据库侧表达不了")
        void requireApprovalGrantsDml() {
            String sql = sqlOf(service.generate("MYSQL",
                    req("shop", "jm_rw", "%", List.of(), WritePolicy.REQUIRE_APPROVAL)));

            assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON `shop`.* TO 'jm_rw'@'%';"), sql);
        }

        @Test
        @DisplayName("写自动与写需审批在数据库侧授的权限相同")
        void autoGrantsSameAsApproval() {
            String auto = sqlOf(service.generate("MYSQL",
                    req("shop", "jm_rw", "%", List.of(), WritePolicy.AUTO)));
            String approval = sqlOf(service.generate("MYSQL",
                    req("shop", "jm_rw", "%", List.of(), WritePolicy.REQUIRE_APPROVAL)));

            assertEquals(grantLines(auto), grantLines(approval));
            assertTrue(auto.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON `shop`.*"), auto);
        }

        @Test
        @DisplayName("写策略为 null 回落只读 —— 「授权的默认值只能是否」")
        void nullPolicyFallsBackToReadonly() {
            String sql = sqlOf(service.generate("MYSQL",
                    req("shop", "jm_readonly", "%", List.of(), null)));

            assertTrue(sql.contains("GRANT SELECT ON `shop`.*"), sql);
            assertFalse(sql.contains("DELETE"), sql);
        }
    }

    // ================================================================ 整库 vs 逐表

    @Nested
    @DisplayName("授权范围")
    class Scope {

        @Test
        @DisplayName("不指定表就整库授权")
        void wholeDatabase() {
            String sql = sqlOf(service.generate("MYSQL", readonlyWholeDb()));

            assertEquals(1, grantLines(sql));
            assertTrue(sql.contains("ON `shop`.*"), sql);
        }

        @Test
        @DisplayName("指定表就逐表授权，一张表一行")
        void perTable() {
            String sql = sqlOf(service.generate("MYSQL", req("shop", "jm_readonly", "%",
                    List.of("orders", "order_item", "customer"), WritePolicy.FORBIDDEN)));

            assertEquals(3, grantLines(sql));
            assertTrue(sql.contains("ON `shop`.`orders` TO 'jm_readonly'@'%';"), sql);
            assertTrue(sql.contains("ON `shop`.`order_item` TO 'jm_readonly'@'%';"), sql);
            assertTrue(sql.contains("ON `shop`.`customer` TO 'jm_readonly'@'%';"), sql);
            // 逐表授权时绝不能再顺手给一条整库的——那等于前面几行白挑了。
            assertFalse(sql.contains("`shop`.*"), sql);
        }

        @Test
        @DisplayName("建号语句只有一条，不会每张表重复一次")
        void createUserOnce() {
            String sql = sqlOf(service.generate("MYSQL", req("shop", "jm_readonly", "%",
                    List.of("orders", "order_item"), WritePolicy.FORBIDDEN)));

            assertEquals(1, sql.lines().filter(l -> l.startsWith("CREATE USER ")).count(), sql);
        }
    }

    // ================================================================ 注入

    @Nested
    @DisplayName("标识符校验：用户输入不可能变成可执行语句")
    class Injection {

        /**
         * ★ 这组用例是本类的核心。脚本是<b>平台给的</b>，客户 IT 会往 root 会话里整段粘贴——
         * 一个带分号或反引号的「库名」到那时执行的就不是我们生成的语句了。
         */
        @ParameterizedTest(name = "库名 [{0}] 必须被拒")
        @ValueSource(strings = {
                "shop; DROP DATABASE mysql",   // 分号：追加一条语句
                "shop`; DROP DATABASE mysql; -- ", // 反引号：先闭合我们的引用再追加
                "shop'",                        // 单引号
                "shop\"",                       // 双引号
                "shop x",                       // 空格
                "shop/*x*/",                    // 块注释
                "shop\\",                       // 反斜杠：转义歧义
                "shop.orders",                  // 点号：越到另一个对象上
                "shop%",                        // 通配：host 段才允许，库名不允许
                "商城",                          // 非 ASCII：MySQL 允许，我们的白名单不允许，宁可拒
                "shop\nGRANT ALL ON *.* TO 'x'@'%'" // 换行：另起一行伪装成脚本的一部分
        })
        void illegalDatabaseRejected(String db) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req(db, "jm_readonly", "%", List.of(), WritePolicy.FORBIDDEN)));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("库名"), e.getRespMsg());
            assertTrue(e.getRespMsg().contains("非法字符"), e.getRespMsg());
        }

        @ParameterizedTest(name = "表名 [{0}] 必须被拒")
        @ValueSource(strings = {"orders; DROP TABLE x", "orders`", "orders'", "orders x", "orders*", "订单"})
        void illegalTableRejected(String table) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req("shop", "jm_readonly", "%",
                            List.of("orders", table), WritePolicy.FORBIDDEN)));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("表名"), e.getRespMsg());
        }

        @ParameterizedTest(name = "账号名 [{0}] 必须被拒")
        @ValueSource(strings = {
                "jm'@'%'; DROP DATABASE mysql; -- ", // 闭合单引号：这条最像真实攻击
                "jm_readonly'",
                "jm`readonly",
                "jm readonly",
                "jm;readonly",
                "只读账号"
        })
        void illegalUsernameRejected(String user) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req("shop", user, "%", List.of(), WritePolicy.FORBIDDEN)));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("账号名"), e.getRespMsg());
        }

        @ParameterizedTest(name = "登录地址 [{0}] 必须被拒")
        @ValueSource(strings = {"1.2.3.4'; DROP DATABASE mysql; -- ", "1.2.3.4 5", "db`host", "1.2.3.4\"", "本机"})
        void illegalHostRejected(String host) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req("shop", "jm_readonly", host, List.of(), WritePolicy.FORBIDDEN)));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("登录地址"), e.getRespMsg());
        }

        @Test
        @DisplayName("短横线是合法标识符字符，不能因为它长得像行注释就拒")
        void hyphenIsLegalAndSafelyQuoted() {
            // 白名单允许短横线（客户库里 shop-2024 这类名字很常见），所以 shop-- 也是合法输入。
            // 它安全的理由不是「被过滤掉了」，而是【引用位置】：反引号和单引号内部 -- 不构成注释。
            String sql = sqlOf(service.generate("MYSQL",
                    req("shop--", "jm-readonly", "%", List.of(), WritePolicy.FORBIDDEN)));

            assertTrue(sql.contains("GRANT SELECT ON `shop--`.* TO 'jm-readonly'@'%';"), sql);
            // 语句仍然是完整的一行：分号没有被注释掉。
            assertTrue(sql.lines().filter(l -> l.startsWith("GRANT ")).allMatch(l -> l.endsWith(";")), sql);
        }

        @Test
        @DisplayName("合法的 host 形态照常放行：通配、具体 IP、网段前缀、域名")
        void legalHostAccepted() {
            for (String host : List.of("%", "203.0.113.10", "192.168.%", "db.example.com")) {
                String sql = sqlOf(service.generate("MYSQL",
                        req("shop", "jm_readonly", host, List.of(), WritePolicy.FORBIDDEN)));
                assertTrue(sql.contains("'jm_readonly'@'" + host + "'"), host + " => " + sql);
            }
        }

        @Test
        @DisplayName("超过 MySQL 8 的 32 字符账号名上限直接拒，而不是生成一段建不出来的脚本")
        void tooLongUsernameRejected() {
            String user = "u".repeat(33);

            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req("shop", user, "%", List.of(), WritePolicy.FORBIDDEN)));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
        }

        @Test
        @DisplayName("库名为空是「不能为空」，不是「非法字符」")
        void blankDatabaseRejected() {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("MYSQL", req("  ", "jm_readonly", "%", List.of(), WritePolicy.FORBIDDEN)));

            assertTrue(e.getRespMsg().contains("不能为空"), e.getRespMsg());
        }
    }

    // ================================================================ 密码

    @Nested
    @DisplayName("密码只能是占位符")
    class Password {

        @Test
        @DisplayName("脚本里只有占位符，且占位符一眼就知道要替换")
        void placeholderOnly() {
            String sql = sqlOf(service.generate("MYSQL", readonlyWholeDb()));

            assertTrue(sql.contains("IDENTIFIED BY '请替换成一个强密码';"), sql);
            // 只允许出现一次 IDENTIFIED BY：多一处就多一个能塞进真密码的位置。
            assertEquals(1, sql.split("IDENTIFIED BY", -1).length - 1, sql);
        }

        @Test
        @DisplayName("提醒里明说要自己换、且不要复用")
        void noteTellsToReplace() {
            List<String> notes = service.generate("MYSQL", readonlyWholeDb()).getNotes();

            assertTrue(notes.stream().anyMatch(n -> n.contains("请替换成一个强密码")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("不要与其它系统复用")), notes.toString());
        }
    }

    // ================================================================ 提醒

    @Nested
    @DisplayName("提醒：脚本表达不了的那部分")
    class Notes {

        @Test
        @DisplayName("只读整库：至少给出 % 的含义、别授 *.* 、版本差异")
        void readonlyNotes() {
            List<String> notes = service.generate("MYSQL", readonlyWholeDb()).getNotes();

            assertTrue(notes.size() >= 4, notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("@'%'")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("*.*")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("8.0")), notes.toString());
            assertTrue(notes.stream().noneMatch(String::isBlank), notes.toString());
        }

        @Test
        @DisplayName("收紧了 host 就不再提示 %，改成提示出口 IP 变更会连不上")
        void tightenedHostNotes() {
            List<String> notes = service.generate("MYSQL",
                    req("shop", "jm_readonly", "203.0.113.10", List.of(), WritePolicy.FORBIDDEN)).getNotes();

            assertTrue(notes.stream().noneMatch(n -> n.contains("@'%'")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("203.0.113.10")), notes.toString());
        }

        @Test
        @DisplayName("逐表授权要提醒「漏授的表在平台侧长得像不存在」")
        void perTableNote() {
            List<String> notes = service.generate("MYSQL", req("shop", "jm_readonly", "%",
                    List.of("orders", "order_item"), WritePolicy.FORBIDDEN)).getNotes();

            assertTrue(notes.stream().anyMatch(n -> n.contains("不存在")), notes.toString());
        }

        @Test
        @DisplayName("可写时额外提醒：平台如实标注写策略，且写操作仍受行数上限与审批约束")
        void writableNotes() {
            List<String> notes = service.generate("MYSQL",
                    req("shop", "jm_rw", "%", List.of(), WritePolicy.REQUIRE_APPROVAL)).getNotes();

            assertTrue(notes.stream().anyMatch(n -> n.contains(WritePolicy.REQUIRE_APPROVAL.label())), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("影响行数")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("确认")), notes.toString());
        }

        @Test
        @DisplayName("只读时不出现「这是一个能写的账号」这类会误导人的提醒")
        void readonlyHasNoWriteNote() {
            List<String> notes = service.generate("MYSQL", readonlyWholeDb()).getNotes();

            assertTrue(notes.stream().noneMatch(n -> n.contains("能写的账号")), notes.toString());
        }
    }

    // ================================================================ 类型分派

    @Nested
    @DisplayName("类型分派")
    class Dispatch {

        @Test
        @DisplayName("kind 大小写与首尾空格由注册表归一")
        void kindNormalized() {
            assertTrue(sqlOf(service.generate("mysql", readonlyWholeDb())).contains("CREATE USER"));
            assertTrue(sqlOf(service.generate("  MySQL  ", readonlyWholeDb())).contains("CREATE USER"));
        }

        @Test
        @DisplayName("没有的类型报「不支持此操作」，并把类型名说清楚")
        void unknownKind() {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("ORACLE", readonlyWholeDb()));

            assertEquals(ExceptionCode.OPERATION_UNSUPPORTED.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("ORACLE"), e.getRespMsg());
        }

        @Test
        @DisplayName("类型存在但不提供脚本（default 返回 null）也是「不支持此操作」，不是失败")
        void connectorWithoutScript() {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> service.generate("HTTP", readonlyWholeDb()));

            assertEquals(ExceptionCode.OPERATION_UNSUPPORTED.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains(NO_SCRIPT_CONNECTOR.displayName()), e.getRespMsg());
        }
    }
}

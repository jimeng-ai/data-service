package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * P4/S1 既有 SQL 语料。
 *
 * <p>本地没有 MySQL，所以全部离线：解析那一半直接喂 SQL 字符串（它是纯函数），
 * 取数那一半把网关 mock 成「真的把 op 跑一遍」，会话里塞一个假的 {@link QueryCapable}。
 *
 * <p><b>这组测试比大多数组更重要一点</b>：这个阶段从未在真实客户库上跑过，
 * 所以它唯一的正确性证据就是这里钉住的那几条形状——尤其
 * {@link RealMySqlShapes} 那一组，那是真库回给我们的样子，不是人手写的漂亮 SQL。
 */
class SemanticSqlCorpusReaderTest {

    private final ConnectorGateway gateway = mock(ConnectorGateway.class);
    private final SemanticSqlCorpusReader reader = new SemanticSqlCorpusReader(gateway);

    private static SemanticSqlCorpusReader.CorpusEntry view(String name, String sql) {
        return new SemanticSqlCorpusReader.CorpusEntry(SemanticSqlCorpusReader.SRC_VIEW, "shop", name, sql);
    }

    private static SemanticSqlCorpusReader.CorpusEntry routine(String name, String sql) {
        return new SemanticSqlCorpusReader.CorpusEntry(SemanticSqlCorpusReader.SRC_ROUTINE, "shop", name, sql);
    }

    /** 「左表.左列 = 右表.右列」的紧凑写法，断言用。 */
    private static String pair(SemanticSqlCorpusReader.CorpusJoin j) {
        return j.getLeftObject() + "." + j.getLeftColumn() + "=" + j.getRightObject() + "." + j.getRightColumn();
    }

    private static List<String> pairs(SemanticSqlCorpusReader.CorpusResult r) {
        List<String> out = new ArrayList<>();
        for (SemanticSqlCorpusReader.CorpusJoin j : r.getJoins()) {
            out.add(pair(j));
        }
        return out;
    }

    // ================================================================

    /**
     * ★ 本文件最要紧的一组。
     *
     * <p>{@code information_schema.VIEWS.VIEW_DEFINITION} 里存的<b>不是客户写的原文</b>，
     * 是 MySQL 规范化之后的形式。手写一条漂亮的 {@code SELECT ... JOIN ... ON ...} 去测，
     * 会让所有实现都通过，然后接上真库一条都挖不出来——而且不报错。
     */
    @Nested
    @DisplayName("真库回给我们的形状（不是手写的漂亮 SQL）")
    class RealMySqlShapes {

        /**
         * ★ 整个阶段成立与否就压在这一条上。
         *
         * <p>MySQL 把 FROM 整块裹进括号，jsqlparser 于是把它解析成 {@code SubJoin}，
         * 而 {@code PlainSelect.getJoins()} 是 <b>null</b>。只看 getJoins() 的实现
         * 在别的用例里全绿，在这里一条都挖不出来。
         */
        @Test
        @DisplayName("FROM 被括号裹住（SubJoin）时照样挖得到 —— 只看 getJoins() 会 0 产出")
        void 括号包住的FROM() {
            String def = "select `shop`.`o`.`id` AS `id`,`shop`.`u`.`name` AS `name` "
                    + "from (`shop`.`orders` `o` join `shop`.`users` `u` on((`o`.`user_id` = `u`.`id`))) "
                    + "where (`o`.`status` = 1)";

            var r = reader.extract(List.of(view("v_order_user", def)));

            assertEquals(List.of("orders.user_id=users.id"), pairs(r));
            assertEquals(1, r.getViewsParsed());
        }

        /** 没写别名时 MySQL 会把列限定成三段 {@code `库`.`表`.`列`}，别名表得按表名认出来。 */
        @Test
        @DisplayName("没有别名时列限定符是「库.表.列」，也要能解析到真表")
        void 三段式列限定符() {
            String def = "select `shop`.`orders`.`id` AS `id` from "
                    + "(`shop`.`orders` join `shop`.`users` on((`shop`.`orders`.`user_id` = `shop`.`users`.`id`)))";

            var r = reader.extract(List.of(view("v_a", def)));

            assertEquals(List.of("orders.user_id=users.id"), pairs(r));
        }

        /** 三张表时 SubJoin 是左深嵌套的，递归下去才拿得到里层那条。 */
        @Test
        @DisplayName("三表视图：嵌套的 SubJoin 两条都要挖到")
        void 三表嵌套() {
            String def = "select 1 from ((`shop`.`orders` `a` join `shop`.`users` `b` on((`a`.`uid` = `b`.`id`))) "
                    + "join `shop`.`items` `c` on((`c`.`oid` = `a`.`id`)))";

            var r = reader.extract(List.of(view("v_b", def)));

            assertEquals(2, r.getJoins().size());
            assertTrue(pairs(r).contains("orders.uid=users.id"));
            assertTrue(pairs(r).contains("items.oid=orders.id"));
        }

        /**
         * 老库里 {@code FROM a, b WHERE a.id = b.aid} 极常见，MySQL 在视图定义里<b>原样保留</b>它。
         * 只挖 ON 会让这一整类库颗粒无收——而它们恰恰是外键最少、最需要这个阶段的那一类。
         */
        @Test
        @DisplayName("逗号连接 + WHERE 里的关联条件也要挖")
        void 老式逗号连接() {
            String def = "select `a`.`x` AS `x` from `shop`.`orders` `a`,`shop`.`users` `b` "
                    + "where ((`a`.`uid` = `b`.`id`) and (`a`.`status` = 1))";

            var r = reader.extract(List.of(view("v_old", def)));

            assertEquals(List.of("orders.uid=users.id"), pairs(r));
        }

        /** {@code SHOW CREATE VIEW} 给的是完整的 CREATE VIEW，两种入口都要接。 */
        @Test
        @DisplayName("完整的 CREATE VIEW 语句也认")
        void 认CreateView() {
            var r = reader.extract(List.of(view("v_c",
                    "CREATE VIEW v_c AS SELECT o.id FROM orders o JOIN users u ON o.user_id = u.id")));

            assertEquals(List.of("orders.user_id=users.id"), pairs(r));
        }
    }

    // ================================================================

    /**
     * 窄规则。每一条「不要」都有自己的出错方式，不是笼统的保守。
     */
    @Nested
    @DisplayName("认得窄：宁可少挖，绝不半解析半猜")
    class NarrowParsing {

        /**
         * ★ 这一条最要紧：CTE 的名字在 FROM 里长得和真表一模一样。
         * 不拦的话会挖出一条两端在客户库里根本不存在的「关系」，
         * 还带着「来自视图 xxx 的定义」这个让人更信它的出处。
         */
        @Test
        @DisplayName("带 CTE（WITH）的视图整个跳过 —— 否则会造出指向不存在的表的假关系")
        void 拒绝CTE() {
            var r = reader.extract(List.of(view("v_cte",
                    "WITH t AS (SELECT 1 AS id) SELECT t.id FROM t JOIN users u ON t.id = u.id")));

            assertTrue(r.getJoins().isEmpty(), "CTE 视图不该产出任何关系");
            assertEquals(1, r.getViewsSkippedUnparseable());
            assertEquals(0, r.getViewsParsed());
        }

        @Test
        @DisplayName("UNION 整个跳过 —— 各分支的别名作用域是独立的，混着解析会把 A 的别名接到 B 的表上")
        void 拒绝UNION() {
            var r = reader.extract(List.of(view("v_u",
                    "select a.x from orders a join users b on a.uid = b.id "
                            + "union select c.x from items c join users d on c.uid = d.id")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getViewsSkippedUnparseable());
        }

        @Test
        @DisplayName("FROM 里的子查询：只丢掉引用它的那一条，不丢掉整个视图")
        void 子查询只丢那一条() {
            // a 是派生表进不了别名表；b、c 是真表，它们之间那条要留下来。
            var r = reader.extract(List.of(view("v_sub",
                    "select 1 from (select 1 as x) a join users b on a.x = b.id "
                            + "join items c on c.uid = b.id")));

            assertEquals(List.of("items.uid=users.id"), pairs(r));
            assertEquals(1, r.getDroppedUnresolvable(), "引用派生表的那一条要被计数，不能静默");
        }

        @Test
        @DisplayName("任一端带函数就不要 —— 函数两端的值域关系我们看不懂，不该猜")
        void 函数端不要() {
            var r = reader.extract(List.of(view("v_f",
                    "select 1 from orders a join users b on lower(a.uid) = b.id")));

            assertTrue(r.getJoins().isEmpty());
        }

        /**
         * {@code a.x = b.y OR a.z = b.w} 里两条至多成立一条。两条都收等于凭空多造一条，
         * 而判断哪条是真的要看数据——那是 S3 的事。
         */
        @Test
        @DisplayName("OR 分支整支不进，并计数")
        void 拒绝OR分支() {
            var r = reader.extract(List.of(view("v_or",
                    "select 1 from orders a, users b where a.uid = b.id or a.alt_uid = b.id")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getDroppedOrBranch());
        }

        @Test
        @DisplayName("USING (col) 明确不支持 —— 猜「左边哪张表拥有这个列」在三表时会指错")
        void 拒绝USING() {
            var r = reader.extract(List.of(view("v_using",
                    "select 1 from orders a join users b using (id)")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getDroppedUsing());
        }

        @Test
        @DisplayName("普通过滤条件（a.status = 1）静默略过，不进任何 dropped 计数")
        void 过滤条件不算失败() {
            var r = reader.extract(List.of(view("v_w",
                    "select 1 from orders a, users b where a.uid = b.id and a.status = 1 and b.tenant = 'x'")));

            assertEquals(List.of("orders.uid=users.id"), pairs(r));
            assertEquals(0, r.getDroppedUnresolvable(),
                    "过滤条件是正常的，混进计数器会把真正的信号淹掉");
        }

        @Test
        @DisplayName("没有限定符的列解析不出来（多表时是歧义的），计入 droppedUnresolvable")
        void 无限定列() {
            var r = reader.extract(List.of(view("v_n",
                    "select 1 from orders a, users b where uid = b.id")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getDroppedUnresolvable());
        }

        @Test
        @DisplayName("引用别的 schema 的表要丢掉 —— 那张表不在这条连接的目录里，模型 join 不到")
        void 跨库引用() {
            var r = reader.extract(List.of(view("v_x",
                    "select 1 from `shop`.`orders` `a` join `other`.`users` `b` on `a`.`uid` = `b`.`id`")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getDroppedForeignSchema());
        }

        @Test
        @DisplayName("t.x = t.x 这种退化写法不表达任何关系，丢掉")
        void 退化自等() {
            var r = reader.extract(List.of(view("v_self",
                    "select 1 from orders a, orders b where a.id = b.id")));

            assertTrue(r.getJoins().isEmpty());
        }

        @Test
        @DisplayName("真正的自关联（不同列）要留下")
        void 真自关联() {
            var r = reader.extract(List.of(view("v_tree",
                    "select 1 from dept a join dept b on a.parent_id = b.id")));

            // 规范方向取字典序，dept.id < dept.parent_id，所以左边是 id 那一端——
            // 这个方向【不表示】谁引用谁，第 1 档上判不出来，见 accumulate 的注释。
            assertEquals(List.of("dept.id=dept.parent_id"), pairs(r));
        }

        /** 解析不了的对象必须被计数地跳过，不能把整个阶段带崩。 */
        @Test
        @DisplayName("解析不了的定义跳过并计数，后面的对象照常处理")
        void 解析失败不影响后续() {
            var r = reader.extract(List.of(
                    view("v_bad", "这不是 SQL ((("),
                    view("v_good", "select 1 from orders a join users b on a.uid = b.id")));

            assertEquals(1, r.getViewsSkippedUnparseable());
            assertEquals(1, r.getViewsParsed());
            assertEquals(List.of("orders.uid=users.id"), pairs(r));
        }
    }

    // ================================================================

    @Nested
    @DisplayName("覆盖面与计数：三种「没挖到」必须分得开")
    class Counters {

        /** 空正文 = 没有 SHOW VIEW 权限，不是「这个视图是空的」。两句话对客户的行动完全不同。 */
        @Test
        @DisplayName("定义为空记成「读不到定义」，不是「解析失败」")
        void 权限缺失单独计数() {
            var r = reader.extract(List.of(view("v_denied", ""), view("v_null", null)));

            assertEquals(2, r.getViewDefinitionsDenied());
            assertEquals(0, r.getViewsSkippedUnparseable());
        }

        /** 被平台的单值上限截断，和「视图本身太花哨」是两回事，要分开。 */
        @Test
        @DisplayName("被平台截断的正文单独计数，不混进解析失败")
        void 截断单独计数() {
            var r = reader.extract(List.of(view("v_long",
                    "select 1 from orders a join users b on a.uid = b." + SemanticSqlCorpusReader.TRUNCATION_MARK)));

            assertEquals(1, r.getViewsSkippedTruncated());
            assertEquals(0, r.getViewsSkippedUnparseable());
        }

        @Test
        @DisplayName("存储过程正文读不到是常态，单独计数、不算失败")
        void 存储过程读不到正文() {
            var r = reader.extract(List.of(routine("p_sync", null)));

            assertEquals(1, r.getRoutinesSeen());
            assertEquals(1, r.getRoutineDefinitionsDenied());
            assertEquals(0, r.getRoutinesSkippedUnparseable());
        }

        /**
         * ★ 刻意不去切分过程体再逐条解析：自己写一个 SQL 切分器，第一个切错的地方
         * 就会产出一条「看起来像人写的」假关系。宁可少挖。
         */
        @Test
        @DisplayName("BEGIN...END 的过程体解析不了 —— 这是刻意的，不去自己切语句")
        void 过程体不切分() {
            var r = reader.extract(List.of(routine("p_x",
                    "BEGIN DECLARE n INT; SELECT a.x FROM orders a JOIN users b ON a.uid = b.id; END")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(1, r.getRoutinesSkippedUnparseable());
        }

        /** 正文整个就是一条 SELECT 的函数还是挖得到的，这是读 ROUTINES 仅剩的正收益。 */
        @Test
        @DisplayName("正文就是一条 SELECT 的函数照样挖得到")
        void 单条SELECT的函数() {
            var r = reader.extract(List.of(routine("f_x",
                    "select a.x from orders a join users b on a.uid = b.id")));

            assertEquals(List.of("orders.uid=users.id"), pairs(r));
            assertEquals(1, r.getRoutinesParsed());
        }
    }

    // ================================================================

    @Nested
    @DisplayName("同一条关系的归并与出处")
    class Merging {

        /** {@code a.x = b.y} 与 {@code b.y = a.x} 是同一个事实，不能存成两行互为镜像。 */
        @Test
        @DisplayName("左右写反的同一条关系要并成一条，来源累加")
        void 方向归一() {
            var r = reader.extract(List.of(
                    view("v_1", "select 1 from orders a join users b on a.uid = b.id"),
                    view("v_2", "select 1 from users b join orders a on b.id = a.uid")));

            assertEquals(1, r.getJoins().size());
            assertEquals(2, r.getJoins().get(0).getSources().size());
        }

        @Test
        @DisplayName("同一个视图里重复出现（ON 与 WHERE 各写一遍）不重复记来源")
        void 同一来源不重复() {
            var r = reader.extract(List.of(view("v_dup",
                    "select 1 from orders a join users b on a.uid = b.id where a.uid = b.id")));

            assertEquals(1, r.getJoins().size());
            assertEquals(1, r.getJoins().get(0).getSources().size());
        }

        /** 两个互不相干的对象都这么 join，排除了「为一张报表临时凑的」这种一次性拼接。 */
        @Test
        @DisplayName("出现在两个以上对象里，置信度上一档")
        void 多处出现更可信() {
            var one = reader.extract(List.of(view("v_1", "select 1 from orders a join users b on a.uid = b.id")));
            var two = reader.extract(List.of(
                    view("v_1", "select 1 from orders a join users b on a.uid = b.id"),
                    view("v_2", "select 1 from orders c join users d on c.uid = d.id")));

            assertEquals(SemanticSqlCorpusReader.CONFIDENCE_SINGLE_SOURCE, one.getJoins().get(0).confidence());
            assertEquals(SemanticSqlCorpusReader.CONFIDENCE_MULTI_SOURCE, two.getJoins().get(0).confidence());
        }

        /**
         * ★ evidence 必须是 COMMENT。
         * <ul>
         *   <li>贴 GUESS 会被推导那边「GUESS 一律丢弃」的规则整批扔掉，这个阶段就白做了；</li>
         *   <li>贴 DATA 是<b>谎报</b>：本阶段一个数据值都没读过，而 S3 采样验证正是靠这个标签
         *       决定还要不要去核。</li>
         * </ul>
         */
        @Test
        @DisplayName("evidence 是 COMMENT：人写在客户库里的依据，既不是猜的也不是数据验过的")
        void 依据标签() {
            var r = reader.extract(List.of(view("v_1", "select 1 from orders a join users b on a.uid = b.id")));
            var j = r.getJoins().get(0);

            assertEquals(ConnectorSemanticService.EV_COMMENT, j.evidence());
            assertNotEquals(ConnectorSemanticService.EV_GUESS, j.evidence());
            assertNotEquals(ConnectorSemanticService.EV_DATA, j.evidence());
        }

        /**
         * ★ basis 会原样进模型上下文，所以它必须<b>具体且真</b>：
         * 说得出是哪个对象，人才能回客户库里核；同时绝不能捎带 SQL 正文
         * ——视图体里有客户的真实 id、名称、阈值。
         */
        @Test
        @DisplayName("basis 点名出处，且不含任何 SQL 正文")
        void 出处具体且不泄露正文() {
            var r = reader.extract(List.of(view("v_order_detail",
                    "select 1 from orders a join users b on a.uid = b.id where a.tenant_id = 10086")));
            String basis = r.getJoins().get(0).basis();

            assertEquals("来自视图 v_order_detail 的定义", basis);
            assertFalse(basis.contains("10086"), "视图里的字面量绝不能跟着 basis 出去");
            assertFalse(basis.toLowerCase().contains("select"));
        }

        @Test
        @DisplayName("多处来源时点名前几个再报总数")
        void 多来源文案() {
            var r = reader.extract(List.of(
                    view("v_1", "select 1 from orders a join users b on a.uid = b.id"),
                    view("v_2", "select 1 from orders a join users b on a.uid = b.id"),
                    view("v_3", "select 1 from orders a join users b on a.uid = b.id"),
                    routine("p_4", "select 1 from orders a join users b on a.uid = b.id")));
            String basis = r.getJoins().get(0).basis();

            assertEquals("来自视图 v_1、视图 v_2、视图 v_3 的定义（共 4 处）", basis);
        }

        /**
         * 字典序方向<b>不含</b>「谁引用谁」的信息（那要看数据，是 S3 的事）。
         * 下游要在右表那头也挂一份时用 mirrored()，别自己手写交换——
         * 漏换一个列名会造出一条看起来完全正常的假关系。
         */
        @Test
        @DisplayName("mirrored() 左右整体对调，来源不变")
        void 镜像() {
            var r = reader.extract(List.of(view("v_1", "select 1 from orders a join users b on a.uid = b.id")));
            var j = r.getJoins().get(0);
            var m = j.mirrored();

            assertEquals("users.id=orders.uid", pair(m));
            assertEquals(j.getSources().size(), m.getSources().size());
        }
    }

    // ================================================================

    @Nested
    @DisplayName("取数那一半：必须走网关，且失败不能外抛")
    class ThroughGateway {

        private ConnectorSession sessionReturning(QueryResult views, QueryResult routines) {
            ConnectorSession session = mock(ConnectorSession.class,
                    withSettings().extraInterfaces(QueryCapable.class));
            QueryCapable q = (QueryCapable) session;
            when(q.query(anyString(), any())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (sql.contains("information_schema.VIEWS")) {
                    return views;
                }
                if (routines == null) {
                    throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                            "这个数据库账号没有访问该对象的权限");
                }
                return routines;
            });
            return session;
        }

        @SuppressWarnings("unchecked")
        private void givenGatewayRuns(ConnectorSession session) {
            when(gateway.executeAsPlatform(eq(7L), any(), anyString(), any()))
                    .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
        }

        private QueryResult viewRows(List<List<Object>> rows) {
            return new QueryResult(List.of("TABLE_SCHEMA", "TABLE_NAME", "VIEW_DEFINITION"),
                    rows, false, null, "x", 1L);
        }

        /**
         * ★ 审计动作名必须一眼看得出是平台干的。客户的 DBA 在他自己的审计里看到这些查询时，
         * 要分得清「平台在剖析这个库」和「Agent 在替人问数」。
         */
        @Test
        @DisplayName("走 executeAsPlatform，动作名带 platform. 前缀且不与模型工具重名")
        void 走网关且审计名正确() {
            givenGatewayRuns(sessionReturning(viewRows(List.of(
                    List.of("shop", "v_1", "select 1 from orders a join users b on a.uid = b.id"))), null));

            var r = reader.read(7L);

            assertTrue(r.isOk());
            assertEquals(List.of("orders.uid=users.id"), pairs(r));
            ArgumentCaptor<String> op = ArgumentCaptor.forClass(String.class);
            verify(gateway).executeAsPlatform(eq(7L), any(), op.capture(), any());
            assertTrue(op.getValue().startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX),
                    "管理面审计名必须带 platform. 前缀，实际: " + op.getValue());
            assertNotEquals("conn_query", op.getValue());
            assertNotEquals(ConnectorAuditService.OP_SCHEMA_REFRESH, op.getValue());
        }

        /**
         * 申 QUERY 而不是 DESCRIBE：我们确实在客户库上跑了一条 SELECT，
         * 尽管它读的是 information_schema。按实际执行的东西申能力。
         */
        @Test
        @DisplayName("申的是 QUERY 能力 —— 按实际执行的东西申，不按「读的只是元数据」申一个更软的")
        void 申QUERY能力() {
            givenGatewayRuns(sessionReturning(viewRows(List.of()), null));

            reader.read(7L);

            verify(gateway).executeAsPlatform(eq(7L), eq(Capability.QUERY), anyString(), any());
        }

        /**
         * ★ 存储过程正文读不到是<b>只读账号的常态</b>，不是故障：它绝不能把已经拿到的视图结果一起弄丢。
         */
        @Test
        @DisplayName("存储过程那条查询被拒 → 视图结果照样留着，整体仍是 ok")
        void 过程读不到不影响视图() {
            givenGatewayRuns(sessionReturning(viewRows(List.of(
                    List.of("shop", "v_1", "select 1 from orders a join users b on a.uid = b.id"))), null));

            var r = reader.read(7L);

            assertTrue(r.isOk(), "过程读不到属于正常结果，不该判为失败");
            assertEquals(1, r.getJoins().size());
            assertNotNull(r.getNote());
            assertTrue(r.getNote().contains("存储过程"), "note 要说清楚过程正文没读到，实际: " + r.getNote());
        }

        /**
         * ★ 语义层是叠加的注解，不是连接可用的前提。整条失败也只能落成 ok=false + 一句话，
         * 绝不能向上抛把整条推导线带崩。
         */
        @Test
        @DisplayName("网关整条失败 → 返回 ok=false，绝不外抛")
        void 失败不外抛() {
            when(gateway.executeAsPlatform(eq(7L), any(), anyString(), any()))
                    .thenThrow(ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                            "这个数据库账号没有访问该对象的权限"));

            var r = reader.read(7L);

            assertFalse(r.isOk());
            assertNotNull(r.getNote());
            assertTrue(r.getJoins().isEmpty());
        }

        /**
         * ★ 未归类异常的 note 里绝不能出现原始 message：它可能带 SQL、主机名、连接参数，
         * 而 note 会写进 connection.semantic_note 展示给客户看。
         */
        @Test
        @DisplayName("未归类异常 → note 是常量句子，不含原始异常文本")
        void 未归类异常不泄露() {
            when(gateway.executeAsPlatform(eq(7L), any(), anyString(), any()))
                    .thenThrow(new IllegalStateException(
                            "jdbc:mysql://10.0.0.7:3306/shop?user=root&password=hunter2 boom"));

            var r = reader.read(7L);

            assertFalse(r.isOk());
            assertFalse(r.getNote().contains("10.0.0.7"));
            assertFalse(r.getNote().contains("hunter2"));
            assertFalse(r.getNote().contains("jdbc"));
        }

        /**
         * 「这个库没有视图」和「这个阶段没被验证过」是两句话，都得说。
         * 我们自己的参考库正是 0 个视图——这个阶段从来没在真实数据上跑过。
         */
        @Test
        @DisplayName("库里没有视图时，note 要同时说出「没视图」和「本阶段未经真实验证」")
        void 零视图的诚实文案() {
            givenGatewayRuns(sessionReturning(viewRows(List.of()), null));

            var r = reader.read(7L);

            assertTrue(r.getNote().contains("没有视图"), r.getNote());
            assertTrue(r.getNote().contains("验证"), "必须说出这个阶段从未验证过，实际: " + r.getNote());
        }

        /**
         * 多取一行是用来区分「正好 maxViews 个」和「还有更多」的。
         * 只靠 size() == maxViews 判断会把恰好等于上限的完整结果误报成截断。
         */
        @Test
        @DisplayName("视图数超上限：多出来的那一行只用于判定截断，不参与解析")
        void 截断判定() {
            reader.maxViews = 1;
            givenGatewayRuns(sessionReturning(viewRows(List.of(
                    List.of("shop", "v_1", "select 1 from orders a join users b on a.uid = b.id"),
                    List.of("shop", "v_2", "select 1 from items c join users d on c.uid = d.id"))), null));

            var r = reader.read(7L);

            assertTrue(r.isViewsTruncated());
            assertEquals(1, r.getViewsSeen(), "探测用的那一行不该被当成语料处理");
            assertEquals(List.of("orders.uid=users.id"), pairs(r));
        }

        @Test
        @DisplayName("会话不支持查询 → 归成失败，不抛")
        void 会话不支持查询() {
            ConnectorSession plain = mock(ConnectorSession.class);
            givenGatewayRuns(plain);

            var r = reader.read(7L);

            assertFalse(r.isOk());
        }

        /** 关掉开关就一次也不该碰客户的库。 */
        @Test
        @DisplayName("总开关关掉 → 不碰网关")
        void 开关关闭() {
            reader.enabled = false;

            var r = reader.read(7L);

            assertFalse(r.isOk());
            verify(gateway, never()).executeAsPlatform(any(), any(), anyString(), any());
        }

        /** scan-routines=false 时只发一条查询。 */
        @Test
        @DisplayName("关掉存储过程扫描 → 只查 VIEWS 一次")
        void 不扫存储过程() {
            reader.scanRoutines = false;
            ConnectorSession session = sessionReturning(viewRows(List.of()), null);
            givenGatewayRuns(session);

            reader.read(7L);

            verify((QueryCapable) session, never()).query(
                    org.mockito.ArgumentMatchers.contains("information_schema.ROUTINES"), any());
        }
    }

    // ================================================================

    @Nested
    @DisplayName("发给客户库的两条语句本身")
    class CorpusSql {

        /** 把库名拼进 SQL 就是给自己开一个注入口子；这条连接本来就只连着一个库。 */
        @Test
        @DisplayName("用 DATABASE() 限定 schema，不拼任何标识符")
        void 不拼标识符() {
            assertTrue(reader.viewsSql().contains("TABLE_SCHEMA = DATABASE()"));
            assertTrue(reader.routinesSql().contains("ROUTINE_SCHEMA = DATABASE()"));
        }

        /**
         * 定义为空 = 没有 SHOW VIEW 权限。不把它们推到最后，
         * 一个「视图很多但一个都读不到」的库会用空行把名额吃光，真能读的那几个正好排在名额外面。
         */
        @Test
        @DisplayName("读不到定义的排最后，然后按定义长度升序 —— 被 LIMIT 砍掉的正是窄规则本来也用不上的那批")
        void 排序依据() {
            String sql = reader.viewsSql();
            int denied = sql.indexOf("VIEW_DEFINITION IS NULL");
            int len = sql.indexOf("CHAR_LENGTH(VIEW_DEFINITION) ASC");
            int name = sql.indexOf("TABLE_NAME ASC");
            assertTrue(denied > 0 && len > denied && name > len,
                    "ORDER BY 的三段顺序不能动，实际: " + sql);
        }

        @Test
        @DisplayName("LIMIT 比上限多一行，用来判定截断")
        void 多取一行() {
            reader.maxViews = 30;
            reader.maxRoutines = 8;
            assertTrue(reader.viewsSql().endsWith("LIMIT 31"), reader.viewsSql());
            assertTrue(reader.routinesSql().endsWith("LIMIT 9"), reader.routinesSql());
        }

        /**
         * ★ 这两条语句是走 {@link QueryCapable#query} 发出去的，也就是说它们要先过
         * {@link ReadOnlySqlGuard}。护栏会把语句解析后<b>重新序列化</b>，还会按 maxRows 收紧 LIMIT——
         * 只要我们写的 LIMIT 与传给护栏的 maxRows 对不上，「多取一行」这套截断判定就会
         * <b>静默失效</b>：护栏把 201 收成 200，于是永远拿不到那第 201 行，
         * 一个视图超标的库会被报成「没有截断」。本地没有 MySQL，这是唯一能提前发现它的办法。
         */
        @Test
        @DisplayName("两条语句都过得了只读护栏，且 LIMIT 不会被护栏收紧")
        void 过只读护栏() {
            ReadOnlySqlGuard guard = new ReadOnlySqlGuard();
            reader.maxViews = 200;
            reader.maxRoutines = 50;

            var v = guard.check(reader.viewsSql(), reader.maxViews + 1);
            assertFalse(v.limitInjected(), "护栏动了 LIMIT，截断判定会失效：" + v.effectiveSql());
            assertTrue(v.effectiveSql().contains("LIMIT 201"), v.effectiveSql());

            var p = guard.check(reader.routinesSql(), reader.maxRoutines + 1);
            assertFalse(p.limitInjected(), "护栏动了 LIMIT，截断判定会失效：" + p.effectiveSql());
            assertTrue(p.effectiveSql().contains("LIMIT 51"), p.effectiveSql());
        }
    }

    // ================================================================

    @Nested
    @DisplayName("标识符处理")
    class Identifiers {

        /** MySQL 规范化视图定义时会给每一个标识符加反引号。 */
        @Test
        @DisplayName("反引号 / 双引号 / 方括号都要剥掉")
        void 剥引号() {
            assertEquals("orders", SemanticSqlCorpusReader.unquote("`orders`"));
            assertEquals("orders", SemanticSqlCorpusReader.unquote("\"orders\""));
            assertEquals("orders", SemanticSqlCorpusReader.unquote("[orders]"));
            assertEquals("orders", SemanticSqlCorpusReader.unquote("  orders  "));
            assertEquals("", SemanticSqlCorpusReader.unquote(null));
        }

        /** 归并要大小写不敏感，否则同一条关系会因为两个视图里写法不同存成两条。 */
        @Test
        @DisplayName("大小写不同的同一条关系并成一条")
        void 大小写归并() {
            var r = reader.extract(List.of(
                    view("v_1", "select 1 from Orders a join Users b on a.UID = b.Id"),
                    view("v_2", "select 1 from orders c join users d on c.uid = d.id")));

            assertEquals(1, r.getJoins().size());
            assertEquals(2, r.getJoins().get(0).getSources().size());
        }
    }

    // ================================================================

    @Nested
    @DisplayName("上限与预算")
    class Limits {

        /** 下游 replaceInferred 是先删后插的一个事务，插到一半失败会连旧语义行一起回滚。 */
        @Test
        @DisplayName("关系条数封顶，超出的计数丢弃")
        void 条数封顶() {
            List<SemanticSqlCorpusReader.CorpusEntry> many = new ArrayList<>();
            for (int i = 0; i < SemanticSqlCorpusReader.MAX_JOINS + 5; i++) {
                many.add(view("v_" + i, "select 1 from t" + i + " a join u" + i + " b on a.x = b.y"));
            }

            var r = reader.extract(many);

            assertEquals(SemanticSqlCorpusReader.MAX_JOINS, r.getJoins().size());
            assertEquals(5, r.getDroppedOverflow());
        }

        /**
         * 语义层是叠加的注解，不值得为它把一条连接的并发闸占上几分钟。
         * 预算设成 0 时应当一个都不解析，而且是<b>计数地</b>不解析。
         */
        @Test
        @DisplayName("解析总预算用尽 → 剩下的对象计入 skippedNoBudget，而不是静默消失")
        void 预算用尽() {
            reader.parseBudgetMs = -1L;

            var r = reader.extract(List.of(
                    view("v_1", "select 1 from orders a join users b on a.uid = b.id"),
                    view("v_2", "select 1 from items c join users d on c.uid = d.id")));

            assertTrue(r.getJoins().isEmpty());
            assertEquals(2, r.getSkippedNoBudget());
        }

        /** 深递归在 jsqlparser 和我们自己的遍历里都会 StackOverflow，那个错会把整条推导线打死。 */
        @Test
        @DisplayName("超深的 AND 链不会把进程带崩，整个对象被跳过")
        void 深递归不崩() {
            StringBuilder sb = new StringBuilder("select 1 from orders a, users b where a.uid = b.id");
            for (int i = 0; i < SemanticSqlCorpusReader.RECURSION_MAX + 50; i++) {
                sb.append(" and a.c").append(i).append(" = ").append(i);
            }

            var r = reader.extract(List.of(view("v_deep", sb.toString())));

            assertNotNull(r, "不能抛，更不能 StackOverflow");
            // ★ 停手必须【被计数】。递归够不到的那条关联条件会让这个视图「什么都没挖到」，
            //   而「没挖到」和「挖到 0 条」在 joins 里长得一模一样——只有这个计数器分得开。
            assertTrue(r.getDroppedTooDeep() > 0, "深到停手也要留下痕迹，不能静默");
        }
    }
}

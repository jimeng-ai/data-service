package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ObjectStale;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.Presence;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SnapshotPresence;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.StaleResult;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.KEY_STALE_REMOVED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_CAVEAT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_METRIC;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_OBJECT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_HUMAN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_INFERRED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_CONFIRMED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_DRAFT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_STALE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.fieldAnchor;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.joinAnchor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漂移处置这一轮补上的几处<b>静默</b>错：错了不报错，只是一张删掉的表的说明一直注入、一条删了列的关系一直注入、
 * 人刚答的口径被一轮刷新盖掉、一张几个月前就没了的表把人后来确认的口径拖下水、管理台表头与按表告警对不上。
 */
class ConnectorSemanticDriftRoundFixTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private static final FieldDetail ORD_ID = new FieldDetail("id", "bigint", false, "订单ID", null);
    private static final FieldDetail ORD_USER_ID = new FieldDetail("user_id", "bigint", true, "下单人", null);
    private static final FieldDetail ORD_STATUS = new FieldDetail("status", "tinyint", true, "状态码", null);
    private static final FieldDetail ORD_LEGACY = new FieldDetail("legacy_no", "varchar(32)", true, "旧单号", null);
    private static final FieldDetail USR_ID = new FieldDetail("id", "bigint", false, "用户ID", null);
    private static final FieldDetail REFUND_ORDER_NO = new FieldDetail("order_no", "varchar(32)", true, null, null);
    private static final FieldDetail REFUND_AMT = new FieldDetail("amt", "decimal(12,2)", true, "退款金额", null);

    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService service;
    private long seq = 1;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    // ================================================================ 夹具

    private ConnectorSemantic base(String scope, String obj, String field, String status) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setSource(SOURCE_INFERRED);
        r.setStatus(status);
        r.setVerified(V_NONE);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
    }

    private ConnectorSemantic objectRow(String table, String status) {
        return base(SCOPE_OBJECT, table, "", status);
    }

    private ConnectorSemantic caveatRow(String table, String status) {
        return base(SCOPE_CAVEAT, table, "", status);
    }

    private ConnectorSemantic fieldRow(String table, FieldDetail col, String status) {
        ConnectorSemantic r = base(SCOPE_FIELD, table, col.name(), status);
        r.setAnchorKind(ANCHOR_FIELD);
        r.setAnchorHash(fieldAnchor(col));
        return r;
    }

    private ConnectorSemantic joinRow(String table, FieldDetail left, String toTable, FieldDetail right, String status) {
        ConnectorSemantic r = base(SCOPE_JOIN, table, left.name(), status);
        r.setAnchorKind(ANCHOR_JOIN);
        r.setAnchorHash(joinAnchor(left, right));
        r.setDetailJson(json(Map.of("to_object", toTable, "to_column", right.name())));
        return r;
    }

    private ConnectorSemantic metric(String term, List<String> appliesTo, List<String> staleList, String status) {
        ConnectorSemantic m = base(SCOPE_METRIC, "", "", status);
        m.setTerm(term);
        m.setSource(SOURCE_HUMAN);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("applies_to", appliesTo);
        if (staleList != null) {
            d.put(KEY_STALE_REMOVED, staleList);
        }
        m.setDetailJson(json(d));
        return m;
    }

    private static Map<String, Map<String, FieldDetail>> snapshot(String table, FieldDetail... fs) {
        Map<String, FieldDetail> cols = new LinkedHashMap<>();
        for (FieldDetail f : fs) {
            cols.put(f.name(), f);
        }
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put(table, cols);
        return m;
    }

    private static final SnapshotPresence COMPLETE_ORDERS = SnapshotPresence.of(List.of("orders"), List.of("orders"), false);

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(ConnectorSemantic r) {
        try {
            return r.getDetailJson() == null ? Map.of() : CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    @SuppressWarnings("unchecked")
    private List<LambdaUpdateWrapper<ConnectorSemantic>> writes(int n) {
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper, times(n)).update(any(), w.capture());
        return w.getAllValues();
    }

    // ================================================================ 条目 1：目录截断时单独确认

    @Nested
    @DisplayName("目录截断时单独确认过存不存在（K-1）")
    class ConfirmedExistence {

        @Test
        @DisplayName("★ 确认不在 → ABSENT；确认在 → LISTED_ONLY；连接器答不了 → 仍是 UNKNOWN")
        void presenceHonoursExistenceCheck() {
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders"), true,
                    List.of("t_gone", "T_Small", "orders"), List.of("t_small"));
            assertEquals(Presence.ABSENT, p.stateOf("T_GONE"));
            assertEquals(Presence.LISTED_ONLY, p.stateOf("t_small"), "按连接器存储的写法返回，比较不分大小写");
            assertEquals(Presence.DESCRIBED, p.stateOf("orders"));
            assertEquals(Presence.UNKNOWN, p.stateOf("never_asked"), "没问过的名字仍然不知道");

            SnapshotPresence cannotTell = SnapshotPresence.of(List.of("orders"), List.of("orders"), true,
                    List.of("t_gone"), null);
            assertEquals(Presence.UNKNOWN, cannotTell.stateOf("t_gone"), "答不了绝不能读成都不在");
        }

        /** ★ 需求里点名的：超过 500 个对象的库上删掉一张表。从前它的说明、关系、口径永远注入。 */
        @Test
        @DisplayName("★ 大库上被删的表：确认不在之后说明、关系、口径一起过期并归因")
        void droppedTableOnHugeSchemaIsStaled() {
            ConnectorSemantic obj = objectRow("t_gone", ST_DRAFT);
            ConnectorSemantic join = joinRow("orders", ORD_USER_ID, "t_gone", USR_ID, ST_DRAFT);
            ConnectorSemantic m = metric("活跃用户", List.of("t_gone"), null, ST_CONFIRMED);
            givenRows(obj, join, m);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders"), true, List.of("t_gone"), List.of());

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID, ORD_USER_ID), p, List.of("t_gone"));

            assertEquals(3, r.staled());
            assertEquals(ST_STALE, obj.getStatus());
            assertEquals(ST_STALE, join.getStatus());
            assertEquals(ST_STALE, m.getStatus());
            ObjectStale i = r.removedImpact().get(0);
            assertEquals("t_gone", i.objectName());
            assertEquals(2, i.staledRows());
            assertEquals(1, i.staledMetrics());
        }

        @Test
        @DisplayName("连接器答不了存不存在：一个字不碰")
        void cannotTellTouchesNothing() {
            givenRows(objectRow("t_gone", ST_DRAFT), metric("活跃用户", List.of("t_gone"), null, ST_CONFIRMED));
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders"), true, List.of("t_gone"), null);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), p, List.of("t_gone"));

            assertEquals(0, r.staled());
            verify(semanticMapper, never()).update(any(), any());
        }
    }

    // ================================================================ 条目 2：描述到的那一端列没了

    @Nested
    @DisplayName("关系：描述到了的那一端上列没了，另一端没看到也照样过期")
    class DroppedColumnOnDescribedEnd {

        /** ★ 需求里点名的：orders.user_id 被删，而 users 这次只在目录里。从前先看到对端没描述就跳过，关系一直注入。 */
        @Test
        @DisplayName("★ 左列被删、右表 LISTED_ONLY：过期")
        void leftColumnDroppedTargetListedOnly() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT);
            givenRows(j);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID, ORD_STATUS),
                    SnapshotPresence.of(List.of("orders"), List.of("orders", "users"), false), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, j.getStatus());
            assertTrue(r.removedImpact().isEmpty(), "没有表消失，不归因");
        }

        @Test
        @DisplayName("左列被删、右表 UNKNOWN（目录截断）：同样过期")
        void leftColumnDroppedTargetUnknown() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT);
            givenRows(j);

            service.applyDrift(CONN_ID, snapshot("orders", ORD_ID),
                    SnapshotPresence.of(List.of("orders"), List.of("orders"), true), List.of());

            assertEquals(ST_STALE, j.getStatus());
        }

        @Test
        @DisplayName("左列还在、右表没看到：不碰（只有真没看到的那一端可以跳过）")
        void leftColumnPresentTargetUnseenIsUntouched() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT);
            givenRows(j);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID, ORD_USER_ID),
                    SnapshotPresence.of(List.of("orders"), List.of("orders", "users"), false), List.of());

            assertEquals(0, r.staled());
            assertEquals(ST_DRAFT, j.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("右列被删、左表只在目录里：同样过期")
        void rightColumnDroppedLeftListedOnly() {
            ConnectorSemantic j = joinRow("t_refund", REFUND_ORDER_NO, "orders", ORD_LEGACY, ST_DRAFT);
            givenRows(j);

            service.applyDrift(CONN_ID, snapshot("orders", ORD_ID),
                    SnapshotPresence.of(List.of("orders"), List.of("orders", "t_refund"), false), List.of());

            assertEquals(ST_STALE, j.getStatus());
        }
    }

    // ================================================================ 条目 3：只写改的列，以读到时的样子为条件

    @Nested
    @DisplayName("回写：只写 status / detail_json，并以读到时的样子为条件")
    class ConditionalWrites {

        @Test
        @DisplayName("★ 写回的 SET 里没有 gloss / 留痕 / 回答人；WHERE 里带着更新时间与内容列")
        void writesOnlyWhatDriftChanges() {
            ConnectorSemantic m = metric("销售额", List.of("t_refund"), null, ST_CONFIRMED);
            m.setGloss("人刚答的口径");
            m.setHistoryJson("[]");
            givenRows(m);

            service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), COMPLETE_ORDERS, List.of("t_refund"));

            LambdaUpdateWrapper<ConnectorSemantic> w = writes(1).get(0);
            String set = w.getSqlSet();
            assertTrue(set.contains("status=") && set.contains("detail_json="), set);
            assertFalse(set.contains("gloss") || set.contains("history_json") || set.contains("answered")
                    || set.contains("trace_id"), "漂移处置不该写它没改的列：" + set);
            String where = w.getSqlSegment();
            for (String col : List.of("update_time", "status", "source", "gloss", "detail_json", "history_json")) {
                assertTrue(where.contains(col), "写回条件里缺 " + col + "：" + where);
            }
        }

        /** ★ 需求里点名的：人在对话里确认口径恰好落在这一轮中间。别处赢，这一轮什么都不算。 */
        @Test
        @DisplayName("★ 别处刚写过（影响 0 行）：跳过、不计数、不归因，内存里的行也不改")
        void concurrentWriteWins() {
            when(semanticMapper.update(any(), any())).thenReturn(0);
            ConnectorSemantic obj = objectRow("t_refund", ST_DRAFT);
            ConnectorSemantic m = metric("销售额", List.of("t_refund"), null, ST_CONFIRMED);
            String before = m.getDetailJson();
            givenRows(obj, m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), COMPLETE_ORDERS, List.of("t_refund"));

            assertEquals(0, r.staled());
            assertEquals(2, r.concurrentSkips());
            assertEquals(ST_DRAFT, obj.getStatus());
            assertEquals(ST_CONFIRMED, m.getStatus());
            assertEquals(before, m.getDetailJson());
            assertTrue(r.removedImpact().isEmpty());
        }
    }

    // ================================================================ 条目 4：「这一次消失的表」只认调用方报的

    @Nested
    @DisplayName("「这一次消失的表」只认调用方报的名单")
    class VanishedOnlyFromCaller {

        /**
         * ★ 需求里点名的：部署到旧版本之上的第一次刷新。旧代码在早已消失的表上复活过表用途行；
         * 它们这次被标过期是对的，但那张表不是「这一次」消失的——几个月后人确认过的口径不能被拖下水。
         */
        @Test
        @DisplayName("★ ABSENT 表上新标过期、却不在名单里：行照样过期，不归因、不牵连口径")
        void absentButUnreportedIsNotAttributed() {
            ConnectorSemantic revivedByOldCode = objectRow("t_old", ST_DRAFT);
            ConnectorSemantic confirmedLater = metric("GMV", List.of("orders", "t_old"), null, ST_CONFIRMED);
            givenRows(revivedByOldCode, confirmedLater);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), COMPLETE_ORDERS, List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, revivedByOldCode.getStatus());
            assertEquals(ST_CONFIRMED, confirmedLater.getStatus());
            assertFalse(detail(confirmedLater).containsKey(KEY_STALE_REMOVED));
            assertTrue(r.removedImpact().isEmpty());
        }

        /** ★ 需求里点名的：只被口径引用、没有别的语义行的表。重试时刷新方把它再报一次，口径必须能补上。 */
        @Test
        @DisplayName("★ 只挂着口径的表：刷新方再报一次，口径失效照样补上")
        void metricOnlyTableIsAttributedOnRetry() {
            ConnectorSemantic onlyMetric = metric("退款额", List.of("t_refund"), null, ST_CONFIRMED);
            givenRows(onlyMetric);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), COMPLETE_ORDERS, List.of("t_refund"));

            assertEquals(ST_STALE, onlyMetric.getStatus());
            assertEquals(List.of("t_refund"), detail(onlyMetric).get(KEY_STALE_REMOVED));
            ObjectStale i = r.removedImpact().get(0);
            assertEquals(0, i.staledRows());
            assertEquals(1, i.staledMetrics());
            assertEquals(List.of("退款额"), i.metricTerms());
        }
    }

    // ================================================================ 条目 6：只依赖「表在不在」的行在 LISTED_ONLY 上复活

    @Nested
    @DisplayName("只看「表在不在」的行：LISTED_ONLY 足以复活")
    class ExistenceOnlyRevival {

        @Test
        @DisplayName("★ 表用途 / 告诫 / 口径名单复活；锚在列上的字段行不复活")
        void existenceOnlyRowsReviveOnListedOnly() {
            ConnectorSemantic obj = objectRow("t_small", ST_STALE);
            ConnectorSemantic caveat = caveatRow("t_small", ST_STALE);
            ConnectorSemantic field = fieldRow("t_small", REFUND_AMT, ST_STALE);
            ConnectorSemantic m = metric("退款额", List.of("t_small"), List.of("t_small"), ST_STALE);
            givenRows(obj, caveat, field, m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID),
                    SnapshotPresence.of(List.of("orders"), List.of("orders", "t_small"), false), List.of());

            assertEquals(3, r.revived());
            assertEquals(ST_DRAFT, obj.getStatus());
            assertEquals(ST_DRAFT, caveat.getStatus());
            assertEquals(ST_STALE, field.getStatus());
            assertEquals(ST_CONFIRMED, m.getStatus());
            assertFalse(detail(m).containsKey(KEY_STALE_REMOVED));
        }

        @Test
        @DisplayName("UNKNOWN 仍然不复活")
        void unknownStillDoesNotRevive() {
            givenRows(objectRow("t_small", ST_STALE), caveatRow("t_small", ST_STALE),
                    metric("退款额", List.of("t_small"), List.of("t_small"), ST_STALE));

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID),
                    SnapshotPresence.of(List.of("orders"), List.of("orders"), true), List.of());

            assertEquals(0, r.revived());
            verify(semanticMapper, never()).update(any(), any());
        }
    }

    // ================================================================ 条目 7：影响面只数新归因的

    @Nested
    @DisplayName("影响面只数这一轮新归到那张表名下的（K-4）")
    class NewlyAttributedCounts {

        @Test
        @DisplayName("★ 早就过期的行、早就挂着这张表的口径不算；本轮新标的才算，与本轮 staled 同一个口径")
        void countsOnlyNewlyAttributed() {
            ConnectorSemantic longStale = objectRow("t_refund", ST_STALE);
            ConnectorSemantic newlyStaled = fieldRow("t_refund", REFUND_AMT, ST_DRAFT);
            ConnectorSemantic alreadyListed = metric("旧口径", List.of("t_refund"), List.of("t_refund"), ST_STALE);
            ConnectorSemantic newlyHit = metric("销售额", List.of("orders", "t_refund"), null, ST_CONFIRMED);
            givenRows(longStale, newlyStaled, alreadyListed, newlyHit);

            StaleResult r = service.applyDrift(CONN_ID, snapshot("orders", ORD_ID), COMPLETE_ORDERS, List.of("t_refund"));

            assertEquals(2, r.staled());
            ObjectStale i = r.removedImpact().get(0);
            assertEquals(1, i.staledRows());
            assertEquals(1, i.staledMetrics());
            assertEquals(List.of("销售额"), i.metricTerms());
        }
    }

    // ================================================================ 刷新方要问的名字

    @Nested
    @DisplayName("语义层提到过的对象名")
    class ReferencedNames {

        @Test
        @DisplayName("挂着行的表、关系指向的表、口径过期名单里的表都在")
        void collectsRowsTargetsAndStaleLists() {
            givenRows(objectRow("orders", ST_DRAFT), joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT),
                    metric("退款额", List.of("t_refund"), List.of("t_refund"), ST_STALE));

            Set<String> names = service.referencedObjectNames(CONN_ID);

            assertTrue(names.containsAll(List.of("orders", "users", "t_refund")), names.toString());
            assertFalse(names.contains(""), "口径行的空表名不是一个对象名");
        }

        @Test
        @DisplayName("读失败返回空集合，不抛")
        void readFailureIsEmpty() {
            when(semanticMapper.selectList(any())).thenThrow(new RuntimeException("连接池满了"));
            assertTrue(service.referencedObjectNames(CONN_ID).isEmpty());
        }
    }
}

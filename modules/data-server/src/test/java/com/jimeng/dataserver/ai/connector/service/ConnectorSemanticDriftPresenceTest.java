package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漂移处置的四种处境（DESCRIBED / LISTED_ONLY / ABSENT / UNKNOWN）与「这一次消失的表」的归因。
 *
 * <p>这一组守的是两种方向相反、却都不报错的错：把「这次没看到」当成「没了」（假过期：口径停止注入、叫人重答），
 * 以及把「上次没算成」当成「没发生」（漏报：漂移处置失败一次，那张表带走了什么就永远没人知道）。
 */
class ConnectorSemanticDriftPresenceTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private static final FieldDetail ORD_ID = new FieldDetail("id", "bigint", false, "订单ID", null);
    private static final FieldDetail ORD_USER_ID = new FieldDetail("user_id", "bigint", true, "下单人", null);
    private static final FieldDetail ORD_STATUS = new FieldDetail("status", "tinyint", true, "状态码", null);
    private static final FieldDetail USR_ID = new FieldDetail("id", "bigint", false, "用户ID", null);
    private static final FieldDetail REFUND_ORDER_ID = new FieldDetail("order_id", "bigint", true, null, null);
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
        when(semanticMapper.update(any(), any())).thenReturn(1);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
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
        d.put("sql_fragment", "SUM(pay_amt) - SUM(refund_amt)");
        d.put("applies_to", appliesTo);
        if (staleList != null) {
            d.put(KEY_STALE_REMOVED, staleList);
        }
        m.setDetailJson(json(d));
        return m;
    }

    private static Map<String, Map<String, FieldDetail>> ordersSnapshot() {
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put("orders", cols(ORD_ID, ORD_USER_ID, ORD_STATUS));
        return m;
    }

    private static Map<String, FieldDetail> cols(FieldDetail... fs) {
        Map<String, FieldDetail> m = new LinkedHashMap<>();
        for (FieldDetail f : fs) {
            m.put(f.name(), f);
        }
        return m;
    }

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
            return CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    // ================================================================

    @Nested
    @DisplayName("没看到的表：不标过期，也不复活")
    class Unseen {

        /**
         * ★ 需求里点名的那一种：表被挤出 200 的描述上限，但目录列出了它。
         * 从前这里把它挂着的行全部标 STALE。反方向要分行的种类：锚在列上的行没有列可比，已经过期的不能复活；
         * 表用途行只看表在不在，目录列出了就证明表在，可以复活。
         */
        @Test
        @DisplayName("★ LISTED_ONLY 上：锚在列上的行不过期也不复活；只看表在不在的表用途行照常复活")
        void listedOnlyRowsAreNeitherStaledNorRevived() {
            ConnectorSemantic obj = objectRow("t_small", ST_DRAFT);
            ConnectorSemantic field = fieldRow("t_small", REFUND_AMT, ST_DRAFT);
            ConnectorSemantic staleObj = objectRow("t_small2", ST_STALE);
            ConnectorSemantic staleField = fieldRow("t_small2", REFUND_AMT, ST_STALE);
            givenRows(obj, field, staleObj, staleField);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders", "T_SMALL", "t_small2"), false);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), p, List.of());

            assertEquals(0, r.staled());
            assertEquals(1, r.revived(), "目录列出了就证明表在：表用途行不锚列，可以复活");
            assertEquals(ST_DRAFT, obj.getStatus());
            assertEquals(ST_DRAFT, field.getStatus(), "没有列可比时锚点不重算——算不出来不等于变了");
            assertEquals(ST_DRAFT, staleObj.getStatus());
            assertEquals(ST_STALE, staleField.getStatus(), "锚在列上的行复活同样需要列，这次没有");
            assertTrue(r.removedImpact().isEmpty());
        }

        @Test
        @DisplayName("UNKNOWN（目录本身截断、没列出）上的行：同样一个字不碰")
        void unknownRowsAreNeitherStaledNorRevived() {
            ConnectorSemantic obj = objectRow("t_beyond", ST_DRAFT);
            ConnectorSemantic stale = objectRow("t_beyond2", ST_STALE);
            givenRows(obj, stale);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders"), true);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), p, List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_DRAFT, obj.getStatus());
            assertEquals(ST_STALE, stale.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 左表描述到了，右表只在目录里：两列指纹拼不出来，这条关系既不能判过期也不能判恢复。 */
        @Test
        @DisplayName("★ 关系的对端 LISTED_ONLY：锚点不重算，状态原样")
        void joinToListedOnlyTargetIsNotRecomputed() {
            ConnectorSemantic draft = joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT);
            ConnectorSemantic stale = joinRow("orders", ORD_STATUS, "users", USR_ID, ST_STALE);
            givenRows(draft, stale);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders", "users"), false);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), p, List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_DRAFT, draft.getStatus());
            assertEquals(ST_STALE, stale.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("关系的对端 ABSENT：照样过期，并记到对端表名下")
        void joinToAbsentTargetStalesAndIsAttributed() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID, ST_DRAFT);
            givenRows(j);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders"), false);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), p, List.of("users"));

            assertEquals(ST_STALE, j.getStatus());
            assertEquals(1, r.removedImpact().size());
            assertEquals("users", r.removedImpact().get(0).objectName());
            assertEquals(1, r.removedImpact().get(0).staledRows());
        }

        /** 口径名单只关心「表在不在」：目录列出了就划掉并复活；目录截断没列到（不知道）就不动。 */
        @Test
        @DisplayName("口径的过期名单里那张表 LISTED_ONLY：划掉并复活；UNKNOWN：名单不动、口径不复活")
        void metricStaleListFollowsTableExistence() {
            ConnectorSemantic listed = metric("销售额", List.of("orders", "t_refund"), List.of("t_refund"), ST_STALE);
            givenRows(listed);
            StaleResult r1 = service.applyDrift(CONN_ID, ordersSnapshot(),
                    SnapshotPresence.of(List.of("orders"), List.of("orders", "t_refund"), false), List.of());
            assertEquals(1, r1.revived());
            assertEquals(ST_CONFIRMED, listed.getStatus());
            assertFalse(detail(listed).containsKey(KEY_STALE_REMOVED));

            ConnectorSemantic unknown = metric("退款额", List.of("t_refund"), List.of("t_refund"), ST_STALE);
            String before = unknown.getDetailJson();
            givenRows(unknown);
            StaleResult r2 = service.applyDrift(CONN_ID, ordersSnapshot(),
                    SnapshotPresence.of(List.of("orders"), List.of("orders"), true), List.of());
            assertEquals(0, r2.revived());
            assertEquals(ST_STALE, unknown.getStatus());
            assertEquals(before, unknown.getDetailJson());
        }

        /** 调用方把一张只是没描述到的表报成 REMOVED（与目录对不上）：以处境为准，不收。 */
        @Test
        @DisplayName("removedObjects 里不是 ABSENT 的名字被忽略")
        void removedNameThatIsNotAbsentIsIgnored() {
            ConnectorSemantic obj = objectRow("t_small", ST_DRAFT);
            ConnectorSemantic m = metric("退款额", List.of("t_small"), null, ST_CONFIRMED);
            givenRows(obj, m);
            SnapshotPresence p = SnapshotPresence.of(List.of("orders"), List.of("orders", "t_small"), false);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), p, List.of("t_small"));

            assertEquals(0, r.staled());
            assertEquals(ST_DRAFT, obj.getStatus());
            assertEquals(ST_CONFIRMED, m.getStatus());
            assertTrue(r.removedImpact().isEmpty());
        }
    }

    @Nested
    @DisplayName("「这一次消失的表」：补报漏掉的，不重复报早就没了的")
    class Attribution {

        private final SnapshotPresence complete = SnapshotPresence.of(List.of("orders"), List.of("orders"), false);

        /**
         * ★ 上一次刷新 diff 报了 REMOVED、快照已落库、漂移处置却失败了。这一次 diff 不会再报它，
         * 刷新方从待补报名单里把它再报一次——影响面必须在这一次补上，口径也必须跟着失效。
         */
        @Test
        @DisplayName("★ 漂移处置失败后的重试：待补报名单里的表再报一次，影响面与口径失效照样补上")
        void retryAfterFailedDriftReportsMissedImpact() {
            ConnectorSemantic refundObj = objectRow("t_refund", ST_DRAFT);
            ConnectorSemantic refundAmt = fieldRow("t_refund", REFUND_AMT, ST_DRAFT);
            ConnectorSemantic joinToRefund = joinRow("orders", ORD_ID, "t_refund", REFUND_ORDER_ID, ST_DRAFT);
            ConnectorSemantic sales = metric("销售额", List.of("orders", "t_refund"), null, ST_CONFIRMED);
            givenRows(refundObj, refundAmt, joinToRefund, sales);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), complete, List.of("t_refund"));

            assertEquals(4, r.staled());
            assertEquals(ST_STALE, sales.getStatus());
            assertEquals(List.of("t_refund"), detail(sales).get(KEY_STALE_REMOVED));
            assertEquals(1, r.removedImpact().size());
            ObjectStale i = r.removedImpact().get(0);
            assertEquals("t_refund", i.objectName());
            assertEquals(3, i.staledRows(), "表用途 + 字段 + 指向它的关系");
            assertEquals(1, i.staledMetrics());
            assertEquals(List.of("销售额"), i.metricTerms());
        }

        /**
         * ★ 早就没了的表：行早就是 STALE，不再报；人刚重新确认过的口径（名单已清）也不会被它重新拖下水。
         */
        @Test
        @DisplayName("★ 早就没了、行早已过期的表：不重复报，也不把人刚确认的口径重新标过期")
        void longGoneTableIsNotReReported() {
            ConnectorSemantic refundObj = objectRow("t_refund", ST_STALE);
            ConnectorSemantic refundAmt = fieldRow("t_refund", REFUND_AMT, ST_STALE);
            ConnectorSemantic joinToRefund = joinRow("orders", ORD_ID, "t_refund", REFUND_ORDER_ID, ST_STALE);
            ConnectorSemantic sales = metric("销售额", List.of("orders", "t_refund"), null, ST_CONFIRMED);
            givenRows(refundObj, refundAmt, joinToRefund, sales);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), complete, List.of());

            assertEquals(0, r.staled());
            assertTrue(r.removedImpact().isEmpty());
            assertEquals(ST_CONFIRMED, sales.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 笔误的名字在完整目录里同样是 ABSENT；「ABSENT 就算」会让一个笔误永久停用人答的口径。 */
        @Test
        @DisplayName("applies_to 的笔误在完整目录里是 ABSENT，也不会让口径过期")
        void typoInAppliesToNeverStalesOnCompleteCatalog() {
            ConnectorSemantic refundObj = objectRow("t_refund", ST_DRAFT);
            ConnectorSemantic gmv = metric("GMV", List.of("orders", "ordres_typo"), null, ST_CONFIRMED);
            givenRows(refundObj, gmv);

            StaleResult r = service.applyDrift(CONN_ID, ordersSnapshot(), complete, List.of());

            assertEquals(ST_CONFIRMED, gmv.getStatus());
            assertEquals(ST_STALE, refundObj.getStatus(), "表确实不在：行照样过期");
            assertTrue(r.removedImpact().isEmpty(), "刷新方没报它消失：不归因到影响面，也不牵连口径");
        }
    }

    @Nested
    @DisplayName("大小写与处境判定")
    class CaseAndStates {

        /**
         * 处境判定不分大小写，锚点重算若分大小写，一张只改了大小写的表会被判成「描述到了」、锚点却「算不出来」，
         * 挂在上面的字段与关系全部假过期。
         */
        @Test
        @DisplayName("表名、列名只差大小写时锚点照样算得出来")
        void anchorLookupIsCaseInsensitive() {
            ConnectorSemantic f = fieldRow("Orders", ORD_STATUS, ST_DRAFT);
            f.setFieldName("STATUS");
            ConnectorSemantic g = fieldRow("ORDERS", ORD_USER_ID, ST_STALE);
            g.setFieldName("User_Id");
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "USERS", USR_ID, ST_DRAFT);
            givenRows(f, g, j);
            Map<String, Map<String, FieldDetail>> snap = ordersSnapshot();
            snap.put("users", cols(USR_ID));

            StaleResult r = service.applyDrift(CONN_ID, snap, null, List.of());

            assertEquals(0, r.staled());
            assertEquals(1, r.revived());
            assertEquals(ST_DRAFT, f.getStatus());
            assertEquals(ST_DRAFT, g.getStatus());
            assertEquals(ST_DRAFT, j.getStatus());
        }

        @Test
        @DisplayName("四种处境的判定矩阵")
        void presenceStates() {
            SnapshotPresence p = SnapshotPresence.of(List.of("Orders"), List.of("orders", "T_Small"), false);
            assertEquals(Presence.DESCRIBED, p.stateOf("ORDERS"));
            assertEquals(Presence.LISTED_ONLY, p.stateOf("t_small"));
            assertEquals(Presence.ABSENT, p.stateOf("gone"));
            assertEquals(Presence.UNKNOWN, p.stateOf(null), "没有名字的东西不该让任何行过期");
            assertEquals(Presence.UNKNOWN, p.stateOf("  "));

            SnapshotPresence truncated = SnapshotPresence.of(List.of("orders"), List.of(), true);
            assertEquals(Presence.DESCRIBED, truncated.stateOf("orders"), "描述到的一定算列出了");
            assertEquals(Presence.UNKNOWN, truncated.stateOf("gone"));

            assertEquals(Presence.ABSENT, SnapshotPresence.describedOnly(List.of("a")).stateOf("b"));
        }
    }
}

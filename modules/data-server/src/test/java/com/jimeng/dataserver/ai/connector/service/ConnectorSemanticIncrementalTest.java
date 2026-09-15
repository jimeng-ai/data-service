package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 增量写入与「表消失」的影响面。
 *
 * <p>这两件事的失效方式都是静默的：增量写入一旦走成删了重插，197 张老表的采样验证结论悄悄清零；
 * 表消失判据一旦只看这次的 diff，已经不存在的表的说明会在下一次刷新时复活。
 */
class ConnectorSemanticIncrementalTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;
    private long seq = 1;

    /** LambdaUpdateWrapper.set 会当场解析列名，纯单测里要手工建 lambda 缓存。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private ConnectorSemantic row(String scope, String obj, String field, String term, String source) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm(term);
        r.setSource(source);
        r.setStatus(SOURCE_HUMAN.equals(source) ? ST_CONFIRMED : ST_DRAFT);
        r.setVerified(V_NONE);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
    }

    private static ConnectorSemantic fresh(String scope, String obj, String field) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setGloss("新推出来的一句话");
        r.setStatus(ST_DRAFT);
        r.setVerified(V_NONE);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
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
            return r.getDetailJson() == null ? Map.of() : CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    // ================================================================ 增量写入

    @Nested
    @DisplayName("增量写入：原地 UPSERT，一行不删")
    class Upsert {

        @Test
        @DisplayName("★ 缺的插入；新表上已有的 INFERRED 行（大小写不同）原地更新并复活，可空列显式写 null")
        void insertsMissingAndUpdatesInPlaceCaseInsensitively() {
            ConnectorSemantic staleObject = row(SCOPE_OBJECT, "T_New", "", "", SOURCE_INFERRED);
            staleObject.setStatus(ST_STALE);
            ConnectorSemantic staleField = row(SCOPE_FIELD, "t_new", "AMT", "", SOURCE_INFERRED);
            staleField.setDetailJson(json(Map.of("value_domain", Map.of("complete", true))));
            givenRows(staleObject, staleField);

            ConnectorSemantic f = fresh(SCOPE_FIELD, "t_new", "amt");
            f.setAnchorKind(ANCHOR_FIELD);
            f.setAnchorHash("h");
            ConnectorSemanticService.UpsertResult r = service.upsertInferred(CONN_ID, List.of(
                    fresh(SCOPE_OBJECT, "t_new", ""), f, fresh(SCOPE_FIELD, "t_new", "qty")), List.of("t_new"));

            assertEquals(1, r.inserted());
            assertEquals(2, r.updated());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper, times(2)).update(u.capture(), w.capture());
            // 复活 + 以本次快照的大小写为准
            assertEquals(ST_DRAFT, u.getAllValues().get(0).getStatus());
            assertEquals("t_new", u.getAllValues().get(0).getObjectName());
            // ★ 旧化身的 value_domain 必须被显式覆盖掉：NOT_NULL 策略会跳过 null，只有 set 子句写得下去。
            String sqlSet = w.getAllValues().get(1).getSqlSet();
            assertTrue(sqlSet.contains("detail_json") && sqlSet.contains("anchor_hash")
                    && sqlSet.contains("confidence"), sqlSet);

            ArgumentCaptor<ConnectorSemantic> ins = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(ins.capture());
            assertEquals("qty", ins.getValue().getFieldName());
            assertEquals(SOURCE_INFERRED, ins.getValue().getSource());
            assertEquals(TENANT, ins.getValue().getTenantId());
        }

        @Test
        @DisplayName("★ HUMAN 行一个字不碰")
        void humanRowsAreNeverTouched() {
            givenRows(row(SCOPE_FIELD, "t_new", "amt", "", SOURCE_HUMAN));

            ConnectorSemanticService.UpsertResult r = service.upsertInferred(CONN_ID,
                    List.of(fresh(SCOPE_FIELD, "t_new", "AMT")), List.of("t_new"));

            assertEquals(1, r.skippedNotInferred());
            verify(semanticMapper, never()).update(any(), any());
            verify(semanticMapper, never()).insert(any());
        }

        /** 老表上的关系可能已经采样验证成 CONFIRMED，这次推导没看过那张表的完整结构，不许覆盖。 */
        @Test
        @DisplayName("★ 不在本次范围里的表上已有的 INFERRED 行保持原样")
        void existingRowsOnOtherTablesAreKept() {
            ConnectorSemantic verifiedJoin = row(SCOPE_JOIN, "orders", "cid", "", SOURCE_INFERRED);
            verifiedJoin.setVerified(V_CONFIRMED);
            givenRows(verifiedJoin);

            ConnectorSemanticService.UpsertResult r = service.upsertInferred(CONN_ID,
                    List.of(fresh(SCOPE_JOIN, "orders", "cid")), List.of("t_new"));

            assertEquals(1, r.keptExisting());
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 这张表不允许产生软删死行：唯一键不含 deleted，软删一次，下一次插入就撞键。 */
        @Test
        @DisplayName("★ 没有任何删除：物理删、软删都不走")
        void neverDeletes() {
            givenRows(row(SCOPE_OBJECT, "t_new", "", "", SOURCE_INFERRED), row(SCOPE_OBJECT, "orders", "", "", SOURCE_INFERRED));

            service.upsertInferred(CONN_ID, List.of(fresh(SCOPE_OBJECT, "t_new", "")), List.of("t_new"));

            verify(semanticMapper, never()).physicalDeleteInferred(any());
            verify(semanticMapper, never()).delete(any());
            verify(semanticMapper, never()).deleteById(any(ConnectorSemantic.class));
            verify(semanticMapper, never()).deleteBatchIds(any());
        }

        @Test
        @DisplayName("折叠规则没覆盖到、落库才撞键的那条只丢它自己")
        void duplicateKeyDropsOnlyThatRow() {
            givenRows();
            doThrow(new DuplicateKeyException("dup")).doReturn(1).when(semanticMapper).insert(any(ConnectorSemantic.class));

            ConnectorSemanticService.UpsertResult r = service.upsertInferred(CONN_ID, List.of(
                    fresh(SCOPE_FIELD, "t_new", "a"), fresh(SCOPE_FIELD, "t_new", "b")), List.of("t_new"));

            assertEquals(1, r.conflicts());
            assertEquals(1, r.inserted());
        }

        @Test
        @DisplayName("连接不存在：不读不写")
        void unknownConnection() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);
            assertThrows(ServiceException.class, () -> service.upsertInferred(CONN_ID, List.of(), List.of()));
            verify(semanticMapper, never()).selectList(any());
        }
    }

    // ================================================================ 表消失的影响面

    @Nested
    @DisplayName("表消失：口径失效、可撤销、按表报影响")
    class RemovedImpact {

        private final FieldDetail ordId = new FieldDetail("id", "bigint", false, null, null);
        private final FieldDetail refundOrd = new FieldDetail("order_id", "bigint", true, null, null);

        private ConnectorSemantic metric(String term, List<String> appliesTo) {
            ConnectorSemantic m = row(SCOPE_METRIC, "", "", term, SOURCE_HUMAN);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sql_fragment", "SUM(pay_amt) - SUM(refund_amt)");
            d.put("applies_to", appliesTo);
            m.setDetailJson(json(d));
            return m;
        }

        private Map<String, Map<String, FieldDetail>> snapshot(String... tables) {
            Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
            for (String t : tables) {
                Map<String, FieldDetail> cols = new LinkedHashMap<>();
                cols.put("id", ordId);
                cols.put("order_id", refundOrd);
                m.put(t, cols);
            }
            return m;
        }

        @Test
        @DisplayName("★ applies_to 含消失的表：口径标 STALE、词条进影响面；表回来后复活为 CONFIRMED")
        void metricHangingOnRemovedTableStalesAndRevives() {
            ConnectorSemantic sales = metric("销售额", List.of("orders", "t_refund"));
            ConnectorSemantic refundObj = row(SCOPE_OBJECT, "t_refund", "", "", SOURCE_INFERRED);
            ConnectorSemantic joinToRefund = row(SCOPE_JOIN, "orders", "id", "", SOURCE_INFERRED);
            joinToRefund.setAnchorKind(ANCHOR_JOIN);
            joinToRefund.setAnchorHash(joinAnchor(ordId, refundOrd));
            joinToRefund.setDetailJson(json(Map.of("to_object", "t_refund", "to_column", "order_id")));
            givenRows(sales, refundObj, joinToRefund);

            // 第一次刷新：t_refund 消失
            ConnectorSemanticService.StaleResult r1 = service.applyDrift(CONN_ID, snapshot("orders"), List.of("t_refund"));
            assertEquals(ST_STALE, sales.getStatus());
            assertEquals(List.of("t_refund"), detail(sales).get(KEY_STALE_REMOVED));
            assertEquals(1, r1.removedImpact().size());
            ConnectorSemanticService.ObjectStale impact = r1.removedImpact().get(0);
            assertEquals("t_refund", impact.objectName());
            assertEquals(2, impact.staledRows(), "表用途 + 指向它的关系");
            assertEquals(1, impact.staledMetrics());
            assertEquals(List.of("销售额"), impact.metricTerms());

            // 第二次刷新：diff 是空的，t_refund 仍然不在。★ 不许复活。
            ConnectorSemanticService.StaleResult r2 = service.applyDrift(CONN_ID, snapshot("orders"), List.of());
            assertEquals(0, r2.revived());
            assertEquals(ST_STALE, sales.getStatus());
            assertEquals(ST_STALE, refundObj.getStatus(), "已经不存在的表的说明不能在下一次刷新时复活");
            assertTrue(r2.removedImpact().isEmpty());

            // 第三次刷新：表回来了
            ConnectorSemanticService.StaleResult r3 = service.applyDrift(CONN_ID, snapshot("orders", "t_refund"), List.of());
            assertEquals(ST_CONFIRMED, sales.getStatus(), "人答的口径复活回 CONFIRMED");
            assertFalse(detail(sales).containsKey(KEY_STALE_REMOVED));
            assertEquals(ST_DRAFT, refundObj.getStatus());
            assertEquals(3, r3.revived());
        }

        /** applies_to 是人写的，可能写错名字。按「在不在快照里」判，一个笔误就会把人答的口径永久停用。 */
        @Test
        @DisplayName("★ applies_to 里写了快照里没有的名字，不会让口径失效")
        void typoInAppliesToNeverStales() {
            ConnectorSemantic m = metric("GMV", List.of("orders", "ordres_typo"));
            givenRows(m);

            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID, snapshot("orders"), List.of());

            assertEquals(0, r.staled());
            assertEquals(ST_CONFIRMED, m.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("没有语义挂着的消失表不进影响面（空列表 = 没有语义因表消失而失效）")
        void emptyWhenNothingHangs() {
            givenRows(row(SCOPE_OBJECT, "orders", "", "", SOURCE_INFERRED));

            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID, snapshot("orders"), List.of("t_log"));

            assertTrue(r.removedImpact().isEmpty());
        }
    }
}

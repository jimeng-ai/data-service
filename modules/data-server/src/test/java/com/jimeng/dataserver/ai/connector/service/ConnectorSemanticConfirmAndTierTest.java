package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人重新确认口径时清掉过期名单（否则下一次刷新会悄悄把它重新标过期），以及契约 C-C 的档位谓词。
 */
class ConnectorSemanticConfirmAndTierTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private Connection givenConnection(String tier) {
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        c.setSemanticDataTier(tier);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        return c;
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String s) {
        try {
            return CommonUtil.getObjectMapper().readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ 口径重新确认

    @Nested
    @DisplayName("人重新确认口径：清掉过期名单")
    class Reconfirm {

        private ConnectorSemantic staleMetric() {
            ConnectorSemantic m = new ConnectorSemantic();
            m.setId(7L);
            m.setTenantId(TENANT);
            m.setConnectorId(CONN_ID);
            m.setScope(SCOPE_METRIC);
            m.setObjectName("");
            m.setFieldName("");
            m.setTerm("销售额");
            m.setGloss("实付金额合计，扣退款");
            m.setSource(SOURCE_HUMAN);
            m.setStatus(ST_STALE);
            m.setVerified(V_NONE);
            m.setAnchorKind(ANCHOR_NONE);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sql_fragment", "SUM(pay_amt) - SUM(refund_amt)");
            d.put("applies_to", List.of("orders", "t_refund"));
            d.put(KEY_STALE_REMOVED, List.of("t_refund"));
            m.setDetailJson(json(d));
            return m;
        }

        /**
         * ★ 需求里点名的那一种：模型重新确认口径时往往只补一句话、不重发 detail。
         * 名单若留着，下一次刷新看到 t_refund 仍然不在，就把人刚确认的口径悄悄重新标 STALE、停止注入。
         */
        @Test
        @DisplayName("★ 不传 detail 也清掉过期名单，其余键原样保留，旧名单进留痕")
        void reconfirmWithoutDetailClearsStaleList() {
            givenConnection(null);
            ConnectorSemantic m = staleMetric();
            String oldDetail = m.getDetailJson();
            when(semanticMapper.selectOne(any())).thenReturn(m);

            service.defineMetric(CONN_ID, "销售额", "实付金额合计，扣退款（已确认 t_refund 下线）", null, "u9", "张三", "tr");

            assertEquals(ST_CONFIRMED, m.getStatus());
            Map<String, Object> d = parse(m.getDetailJson());
            assertFalse(d.containsKey(KEY_STALE_REMOVED), m.getDetailJson());
            assertEquals("SUM(pay_amt) - SUM(refund_amt)", d.get("sql_fragment"));
            assertEquals(List.of("orders", "t_refund"), d.get("applies_to"));
            assertTrue(m.getHistoryJson().contains("from_detail"));
            assertTrue(String.valueOf(((Map<?, ?>) CommonUtil.getObjectMapper().convertValue(
                    parseList(m.getHistoryJson()).get(0), Map.class)).get("from_detail")).equals(oldDetail),
                    "旧名单在旧 detail 里，整份进留痕，取证材料不丢");
            verify(semanticMapper).update(any(), any());
        }

        @Test
        @DisplayName("传进来的 detail 里带着过期名单：拿掉（名单只该由漂移处置写）")
        void reconfirmWithDetailStripsStaleList() {
            givenConnection(null);
            ConnectorSemantic m = staleMetric();
            when(semanticMapper.selectOne(any())).thenReturn(m);
            Map<String, Object> passed = new LinkedHashMap<>(parse(m.getDetailJson()));

            service.defineMetric(CONN_ID, "销售额", "新口径", passed, "u9", "张三", null);

            assertFalse(parse(m.getDetailJson()).containsKey(KEY_STALE_REMOVED));
            assertTrue(passed.containsKey(KEY_STALE_REMOVED), "不改调用方传进来的 Map");
        }

        @Test
        @DisplayName("新建口径时 detail 里带着过期名单：同样拿掉")
        void newMetricStripsStaleList() {
            givenConnection(null);
            when(semanticMapper.selectOne(any())).thenReturn(null);

            service.defineMetric(CONN_ID, "退款额", "退款金额合计",
                    Map.of("applies_to", List.of("t_refund"), KEY_STALE_REMOVED, List.of("t_refund")), "u9", "张三", null);

            ArgumentCaptor<ConnectorSemantic> ins = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(ins.capture());
            assertFalse(parse(ins.getValue().getDetailJson()).containsKey(KEY_STALE_REMOVED));
        }

        /** ★ 端到端：确认之后那张表仍然不在、它的行早已过期——下一次刷新不许把口径重新拖下水。 */
        @Test
        @DisplayName("★ 确认之后的下一次刷新：表仍然不在，口径保持 CONFIRMED")
        void nextRefreshDoesNotReStaleConfirmedMetric() {
            givenConnection(null);
            ConnectorSemantic m = staleMetric();
            when(semanticMapper.selectOne(any())).thenReturn(m);
            service.defineMetric(CONN_ID, "销售额", "已确认", null, "u9", "张三", null);

            ConnectorSemantic refundObj = new ConnectorSemantic();
            refundObj.setId(8L);
            refundObj.setScope(SCOPE_OBJECT);
            refundObj.setObjectName("t_refund");
            refundObj.setFieldName("");
            refundObj.setSource(SOURCE_INFERRED);
            refundObj.setStatus(ST_STALE);
            refundObj.setAnchorKind(ANCHOR_NONE);
            when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(m, refundObj)));
            Map<String, Map<String, FieldDetail>> snap = new LinkedHashMap<>();
            snap.put("orders", Map.of("id", new FieldDetail("id", "bigint", false, null, null)));

            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID, snap,
                    SnapshotPresence.of(List.of("orders"), List.of("orders"), false), List.of());

            assertEquals(ST_CONFIRMED, m.getStatus());
            assertEquals(0, r.staled());
            assertTrue(r.removedImpact().isEmpty());
        }

        private List<?> parseList(String s) {
            try {
                return CommonUtil.getObjectMapper().readValue(s, List.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ================================================================ 档位谓词（契约 C-C）

    @Nested
    @DisplayName("allowsSampleValues：只有显式开到第 3 档才允许，任何失败都不允许")
    class SampleValuesTier {

        @Test
        @DisplayName("显式 SAMPLE_VALUES → 允许")
        void explicitSampleValuesTier() {
            givenConnection("SAMPLE_VALUES");
            assertTrue(service.allowsSampleValues(CONN_ID));
        }

        /** 空值是「没选过」→ 默认第 2 档；认不出来 → 最严档。两条兜底都到不了第 3 档。 */
        @Test
        @DisplayName("空值、第 1 / 2 档、认不出来的值 → 不允许")
        void everyOtherTierIsDenied() {
            for (String tier : new String[]{null, "", "DERIVED_STATS", "METADATA_ONLY", "SAMPLE-VALUES", "3"}) {
                givenConnection(tier);
                assertFalse(service.allowsSampleValues(CONN_ID), "档位 " + tier + " 不该允许真实取值出库");
            }
        }

        @Test
        @DisplayName("连接不存在 → 不允许，且不抛")
        void missingConnectionIsDenied() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);
            assertFalse(service.allowsSampleValues(CONN_ID));
        }

        /** ★ fail-closed：库抖了答「允许」，客户的真实取值会在他没授权的档位上进提示词，而且没人发现。 */
        @Test
        @DisplayName("★ 读库失败 → 不允许，且不抛")
        void mapperFailureIsDenied() {
            when(connectionMapper.selectById(anyLong())).thenThrow(new RuntimeException("连接池满了"));
            assertFalse(service.allowsSampleValues(CONN_ID));
        }

        @Test
        @DisplayName("id 为空 → 不允许，不查库")
        void nullIdIsDenied() {
            assertFalse(service.allowsSampleValues(null));
            verify(connectionMapper, never()).selectById(any());
        }
    }
}

package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
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

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.EV_DATA;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.EV_NAME;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.JOIN_VERDICT_KEYS;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.KEY_PROBED_WITH_SAMPLE_VALUES;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.PURGED_VALUE_GLOSS;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.PURGED_VALUE_NOTE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_METRIC;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_OBJECT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_HUMAN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_INFERRED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_CONFIRMED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_DRAFT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_CONFIRMED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.isValueProfileRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * 真实取值的删除（K-5）、增量写入的保留规则（条目 8）、口径沉淀撞上并发写（条目 3）。
 *
 * <p>这三件事坏了都不报错：客户收回第 3 档之后他的取值仍在我们库里；一次增量推导把测过的表形态、探过的判别值记号冲掉，
 * 下一轮再去客户库量一遍；两个人同时确认一条口径，其中一条回答从留痕里消失。
 */
class ConnectorSemanticPurgeAndMergeTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private static final String CARE_WITH_VALUE = "多态外键：role_resource.resource_id 只有在 role_resource.resource_type = 'AGENT' "
            + "时才指向 agent.id。关联时必须带上这个条件，否则指向别的表的行也会被连上，而且不报错。";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;
    private long seq = 1;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        givenTier(null);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private void givenTier(String tier) {
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        c.setSemanticDataTier(tier);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
    }

    // ================================================================ 夹具

    private ConnectorSemantic row(String scope, String obj, String field, String source, String evidence) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setSource(source);
        r.setEvidence(evidence);
        r.setStatus(ST_DRAFT);
        r.setVerified(V_NONE);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
    }

    private static Map<String, Object> enumerated(List<?> values) {
        Map<String, Object> vd = new LinkedHashMap<>();
        vd.put("complete", true);
        vd.put("outcome", "ENUMERATED");
        vd.put("distinct_count", values.size());
        vd.put("values", values);
        vd.put("note", "以下是这一列的全部取值");
        return vd;
    }

    private static Map<String, Object> polymorphicDetail() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", "agent");
        d.put("to_column", "id");
        d.put("basis", "按 resource_type 的取值分组采样");
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        d.put("discriminator_value", "AGENT");
        d.put("care_reason", CARE_WITH_VALUE);
        d.put(KEY_PROBED_WITH_SAMPLE_VALUES, true);
        return d;
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Object json) {
        try {
            return CommonUtil.getObjectMapper().readValue(String.valueOf(json), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    /** 一次回写：实体（NOT_NULL 列）与 set 子句（显式写的列）。 */
    private record Written(ConnectorSemantic entity, LambdaUpdateWrapper<ConnectorSemantic> wrapper) {

        boolean sets(String column) {
            for (String part : wrapper.getSqlSet().split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(column)) {
                    return true;
                }
            }
            return false;
        }

        Object set(String column) {
            for (String part : wrapper.getSqlSet().split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(column)) {
                    String ph = part.substring(eq + 1).trim();
                    String key = ph.substring(ph.lastIndexOf('.') + 1, ph.length() - 1);
                    return wrapper.getParamNameValuePairs().get(key);
                }
            }
            throw new AssertionError("set 子句里没有 " + column + "：" + wrapper.getSqlSet());
        }

        Map<String, Object> detail() {
            return parse(set("detail_json"));
        }

        /** 实体上或 set 子句里的 gloss（增量写入放在实体上，删取值放在 set 子句里）。 */
        String gloss() {
            if (entity != null && entity.getGloss() != null) {
                return entity.getGloss();
            }
            return sets("gloss") ? (String) set("gloss") : null;
        }
    }

    @SuppressWarnings("unchecked")
    private Written onlyWrite() {
        ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper).update(u.capture(), w.capture());
        return new Written(u.getValue(), w.getValue());
    }

    // ================================================================ K-5 删除真实取值

    @Nested
    @DisplayName("purgeSampleValues：档位下调后删掉库里的真实取值")
    class Purge {

        @Test
        @DisplayName("★ 值域：取值列表拿掉，片段留成「不完整 + 为什么」，distinct_count 留着；模型写的 gloss 不动")
        void valueListsAreRemovedFragmentStays() {
            ConnectorSemantic f = row(SCOPE_FIELD, "orders", "status", SOURCE_INFERRED, EV_NAME);
            f.setGloss("订单状态码");
            f.setDetailJson(json(Map.of("value_domain", enumerated(List.of("0", "1", "2")), "note", "含义需确认")));
            givenRows(f);

            assertEquals(1, service.purgeSampleValues(CONN_ID));

            Written w = onlyWrite();
            Map<String, Object> d = w.detail();
            @SuppressWarnings("unchecked")
            Map<String, Object> vd = (Map<String, Object>) d.get("value_domain");
            assertNotNull(vd, "整段删掉会让注入层说不出「不要写死条件」");
            assertFalse(vd.containsKey("values"));
            assertEquals(false, vd.get("complete"));
            assertEquals(3, vd.get("distinct_count"));
            assertEquals(PURGED_VALUE_NOTE, vd.get("note"));
            assertEquals("含义需确认", d.get("note"));
            assertFalse(w.sets("gloss"), "模型写的 gloss 不带取值，不该改");
        }

        /** ★ 注入层判 gloss 会不会带出取值看的是值域里还有没有取值：两件事必须在同一条 UPDATE 里。 */
        @Test
        @DisplayName("★ 值域阶段补写的行（带标记 / 存量 DATA 行）：gloss 与值域在同一条 UPDATE 里改")
        void valueProfileRowGlossIsNeutralisedInSameUpdate() {
            ConnectorSemantic marked = row(SCOPE_FIELD, "orders", "st", SOURCE_INFERRED, EV_DATA);
            marked.setGloss("取值只有这 3 种：待支付、已支付、已退款。");
            marked.setDetailJson(json(Map.of("origin", "value_profile", "value_domain", enumerated(List.of("待支付", "已支付", "已退款")))));
            givenRows(marked);

            service.purgeSampleValues(CONN_ID);

            Written w = onlyWrite();
            assertTrue(w.sets("detail_json") && w.sets("gloss"), w.wrapper().getSqlSet());
            assertEquals(PURGED_VALUE_GLOSS, w.set("gloss"));
            assertFalse(String.valueOf(w.set("gloss")).contains("待支付"));
        }

        @Test
        @DisplayName("没有标记的存量行按 INFERRED + FIELD + DATA 且带着 value_domain 键认")
        void legacyValueRowIsRecognised() {
            ConnectorSemantic legacy = row(SCOPE_FIELD, "orders", "st", SOURCE_INFERRED, EV_DATA);
            legacy.setGloss("取值只有这 2 种：A、B。");
            legacy.setDetailJson(json(Map.of("value_domain", enumerated(List.of("A", "B")))));
            givenRows(legacy);

            service.purgeSampleValues(CONN_ID);

            assertEquals(PURGED_VALUE_GLOSS, onlyWrite().set("gloss"));
        }

        @Test
        @DisplayName("★ 判别值：拿掉；care_reason 里的取值换成占位；探查记号置 false 让下次开第 3 档能重探")
        void discriminatorValueIsRemoved() {
            ConnectorSemantic j = row(SCOPE_JOIN, "role_resource", "resource_id", SOURCE_INFERRED, EV_NAME);
            j.setVerified(V_CONFIRMED);
            j.setGloss("关联 agent.id。已通过采样验证。");
            j.setDetailJson(json(polymorphicDetail()));
            givenRows(j);

            service.purgeSampleValues(CONN_ID);

            Map<String, Object> d = onlyWrite().detail();
            assertFalse(d.containsKey("discriminator_value"));
            String care = String.valueOf(d.get("care_reason"));
            assertFalse(care.contains("AGENT"), care);
            assertTrue(care.contains("resource_type") && care.contains("<代表 agent 的取值"), care);
            assertEquals(false, d.get(KEY_PROBED_WITH_SAMPLE_VALUES));
            assertEquals("POLYMORPHIC", d.get("join_kind"), "结构判定本身不是取值，留着");
        }

        @Test
        @DisplayName("care_reason 里的取值不是按 '取值' 写的：整句换成不带取值的警告")
        void unquotedValueFallsBackToNeutralWarning() {
            ConnectorSemantic j = row(SCOPE_JOIN, "role_resource", "resource_id", SOURCE_INFERRED, EV_NAME);
            Map<String, Object> d0 = polymorphicDetail();
            d0.put("care_reason", "判别值 AGENT 对应 agent 表");
            j.setDetailJson(json(d0));
            givenRows(j);

            service.purgeSampleValues(CONN_ID);

            String care = String.valueOf(onlyWrite().detail().get("care_reason"));
            assertFalse(care.contains("AGENT"), care);
            assertTrue(care.startsWith("多态外键：role_resource.resource_id"), care);
        }

        @Test
        @DisplayName("人写的行上的值域同样删：取值不因为是谁写的就可以留")
        void humanRowValuesAreRemovedToo() {
            ConnectorSemantic h = row(SCOPE_FIELD, "orders", "status", SOURCE_HUMAN, EV_NAME);
            h.setGloss("状态");
            h.setDetailJson(json(Map.of("value_domain", enumerated(List.of("x")))));
            givenRows(h);

            assertEquals(1, service.purgeSampleValues(CONN_ID));
            assertFalse(onlyWrite().sets("gloss"));
        }

        @Test
        @DisplayName("已经删干净的行不再写")
        void alreadyPurgedRowIsNotWritten() {
            ConnectorSemantic f = row(SCOPE_FIELD, "orders", "status", SOURCE_INFERRED, EV_NAME);
            Map<String, Object> vd = new LinkedHashMap<>();
            vd.put("complete", false);
            vd.put("outcome", "ENUMERATED");
            vd.put("note", PURGED_VALUE_NOTE);
            f.setDetailJson(json(Map.of("value_domain", vd)));
            // 删过一次的存量值域行：值域片段已中性、gloss 已换。它必须仍带着值域键，才仍是值域行（判据见 isValueProfileRow）。
            ConnectorSemantic p = row(SCOPE_FIELD, "orders", "st", SOURCE_INFERRED, EV_DATA);
            p.setGloss(PURGED_VALUE_GLOSS);
            p.setDetailJson(json(Map.of("value_domain", vd)));
            givenRows(f, p);

            assertEquals(0, service.purgeSampleValues(CONN_ID));
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("★ 被别处抢先改写的行重读重来；回写带着读到时的样子为条件")
        void conflictedRowIsRetriedWithFreshRead() {
            when(semanticMapper.update(any(), any())).thenReturn(0, 1);
            ConnectorSemantic f = row(SCOPE_FIELD, "orders", "status", SOURCE_INFERRED, EV_NAME);
            f.setDetailJson(json(Map.of("value_domain", enumerated(List.of("0")))));
            givenRows(f);

            assertEquals(1, service.purgeSampleValues(CONN_ID));

            verify(semanticMapper, times(2)).selectList(any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(semanticMapper, times(2)).update(any(), w.capture());
            assertTrue(w.getValue().getSqlSegment().contains("update_time"), w.getValue().getSqlSegment());
        }

        @Test
        @DisplayName("解析不了、却写着取值键的 detail：整份不要")
        void corruptDetailMentioningValuesIsDropped() {
            ConnectorSemantic f = row(SCOPE_FIELD, "orders", "status", SOURCE_INFERRED, EV_NAME);
            f.setDetailJson("{\"value_domain\": {\"values\": [\"13800000000\"");
            givenRows(f);

            service.purgeSampleValues(CONN_ID);

            assertEquals("{}", onlyWrite().set("detail_json"));
        }

        /**
         * ★ 回归：推导提示词让模型给「从类型 / 约束读得出来」的字段说明标 DATA，这类行 detail 为空或不带值域键。
         * 默认档（setUp 里档位为 null）每次结构刷新都跑删取值；从前按 INFERRED + FIELD + DATA 认，
         * 这些说明被整句换成「曾在第 3 档采集过」的假话，INFERRED 行不留痕，永久丢失。
         */
        @Test
        @DisplayName("★ 回归：默认档上模型标 DATA 的字段说明（detail 为空 / 空对象 / 不带值域键）删取值时一个字不动")
        void modelDataRowWithoutValueDomainIsUntouched() {
            ConnectorSemantic noDetail = row(SCOPE_FIELD, "orders", "id", SOURCE_INFERRED, EV_DATA);
            noDetail.setGloss("自增主键，一行一个订单");
            ConnectorSemantic emptyDetail = row(SCOPE_FIELD, "orders", "amount", SOURCE_INFERRED, EV_DATA);
            emptyDetail.setGloss("decimal(12,2)，金额");
            emptyDetail.setDetailJson("{}");
            ConnectorSemantic otherKeys = row(SCOPE_FIELD, "orders", "paid_at", SOURCE_INFERRED, EV_DATA);
            otherKeys.setGloss("可空的时间列，为空表示尚未支付");
            otherKeys.setDetailJson("{\"note\":\"从可空约束读出\"}");
            givenRows(noDetail, emptyDetail, otherKeys);

            assertEquals(0, service.purgeSampleValues(CONN_ID));

            verify(semanticMapper, never()).update(any(), any());
            assertEquals("自增主键，一行一个订单", noDetail.getGloss());
            assertNull(noDetail.getDetailJson());
            assertEquals("{}", emptyDetail.getDetailJson());
            assertEquals("{\"note\":\"从可空约束读出\"}", otherKeys.getDetailJson());
        }

        @Test
        @DisplayName("★ 带标记的行不看依据、不看有没有值域键：照样换 gloss，detail 没有取值就不动")
        void markerRowIsPurgedWithoutValueDomain() {
            ConnectorSemantic marked = row(SCOPE_FIELD, "orders", "region", SOURCE_INFERRED, EV_NAME);
            marked.setGloss("取值只有这 2 种：华东大区、华南大区");
            marked.setDetailJson(json(Map.of("origin", "value_profile")));
            givenRows(marked);

            assertEquals(1, service.purgeSampleValues(CONN_ID));

            Written w = onlyWrite();
            assertEquals(PURGED_VALUE_GLOSS, w.set("gloss"));
            assertFalse(w.sets("detail_json"), w.wrapper().getSqlSet());
        }

        @Test
        @DisplayName("存量 DATA 行的 detail 解析不了：按带着值域键算，detail 与 gloss 在同一条 UPDATE 里换掉")
        void corruptLegacyDataRowIsPurged() {
            ConnectorSemantic f = row(SCOPE_FIELD, "orders", "st", SOURCE_INFERRED, EV_DATA);
            f.setGloss("取值只有这 2 种：A、B。");
            f.setDetailJson("{\"value_domain\": {\"values\": [\"A\"");
            givenRows(f);

            service.purgeSampleValues(CONN_ID);

            Written w = onlyWrite();
            assertEquals("{}", w.set("detail_json"));
            assertEquals(PURGED_VALUE_GLOSS, w.set("gloss"));
        }

        @Test
        @DisplayName("连接不存在：不读不写")
        void unknownConnection() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);
            assertThrows(ServiceException.class, () -> service.purgeSampleValues(CONN_ID));
            verify(semanticMapper, never()).selectList(any());
        }
    }

    // ================================================================ 条目 8：增量写入的保留规则

    @Nested
    @DisplayName("增量写入：档位与测量留痕")
    class MergeKeeps {

        private ConnectorSemantic freshJoin() {
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_JOIN);
            f.setObjectName("role_resource");
            f.setFieldName("resource_id");
            f.setTerm("");
            f.setGloss("关联 agent.id。本条未经数据验证。");
            f.setEvidence(EV_NAME);
            f.setStatus(ST_DRAFT);
            f.setVerified(V_NONE);
            f.setAnchorKind(ANCHOR_JOIN);
            f.setAnchorHash("h-j");
            f.setDetailJson(json(Map.of("to_object", "agent", "to_column", "id", "basis", "未经数据验证")));
            return f;
        }

        private ConnectorSemantic verifiedPolymorphic() {
            ConnectorSemantic old = row(SCOPE_JOIN, "role_resource", "resource_id", SOURCE_INFERRED, EV_NAME);
            old.setVerified(V_CONFIRMED);
            old.setGloss("关联 agent.id。已通过采样验证。");
            old.setAnchorKind(ANCHOR_JOIN);
            old.setAnchorHash("h-j");
            old.setDetailJson(json(polymorphicDetail()));
            return old;
        }

        @Test
        @DisplayName("★ 探查记号跟着结论走（第 3 档）：判别值与 probed_with_sample_values 都留着")
        void probedFlagTravelsWithVerdict() {
            assertTrue(JOIN_VERDICT_KEYS.contains(KEY_PROBED_WITH_SAMPLE_VALUES));
            givenTier("SAMPLE_VALUES");
            givenRows(verifiedPolymorphic());

            service.upsertInferred(CONN_ID, List.of(freshJoin()), List.of("role_resource"));

            Map<String, Object> d = onlyWrite().detail();
            assertEquals(true, d.get(KEY_PROBED_WITH_SAMPLE_VALUES));
            assertEquals("AGENT", d.get("discriminator_value"));
        }

        @Test
        @DisplayName("★ 档位不允许真实取值：判别值不跟着留，care_reason 不带取值，记号置 false")
        void discriminatorIsScrubbedOnMergeBelowTier3() {
            givenTier("DERIVED_STATS");
            givenRows(verifiedPolymorphic());

            service.upsertInferred(CONN_ID, List.of(freshJoin()), List.of("role_resource"));

            Written w = onlyWrite();
            Map<String, Object> d = w.detail();
            assertFalse(d.containsKey("discriminator_value"));
            assertFalse(String.valueOf(d.get("care_reason")).contains("AGENT"));
            assertEquals(false, d.get(KEY_PROBED_WITH_SAMPLE_VALUES));
            assertEquals(V_CONFIRMED, w.entity().getVerified(), "结论本身留着");
        }

        @Test
        @DisplayName("★ 值域只在允许真实取值时留；第 2 档不留")
        void valueDomainIsNotCarriedBelowTier3() {
            givenTier("DERIVED_STATS");
            ConnectorSemantic old = row(SCOPE_FIELD, "t_new", "status", SOURCE_INFERRED, EV_NAME);
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-col");
            old.setDetailJson(json(Map.of("value_domain", enumerated(List.of("0", "1")))));
            givenRows(old);
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_FIELD);
            f.setObjectName("t_new");
            f.setFieldName("status");
            f.setGloss("状态码");
            f.setEvidence(EV_NAME);
            f.setAnchorKind(ANCHOR_FIELD);
            f.setAnchorHash("h-col");
            f.setDetailJson(json(Map.of("note", "含义需确认")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            assertFalse(onlyWrite().detail().containsKey("value_domain"));
        }

        /** ★ 依据强的旧行会被原样留下，而值域阶段补写的行恰恰是 DATA 依据：gloss 里就列着取值。 */
        @Test
        @DisplayName("★ 旧行依据强被原样留下时，档位不允许就把 gloss 与值域里的取值剥掉")
        void strongEvidenceValueRowIsScrubbedBelowTier3() {
            givenTier(null);
            ConnectorSemantic old = row(SCOPE_FIELD, "t_ord", "st", SOURCE_INFERRED, EV_DATA);
            old.setGloss("取值只有这 3 种：0、1、2。（采样时点的完整集合）");
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-st");
            old.setDetailJson(json(Map.of("value_domain", enumerated(List.of("0", "1", "2")))));
            givenRows(old);
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_FIELD);
            f.setObjectName("t_ord");
            f.setFieldName("st");
            f.setGloss("状态码");
            f.setEvidence(EV_NAME);
            f.setAnchorKind(ANCHOR_FIELD);
            f.setAnchorHash("h-st");

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_ord"));

            Written w = onlyWrite();
            assertEquals(PURGED_VALUE_GLOSS, w.gloss());
            @SuppressWarnings("unchecked")
            Map<String, Object> vd = (Map<String, Object>) w.detail().get("value_domain");
            assertFalse(vd.containsKey("values"));
            assertEquals(false, vd.get("complete"));
        }

        /** 名字像的新推断，锚点与旧行相同：撞上依据强的旧行就走规则 ②（旧行原样留下）。 */
        private ConnectorSemantic freshNameField(String obj, String field, String anchorHash) {
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_FIELD);
            f.setObjectName(obj);
            f.setFieldName(field);
            f.setTerm("");
            f.setGloss("名字像的说明");
            f.setEvidence(EV_NAME);
            f.setAnchorKind(ANCHOR_FIELD);
            f.setAnchorHash(anchorHash);
            return f;
        }

        /**
         * ★ 回归：模型看结构写、标了 DATA 的说明行被规则 ② 原样留下时，默认档的剥取值那一步从前按 INFERRED + FIELD + DATA
         * 把它当成值域行，gloss 换成「曾在第 3 档采集过」的假话。现在它不带标记、不带值域键，必须与允许取值时的合并结果逐字相同。
         */
        @Test
        @DisplayName("★ 回归：模型标 DATA、没有值域键的说明行，默认档增量写入把它原样留下时一个字不改")
        void modelDataRowSurvivesMergeBelowTier3() {
            givenTier(null);
            for (String detail : java.util.Arrays.asList(null, "{}", "{\"note\":\"从自增约束读出\"}")) {
                ConnectorSemantic old = row(SCOPE_FIELD, "orders", "id", SOURCE_INFERRED, EV_DATA);
                old.setGloss("自增主键，一行一个订单");
                old.setAnchorKind(ANCHOR_FIELD);
                old.setAnchorHash("h-id");
                old.setDetailJson(detail);
                ConnectorSemantic fresh = freshNameField("orders", "id", "h-id");

                ConnectorSemanticService.Merged below = service.mergeInferred(old, fresh, false);

                assertEquals(service.mergeInferred(old, fresh, true), below, "detail=" + detail);
                assertEquals("自增主键，一行一个订单", below.gloss(), "detail=" + detail);
                assertEquals(detail, below.detailJson());
            }

            ConnectorSemantic old = row(SCOPE_FIELD, "orders", "id", SOURCE_INFERRED, EV_DATA);
            old.setGloss("自增主键，一行一个订单");
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-id");
            givenRows(old);

            service.upsertInferred(CONN_ID, List.of(freshNameField("orders", "id", "h-id")), List.of("orders"));

            Written w = onlyWrite();
            assertEquals("自增主键，一行一个订单", w.entity().getGloss());
            assertNull(w.set("detail_json"));
        }

        @Test
        @DisplayName("★ 带标记、没有值域键的旧行被原样留下：档位不允许照样换 gloss")
        void markerRowIsNeutralisedOnMergeBelowTier3() {
            ConnectorSemantic old = row(SCOPE_FIELD, "t_ord", "region", SOURCE_INFERRED, EV_DATA);
            old.setGloss("取值只有这 2 种：华东大区、华南大区");
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-r");
            old.setDetailJson(json(Map.of("origin", "value_profile")));

            ConnectorSemanticService.Merged m = service.mergeInferred(old, freshNameField("t_ord", "region", "h-r"), false);

            assertEquals(PURGED_VALUE_GLOSS, m.gloss());
            assertEquals(old.getDetailJson(), m.detailJson());
        }

        @Test
        @DisplayName("旧行 detail 解析不了、写着取值键：换成 {} 的同一次写里 gloss 一起换掉")
        void corruptDetailOnMergeTakesGlossWithIt() {
            ConnectorSemantic old = row(SCOPE_FIELD, "t_ord", "st", SOURCE_INFERRED, EV_DATA);
            old.setGloss("取值只有这 2 种：A、B。");
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-st");
            old.setDetailJson("{\"value_domain\": {\"values\": [\"A\"");

            ConnectorSemanticService.Merged m = service.mergeInferred(old, freshNameField("t_ord", "st", "h-st"), false);

            assertEquals("{}", m.detailJson());
            // 只换 detail 的话，写下去的 {} 之后谁都认不出是值域行，这句列着取值的 gloss 就永远留着。
            assertEquals(PURGED_VALUE_GLOSS, m.gloss());
        }

        /** ★ 「测过、不是键值对表」同样打在客户库上。丢了它，推导类当它没测过，下一轮再量一遍。 */
        @Test
        @DisplayName("★ 模型来源的表用途行：测量留痕照样留，锚点写法不同也不丢")
        void measurementIsKeptWhateverTheSource() {
            ConnectorSemantic old = row(SCOPE_OBJECT, "metric_kv", "", SOURCE_INFERRED, EV_NAME);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put(TableShape.KEY_SHAPE, "DETAIL");
            d.put(TableShape.KEY_SOURCE, TableShape.SOURCE_MODEL);
            d.put(TableShape.KEY_MEASUREMENT, Map.of("outcome", "NOT_KEY_VALUE", "basis", "名列取值 3 种，单位不混存"));
            old.setDetailJson(json(d));
            givenRows(old);
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_OBJECT);
            f.setObjectName("metric_kv");
            f.setGloss("指标流水");
            f.setEvidence(EV_NAME);
            f.setAnchorKind(ANCHOR_NONE);
            f.setAnchorHash("whatever");
            f.setDetailJson(json(Map.of(TableShape.KEY_SHAPE, "DETAIL", TableShape.KEY_SOURCE, TableShape.SOURCE_MODEL)));

            service.upsertInferred(CONN_ID, List.of(f), List.of("metric_kv"));

            Written w = onlyWrite();
            assertEquals("指标流水", w.entity().getGloss());
            assertEquals(Map.of("outcome", "NOT_KEY_VALUE", "basis", "名列取值 3 种，单位不混存"),
                    w.detail().get(TableShape.KEY_MEASUREMENT));
        }

        @Test
        @DisplayName("★ 读与写之间被别处改过：不覆盖，计进 concurrentSkips；回写条件里带着更新时间")
        void rowChangedSinceReadIsSkipped() {
            when(semanticMapper.update(any(), any())).thenReturn(0);
            givenRows(row(SCOPE_OBJECT, "t_new", "", SOURCE_INFERRED, EV_NAME));
            ConnectorSemantic f = new ConnectorSemantic();
            f.setScope(SCOPE_OBJECT);
            f.setObjectName("t_new");
            f.setGloss("新表");
            f.setEvidence(EV_NAME);

            ConnectorSemanticService.UpsertResult r = service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            assertEquals(0, r.updated());
            assertEquals(1, r.concurrentSkips());
            String where = onlyWrite().wrapper().getSqlSegment();
            assertTrue(where.contains("update_time") && where.contains("source") && where.contains("detail_json"), where);
        }

        @Test
        @DisplayName("本类的探查记号与推导类是同一个字符串")
        void probedKeyMatchesDeriveService() {
            assertEquals(ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES, KEY_PROBED_WITH_SAMPLE_VALUES);
        }
    }

    // ================================================================ 值域行的唯一判据

    @Nested
    @DisplayName("isValueProfileRow：值域行的唯一判据（删取值、增量写入、注入路径共用）")
    class ValueProfileRule {

        private ConnectorSemantic r(String scope, String source, String evidence, String detailJson) {
            ConnectorSemantic x = row(scope, "orders", "st", source, evidence);
            x.setDetailJson(detailJson);
            return x;
        }

        @Test
        @DisplayName("带标记：不看来源、依据、有没有值域键")
        void markerWinsWhateverTheSource() {
            String marker = "{\"origin\":\"value_profile\"}";
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, marker)));
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_NAME, marker)));
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_HUMAN, EV_NAME, marker)));
        }

        @Test
        @DisplayName("★ 存量行：INFERRED + FIELD + DATA 且带着 value_domain 键（值是什么不论；detail 读不出来算带着）")
        void legacyRowNeedsValueDomainKey() {
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA,
                    json(Map.of("value_domain", enumerated(List.of("A")))))));
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, "{\"value_domain\":null}")));
            assertTrue(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, "{\"value_domain\": {\"values\": [\"A\"")));
        }

        @Test
        @DisplayName("★ 回归：模型标 DATA、没有标记也没有值域键的说明行不是值域行")
        void modelDataRowIsNot() {
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, null)));
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, "")));
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, "{}")));
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_DATA, "{\"note\":\"主键\"}")));
            assertFalse(isValueProfileRow(null));
        }

        @Test
        @DisplayName("带着 value_domain、但不是 INFERRED + FIELD + DATA：不是（人写的、名字像的、关系行）")
        void valueDomainAloneIsNotEnough() {
            String vd = json(Map.of("value_domain", enumerated(List.of("A"))));
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_HUMAN, EV_DATA, vd)));
            assertFalse(isValueProfileRow(r(SCOPE_FIELD, SOURCE_INFERRED, EV_NAME, vd)));
            assertFalse(isValueProfileRow(r(SCOPE_JOIN, SOURCE_INFERRED, EV_DATA, vd)));
        }
    }

    // ================================================================ 条目 3：口径沉淀撞上并发写

    @Nested
    @DisplayName("口径沉淀：写回撞上别处的写")
    class DefineMetricConflicts {

        private ConnectorSemantic existing(String gloss, String historyJson) {
            ConnectorSemantic e = row(SCOPE_METRIC, "", "", SOURCE_HUMAN, "GUESS");
            e.setTerm("销售额");
            e.setGloss(gloss);
            e.setStatus(ST_CONFIRMED);
            e.setHistoryJson(historyJson);
            return e;
        }

        private List<?> history(ConnectorSemantic e) {
            try {
                return CommonUtil.getObjectMapper().readValue(e.getHistoryJson(), List.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        /** ★ 两个人同时确认同一个词条：后写的那个要在新的旧值上留痕，前一个人的回答不能从留痕里消失。 */
        @Test
        @DisplayName("★ 写回影响 0 行：重读，在新的旧值上再留一次痕")
        void retriesOnFreshReadKeepingBothAnswers() {
            ConnectorSemantic firstRead = existing("最早的一版", null);
            ConnectorSemantic reread = existing("别人刚答的", json(List.of(Map.of("from_gloss", "最早的一版"))));
            when(semanticMapper.selectOne(any())).thenReturn(firstRead, reread);
            when(semanticMapper.update(any(), any())).thenReturn(0, 1);

            ConnectorSemantic out = service.defineMetric(CONN_ID, "销售额", "我答的", null, "u9", "张三", "tr");

            assertSame(reread, out);
            List<?> hist = history(out);
            assertEquals(2, hist.size());
            assertEquals("别人刚答的", ((Map<?, ?>) hist.get(1)).get("from_gloss"));
            assertEquals("我答的", out.getGloss());
            verify(semanticMapper, times(2)).update(any(), any());
        }

        @Test
        @DisplayName("插入撞唯一键：重读，按覆盖处理，不让这一次回答报错消失")
        void duplicateInsertFallsBackToOverwrite() {
            ConnectorSemantic justInserted = existing("另一次确认", null);
            when(semanticMapper.selectOne(any())).thenReturn(null, justInserted);
            doThrow(new DuplicateKeyException("dup")).when(semanticMapper).insert(any(ConnectorSemantic.class));

            ConnectorSemantic out = service.defineMetric(CONN_ID, "销售额", "我答的", null, "u9", "张三", null);

            assertSame(justInserted, out);
            assertEquals("另一次确认", ((Map<?, ?>) history(out).get(0)).get("from_gloss"));
        }

        @Test
        @DisplayName("反复写不进去：报错，不假装记住了")
        void givesUpLoudly() {
            when(semanticMapper.selectOne(any())).thenReturn(existing("旧", null));
            when(semanticMapper.update(any(), any())).thenReturn(0);

            assertThrows(ServiceException.class,
                    () -> service.defineMetric(CONN_ID, "销售额", "我答的", null, "u9", "张三", null));
            verify(semanticMapper, times(ConnectorSemanticService.WRITE_ATTEMPTS)).update(any(), any());
        }

        /** 留着上一次的 trace_id，「这条口径是在哪次对话里答的」就指向了另一段对话。 */
        @Test
        @DisplayName("覆盖时可空列按这次的值显式写（trace_id 为 null 也写）")
        void nullableColumnsAreWrittenExplicitly() {
            ConnectorSemantic e = existing("旧", null);
            e.setTraceId("trace-old");
            when(semanticMapper.selectOne(any())).thenReturn(e);

            service.defineMetric(CONN_ID, "销售额", "新", null, "u9", "张三", null);

            Written w = onlyWrite();
            assertTrue(w.sets("trace_id") && w.sets("answered_by") && w.sets("history_json"), w.wrapper().getSqlSet());
            assertNull(w.set("trace_id"));
            assertNull(e.getTraceId());
        }
    }
}

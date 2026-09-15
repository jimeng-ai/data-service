package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ 增量写入撞上已有的 INFERRED 行时<b>不毁证据</b>。
 *
 * <p>撞上的场景几乎都是「一张表回来了」，而旧行身上带着的东西新推断一样都给不出：S3 的采样结论（尤其是 REJECTED）、
 * S1 的语料来源、表形态实测、值域。每一样被冲掉都不报错——REJECTED 变回 NONE 会让一条数据否决过的关系重新进 joins。
 */
class ConnectorSemanticUpsertEvidenceTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService service;
    private ConnectionMapper connectionMapper;
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
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    // ================================================================ 夹具

    private ConnectorSemantic existing(String scope, String obj, String field, String evidence) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setSource(SOURCE_INFERRED);
        r.setStatus(ST_DRAFT);
        r.setVerified(V_NONE);
        r.setEvidence(evidence);
        r.setConfidence(70);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
    }

    private static ConnectorSemantic fresh(String scope, String obj, String field, String evidence) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setGloss("新推出来的一句话");
        r.setStatus(ST_DRAFT);
        r.setVerified(V_NONE);
        r.setEvidence(evidence);
        r.setConfidence(90);
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

    /** 这次落库的实体（NOT_NULL 列）与 set 子句（可空列）。 */
    private record Written(ConnectorSemantic entity, LambdaUpdateWrapper<ConnectorSemantic> wrapper) {

        /** 从 set 子句里取某一列写下去的值。sqlSet 形如 {@code detail_json=#{ew.paramNameValuePairs.MPGENVAL3},...}。 */
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
    }

    @SuppressWarnings("unchecked")
    private Written written() {
        ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper).update(u.capture(), w.capture());
        return new Written(u.getValue(), w.getValue());
    }

    // ================================================================ JOIN：采样结论

    @Nested
    @DisplayName("JOIN：采样结论")
    class Verdict {

        private ConnectorSemantic rejectedJoin() {
            ConnectorSemantic old = existing(SCOPE_JOIN, "t_new", "cid", EV_NAME);
            old.setVerified(V_REJECTED);
            old.setAnchorKind(ANCHOR_JOIN);
            old.setAnchorHash("h-join");
            old.setGloss("关联 users.id。采样 1000 个取值只命中 12 个，数据否决了这条关系。");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("to_object", "users");
            d.put("to_column", "id");
            d.put("basis", "采样 1000 个取值，命中 12");
            d.put("sample_n", 1000);
            d.put("match_n", 12);
            d.put("containment", 0.012);
            d.put("auto_joinable", false);
            d.put("verify_note", "包含率过低");
            d.put("join_kind", "SIMPLE");
            old.setDetailJson(json(d));
            return old;
        }

        private ConnectorSemantic reProposed(String anchorHash) {
            ConnectorSemantic f = fresh(SCOPE_JOIN, "t_new", "cid", EV_NAME);
            f.setAnchorKind(ANCHOR_JOIN);
            f.setAnchorHash(anchorHash);
            f.setGloss("关联 USERS.id（N:1）。本条未经数据验证，join 前建议先看该列的取值分布。");
            f.setDetailJson(json(Map.of("to_object", "USERS", "to_column", "id",
                    "cardinality", "N:1", "basis", "未经数据验证")));
            return f;
        }

        /**
         * ★ 需求里点名的那一种。表回来了、增量推导把同一条关系又提了一遍：数据否决过的结论必须留着，
         * 否则它以 NONE 的身份重新进 joins，「REJECTED 永不注入」被静默打破。
         */
        @Test
        @DisplayName("★ 同一条关系、锚点没变：REJECTED 与整组结论键、gloss 都留着")
        void rejectedVerdictSurvivesReProposal() {
            ConnectorSemantic old = rejectedJoin();
            givenRows(old);

            ConnectorSemanticService.UpsertResult res = service.upsertInferred(CONN_ID,
                    List.of(reProposed("h-join")), List.of("t_new"));

            assertEquals(1, res.updated());
            Written w = written();
            assertEquals(V_REJECTED, w.entity().getVerified(), "数据否决过的关系不能被一次重推变回 NONE");
            assertEquals(old.getGloss(), w.entity().getGloss(), "S3 改写过 gloss；换回「未经数据验证」就是在说反话");
            Map<String, Object> d = w.detail();
            assertEquals(1000, d.get("sample_n"));
            assertEquals(12, d.get("match_n"));
            assertEquals(0.012, d.get("containment"));
            assertEquals(false, d.get("auto_joinable"));
            assertEquals("采样 1000 个取值，命中 12", d.get("basis"));
            assertEquals("包含率过低", d.get("verify_note"));
            assertEquals("SIMPLE", d.get("join_kind"));
            assertFalse(d.containsKey("cardinality"), "旧结论里没有基数，模型新猜的基数不能挂到已验证的结论上");
        }

        /** 锚点变了 = 两端列的结构变了，旧结论是在旧结构上量的，作废。 */
        @Test
        @DisplayName("同一条关系、锚点变了：结论作废，整条换成新的")
        void verdictIsResetWhenStructureChanged() {
            givenRows(rejectedJoin());
            ConnectorSemantic f = reProposed("h-join-2");

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            Written w = written();
            assertEquals(V_NONE, w.entity().getVerified());
            assertEquals(f.getGloss(), w.entity().getGloss());
            assertEquals(f.getDetailJson(), w.set("detail_json"));
            assertEquals("h-join-2", w.set("anchor_hash"));
        }
    }

    // ================================================================ 依据强弱

    @Nested
    @DisplayName("依据强的不被依据弱的顶掉")
    class Evidence {

        private ConnectorSemantic corpusJoin(String status) {
            ConnectorSemantic old = existing(SCOPE_JOIN, "t_new", "cid", EV_COMMENT);
            old.setStatus(status);
            old.setAnchorKind(ANCHOR_JOIN);
            old.setAnchorHash("h-corpus");
            old.setConfidence(85);
            old.setGloss("关联 users.id。来自视图 v_ord 的定义。本条未经数据验证。");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("to_object", "users");
            d.put("to_column", "id");
            d.put("cardinality", null);
            d.put("basis", "来自视图 v_ord；未经数据验证");
            d.put("source_sql", "来自视图 v_ord");
            old.setDetailJson(json(d));
            return old;
        }

        /**
         * ★ 需求里点名的另一种：S1 语料关系（客户自己在视图里这样连过）被一条模型的「名字像」撞上。
         * 目标不同也不让——撞的是唯一键，键里只有左列。
         */
        @Test
        @DisplayName("★ 语料关系（COMMENT）不被模型的名字猜测（NAME）顶掉：gloss、目标、detail、依据、状态都不动")
        void corpusRelationIsNotReplacedByWeakerGuess() {
            ConnectorSemantic old = corpusJoin(ST_STALE);
            givenRows(old);
            ConnectorSemantic f = fresh(SCOPE_JOIN, "t_new", "cid", EV_NAME);
            f.setAnchorKind(ANCHOR_JOIN);
            f.setAnchorHash("h-guess");
            f.setDetailJson(json(Map.of("to_object", "members", "to_column", "id", "basis", "未经数据验证")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            Written w = written();
            assertEquals(old.getGloss(), w.entity().getGloss());
            assertEquals(old.getDetailJson(), w.set("detail_json"));
            assertEquals("users", w.detail().get("to_object"));
            assertEquals(EV_COMMENT, w.set("evidence"));
            assertEquals(85, w.set("confidence"));
            assertEquals("h-corpus", w.set("anchor_hash"));
            assertEquals(ST_STALE, w.entity().getStatus(), "换了目标：这次推导没核过旧目标，状态保持漂移处置给的");
        }

        /** 同一个目标、锚点对得上：内容不让，但新锚点证明它在当前结构上成立，可以复活。 */
        @Test
        @DisplayName("语料关系被同一目标的名字猜测撞上：内容不让，状态复活")
        void corpusRelationSameTargetKeepsContentAndRevives() {
            ConnectorSemantic old = corpusJoin(ST_STALE);
            givenRows(old);
            ConnectorSemantic f = fresh(SCOPE_JOIN, "t_new", "cid", EV_NAME);
            f.setAnchorKind(ANCHOR_JOIN);
            f.setAnchorHash("h-corpus");
            f.setDetailJson(json(Map.of("to_object", "users", "to_column", "id")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            Written w = written();
            assertEquals(old.getDetailJson(), w.set("detail_json"));
            assertEquals(ST_DRAFT, w.entity().getStatus());
        }

        /** 两边同样有依据时以新的为准，但「客户这样连过」这条来源仍然成立，不跟着丢。 */
        @Test
        @DisplayName("依据同样强、同一条关系：以新行为准，但 source_sql 留着")
        void sourceSqlSurvivesEqualEvidenceReProposal() {
            givenRows(corpusJoin(ST_DRAFT));
            ConnectorSemantic f = fresh(SCOPE_JOIN, "t_new", "cid", EV_COMMENT);
            f.setAnchorKind(ANCHOR_JOIN);
            f.setAnchorHash("h-corpus");
            f.setGloss("关联 users.id（列注释：关联用户表）。本条未经数据验证。");
            f.setDetailJson(json(Map.of("to_object", "users", "to_column", "id", "basis", "列注释写着关联用户表")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            Written w = written();
            assertEquals(f.getGloss(), w.entity().getGloss());
            Map<String, Object> d = w.detail();
            assertEquals("列注释写着关联用户表", d.get("basis"));
            assertEquals("来自视图 v_ord", d.get("source_sql"));
        }

        @Test
        @DisplayName("数据里测出来的（DATA）表用途不被名字猜测覆盖")
        void dataEvidenceObjectRowIsNotOverwrittenByNameGuess() {
            ConnectorSemantic old = existing(SCOPE_OBJECT, "metric_kv", "", EV_DATA);
            old.setGloss("实测为键值对表：一行是某一个指标的一个值。");
            old.setDetailJson(json(Map.of("table_shape", "KEY_VALUE", "table_shape_source", "MEASURED",
                    "kv_name_column", "metric_name", "kv_value_column", "metric_value")));
            givenRows(old);
            ConnectorSemantic f = fresh(SCOPE_OBJECT, "metric_kv", "", EV_NAME);
            f.setGloss("指标表");
            f.setDetailJson(json(Map.of("table_shape", "DETAIL", "table_shape_source", "MODEL")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("metric_kv"));

            Written w = written();
            assertEquals(old.getGloss(), w.entity().getGloss());
            assertEquals(old.getDetailJson(), w.set("detail_json"));
            assertEquals(EV_DATA, w.set("evidence"));
        }
    }

    // ================================================================ 表形态与值域

    @Nested
    @DisplayName("表形态实测与值域")
    class MeasuredFacts {

        private ConnectorSemantic measuredKv() {
            ConnectorSemantic old = existing(SCOPE_OBJECT, "metric_kv", "", EV_NAME);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put(TableShape.KEY_SHAPE, "KEY_VALUE");
            d.put(TableShape.KEY_SOURCE, TableShape.SOURCE_MEASURED);
            d.put(TableShape.KEY_MODEL_GUESS, "DETAIL");
            d.put(TableShape.KEY_KV_NAME_COLUMN, "metric_name");
            d.put(TableShape.KEY_KV_VALUE_COLUMN, "metric_value");
            d.put(TableShape.KEY_MEASUREMENT, Map.of("outcome", "KEY_VALUE"));
            old.setDetailJson(json(d));
            return old;
        }

        /**
         * ★ 键值对表的实测结论被模型重新猜成别的：丢了它，所有聚合都按明细表 SUM，且测过的表下一轮不会再测。
         */
        @Test
        @DisplayName("★ MEASURED 的表形态与测量留痕留着；模型的新分歧记进 model_guess")
        void measuredShapeSurvivesModelReProposal() {
            givenRows(measuredKv());
            ConnectorSemantic f = fresh(SCOPE_OBJECT, "metric_kv", "", EV_COMMENT);
            f.setGloss("指标日报表（表注释：每日指标）");
            f.setDetailJson(json(Map.of(TableShape.KEY_SHAPE, "MULTI_METRIC_PERIOD",
                    TableShape.KEY_SOURCE, TableShape.SOURCE_MODEL)));

            service.upsertInferred(CONN_ID, List.of(f), List.of("metric_kv"));

            Written w = written();
            assertEquals(f.getGloss(), w.entity().getGloss(), "用途说明以依据同样强的新行为准");
            Map<String, Object> d = w.detail();
            assertEquals("KEY_VALUE", d.get(TableShape.KEY_SHAPE));
            assertEquals(TableShape.SOURCE_MEASURED, d.get(TableShape.KEY_SOURCE));
            assertEquals("metric_name", d.get(TableShape.KEY_KV_NAME_COLUMN));
            assertEquals("metric_value", d.get(TableShape.KEY_KV_VALUE_COLUMN));
            assertEquals(Map.of("outcome", "KEY_VALUE"), d.get(TableShape.KEY_MEASUREMENT));
            assertEquals("MULTI_METRIC_PERIOD", d.get(TableShape.KEY_MODEL_GUESS), "分歧本身是信号");
        }

        @Test
        @DisplayName("模型这次同意实测结论：旧的分歧记录不丢")
        void earlierDisagreementIsKeptWhenModelNowAgrees() {
            givenRows(measuredKv());
            ConnectorSemantic f = fresh(SCOPE_OBJECT, "metric_kv", "", EV_COMMENT);
            f.setDetailJson(json(Map.of(TableShape.KEY_SHAPE, "KEY_VALUE", TableShape.KEY_SOURCE, TableShape.SOURCE_MODEL)));

            service.upsertInferred(CONN_ID, List.of(f), List.of("metric_kv"));

            Map<String, Object> d = written().detail();
            assertEquals(TableShape.SOURCE_MEASURED, d.get(TableShape.KEY_SOURCE));
            assertEquals("DETAIL", d.get(TableShape.KEY_MODEL_GUESS));
        }

        /** 列指纹没变，值域照样有效；丢了就要回客户库再读一遍真实取值。 */
        @Test
        @DisplayName("FIELD 锚点没变、连接开着第 3 档：新行没带值域时留旧的 value_domain")
        void valueDomainSurvivesWhenColumnUnchanged() {
            Connection tier3 = new Connection();
            tier3.setId(CONN_ID);
            tier3.setTenantId(TENANT);
            tier3.setSemanticDataTier("SAMPLE_VALUES");
            when(connectionMapper.selectById(CONN_ID)).thenReturn(tier3);
            ConnectorSemantic old = existing(SCOPE_FIELD, "t_new", "status", EV_NAME);
            old.setAnchorKind(ANCHOR_FIELD);
            old.setAnchorHash("h-col");
            old.setDetailJson(json(Map.of(SemanticValueProfiler.DETAIL_KEY,
                    Map.of("complete", true, "values", List.of(0, 1, 2)))));
            givenRows(old);
            ConnectorSemantic f = fresh(SCOPE_FIELD, "t_new", "status", EV_NAME);
            f.setAnchorKind(ANCHOR_FIELD);
            f.setAnchorHash("h-col");
            f.setDetailJson(json(Map.of("note", "状态码，含义需确认")));

            service.upsertInferred(CONN_ID, List.of(f), List.of("t_new"));

            Map<String, Object> d = written().detail();
            assertEquals("状态码，含义需确认", d.get("note"));
            assertTrue(d.get(SemanticValueProfiler.DETAIL_KEY) instanceof Map<?, ?> vd
                    && Boolean.TRUE.equals(vd.get("complete")), String.valueOf(d));
        }
    }
}

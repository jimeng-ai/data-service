package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推导流水线上那几种<b>静默</b>的失败：真实取值从一句不设防的话里出库、未知被冻成「普通关系」、
 * 一次被打断的补写把状态永久钉在 RUNNING、每天在客户库上重跑剖析、提示名单被悄悄丢掉。
 *
 * <p>与 {@code ConnectorSemanticStageWiringTest} 同一个立场：验证器、值域、表形态测量全部 mock，这里只钉接线与落库纪律。
 */
class ConnectorSemanticPipelineSilentFailureTest {

    private static final Long CONNECTOR_ID = 7L;
    private static final String TENANT = "t1";

    private ConnectorSchemaService schemaService;
    private ConnectorSemanticService semanticService;
    private ConnectionMapper connectionMapper;
    private ClaudeService claudeService;
    private ConnectorProperties properties;
    private SemanticJoinValidator joinValidator;
    private SemanticValueProfiler valueProfiler;
    private ConnectorSemanticMapper semanticMapper;
    private ThreadPoolTaskExecutor semanticStageExecutor;
    private TableShapeDetector shapeDetector;
    private RedissonClient redisson;
    private Map<String, RBucket<Object>> buckets;
    private ConnectorSemanticDeriveService service;
    private Connection conn;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schemaService = mock(ConnectorSchemaService.class);
        semanticService = mock(ConnectorSemanticService.class);
        connectionMapper = mock(ConnectionMapper.class);
        claudeService = mock(ClaudeService.class);
        properties = new ConnectorProperties();
        joinValidator = mock(SemanticJoinValidator.class);
        valueProfiler = mock(SemanticValueProfiler.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        semanticStageExecutor = mock(ThreadPoolTaskExecutor.class);
        shapeDetector = mock(TableShapeDetector.class);
        redisson = mock(RedissonClient.class);
        buckets = new HashMap<>();
        when(redisson.getBucket(anyString())).thenAnswer(inv ->
                buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        service = new ConnectorSemanticDeriveService(schemaService, semanticService, connectionMapper,
                claudeService, properties, mock(SemanticSqlCorpusReader.class), joinValidator, valueProfiler,
                semanticMapper, mock(ConnectorAuditService.class), mock(ThreadPoolTaskExecutor.class),
                semanticStageExecutor, shapeDetector, redisson);

        conn = new Connection();
        conn.setId(CONNECTOR_ID);
        conn.setName("客户生产库");
        conn.setKind("MYSQL");
        conn.setTenantId(TENANT);
        conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_READY);
        conn.setSemanticNote("覆盖 3/3 个对象：表用途 3");
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(conn);
        when(connectionMapper.update(any(), any())).thenReturn(1);
        when(semanticMapper.updateById(any())).thenReturn(1);
        when(semanticService.upsertInferred(eq(CONNECTOR_ID), any(), any()))
                .thenReturn(new ConnectorSemanticService.UpsertResult(1, 0, 0, 0, 0));
        profilerDisabled();
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private static FieldDetail col(String name, String type) {
        return new FieldDetail(name, type, true, null, null);
    }

    private static ConnectorSchema table(String name, FieldDetail... fields) {
        return tableWithExtra(name, Map.of(), fields);
    }

    /** 快照里带着唯一键（= 目标表的唯一键「已知」）。 */
    private static ConnectorSchema tableWithKeys(String name, List<List<String>> keys, FieldDetail... fields) {
        List<Map<String, Object>> ks = new ArrayList<>();
        for (List<String> k : keys) {
            ks.add(Map.of("columns", k));
        }
        return tableWithExtra(name, Map.<String, Object>of(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, ks), fields);
    }

    private static ConnectorSchema tableWithExtra(String name, Map<String, Object> extra, FieldDetail... fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType("BASE TABLE");
        s.setContentHash("hash-" + name);
        s.setDetailJson(json(ConnectorSchemaService.toDetailMap(
                new ObjectDetail(name, "BASE TABLE", null, List.of(fields), extra))));
        return s;
    }

    private void snapshot(ConnectorSchema... rows) {
        when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(rows));
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(String detailJson) {
        try {
            return CommonUtil.getObjectMapper().readValue(detailJson, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConnectorSemantic joinRow(long id, String obj, String col, String toObj, String toCol,
                                             String verified, String gloss, Map<String, Object> extra) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(id);
        r.setConnectorId(CONNECTOR_ID);
        r.setTenantId(TENANT);
        r.setScope(ConnectorSemanticService.SCOPE_JOIN);
        r.setObjectName(obj);
        r.setFieldName(col);
        r.setTerm("");
        r.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        r.setEvidence(ConnectorSemanticService.EV_NAME);
        r.setVerified(verified);
        r.setGloss(gloss);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", toObj);
        d.put("to_column", toCol);
        d.putAll(extra);
        r.setDetailJson(json(d));
        return r;
    }

    private static ConnectorSemantic fieldRow(long id, String obj, String col, String evidence, String gloss,
                                              Map<String, Object> detail) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(id);
        r.setConnectorId(CONNECTOR_ID);
        r.setTenantId(TENANT);
        r.setScope(ConnectorSemanticService.SCOPE_FIELD);
        r.setObjectName(obj);
        r.setFieldName(col);
        r.setTerm("");
        r.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        r.setEvidence(evidence);
        r.setGloss(gloss);
        r.setDetailJson(json(detail));
        return r;
    }

    private void profilerDisabled() {
        when(valueProfiler.profile(anyLong(), any())).thenReturn(SemanticValueProfiler.ValueProfile.builder()
                .tierAllowed(false).tierStatement(SemanticValueProfiler.NOT_ENABLED_NOTE).build());
    }

    private void profilerReturns(SemanticValueProfiler.ColumnValueDomain... domains) {
        when(valueProfiler.profile(anyLong(), any())).thenReturn(SemanticValueProfiler.ValueProfile.builder()
                .tierAllowed(true).tierStatement("第 3 档").domains(List.of(domains))
                .attemptedColumns(domains.length).statements(domains.length).build());
    }

    private List<ConnectorSemantic> updates() {
        ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
        verify(semanticMapper, atLeastOnce()).updateById(cap.capture());
        return cap.getAllValues();
    }

    private Connection lastStatusWrite() {
        ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper, atLeastOnce()).update(cap.capture(), any());
        List<Connection> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    private void modelOutputs(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        when(claudeService.messages(any())).thenReturn(Map.of("content", List.of(block)));
    }

    @SuppressWarnings("unchecked")
    private RBucket<Object> bucket(String key) {
        return buckets.computeIfAbsent(key, k -> mock(RBucket.class));
    }

    /** 入队，并把刚派发出去的最后一个后台任务就地跑完。 */
    private void enqueueAndDrain(Collection<String> added) {
        service.deriveAddedAsync(CONNECTOR_ID, added);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(semanticStageExecutor, atLeastOnce()).execute(task.capture());
        List<Runnable> all = task.getAllValues();
        all.get(all.size() - 1).run();
        TenantContext.set(TENANT);
    }

    @SuppressWarnings("unchecked")
    private List<Collection<String>> updatableArgs() {
        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(semanticService, atLeastOnce()).upsertInferred(eq(CONNECTOR_ID), any(), cap.capture());
        return cap.getAllValues();
    }

    // ================================================================ 1. 自由文本里的真实取值（K-3）

    @Nested
    @DisplayName("1. 值域补写行的 gloss 不带真实取值（K-3）")
    class ValueGloss {

        private static final String LEGACY_GLOSS =
                "取值只有这 2 种：华东大区、华南大区。（采样时点的完整集合，之后客户新增的取值不在其中）";

        private Map<String, Object> enumeratedDomain() {
            Map<String, Object> vd = new LinkedHashMap<>();
            vd.put("complete", true);
            vd.put("outcome", "ENUMERATED");
            vd.put("distinct_count", 2);
            vd.put("values", List.of("华东大区", "华南大区"));
            return vd;
        }

        private SemanticValueProfiler.ColumnValueDomain highCardinality(String obj, String field) {
            return SemanticValueProfiler.ColumnValueDomain.builder().objectName(obj).fieldName(field)
                    .outcome(SemanticValueProfiler.Outcome.HIGH_CARDINALITY).note("取值太多").build();
        }

        @Test
        @DisplayName("★ 补写的行：gloss 一个取值、一个个数都没有；带 origin=value_profile；取值只在 value_domain 里")
        void createdRowCarriesNoValuesInGloss() {
            snapshot(table("orders", col("id", "bigint"), col("region", "varchar(16)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            profilerReturns(SemanticValueProfiler.ColumnValueDomain.builder()
                    .objectName("orders").fieldName("region").outcome(SemanticValueProfiler.Outcome.ENUMERATED)
                    .distinctCount(2L).values(List.of("华东大区", "华南大区")).note("取值共 2 种").build());

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> ins = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(ins.capture());
            ConnectorSemantic row = ins.getValue();
            assertEquals(ConnectorSemanticDeriveService.VALUE_ROW_GLOSS, row.getGloss());
            assertFalse(row.getGloss().contains("华东"), row.getGloss());
            assertFalse(row.getGloss().matches(".*\\d.*"), "个数和取值出自同一次第 3 档采集，也不许进 gloss：" + row.getGloss());
            Map<String, Object> d = detailOf(row.getDetailJson());
            assertEquals(ConnectorSemanticDeriveService.ORIGIN_VALUE_PROFILE, d.get(ConnectorSemanticDeriveService.KEY_ORIGIN));
            assertTrue(String.valueOf(d.get(SemanticValueProfiler.DETAIL_KEY)).contains("华东大区"));
        }

        /** 这就是那条漏口：重采没枚举出取值，value_domain 被换掉之后注入层就不再挡 gloss。 */
        @Test
        @DisplayName("★ 旧版补写行：重采没枚举出取值、value_domain 被换掉时，带取值的 gloss 从来不会单独留下")
        void legacyGlossNeverSurvivesAReprofileThatDropsValues() {
            snapshot(table("orders", col("id", "bigint"), col("region", "varchar(16)")));
            ConnectorSemantic legacy = fieldRow(5L, "orders", "region", ConnectorSemanticService.EV_DATA, LEGACY_GLOSS,
                    Map.of(SemanticValueProfiler.DETAIL_KEY, enumeratedDomain()));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(legacy));
            profilerReturns(highCardinality("orders", "region"));

            service.validate(CONNECTOR_ID);

            List<ConnectorSemantic> us = updates();
            int glossNeutralisedAt = -1;
            int valuesDroppedAt = -1;
            for (int i = 0; i < us.size(); i++) {
                ConnectorSemantic u = us.get(i);
                assertTrue(u.getGloss() == null || !u.getGloss().contains("华东"), "任何一次写都不许留下带取值的 gloss");
                if (glossNeutralisedAt < 0 && ConnectorSemanticDeriveService.VALUE_ROW_GLOSS.equals(u.getGloss())) {
                    glossNeutralisedAt = i;
                }
                if (valuesDroppedAt < 0 && u.getDetailJson() != null && !String.valueOf(
                        detailOf(u.getDetailJson()).get(SemanticValueProfiler.DETAIL_KEY)).contains("华东")) {
                    valuesDroppedAt = i;
                }
            }
            assertTrue(glossNeutralisedAt >= 0, "带取值的旧 gloss 必须被换掉");
            assertTrue(valuesDroppedAt >= 0, "重采没枚举出取值，value_domain 应被换掉");
            assertTrue(glossNeutralisedAt <= valuesDroppedAt,
                    "gloss 必须不晚于 value_domain 失去取值的那次写被换掉——反过来，中间那一刻注入层就不再挡它了");
            ConnectorSemantic last = us.get(us.size() - 1);
            Map<String, Object> d = detailOf(last.getDetailJson());
            assertEquals(ConnectorSemanticDeriveService.ORIGIN_VALUE_PROFILE, d.get(ConnectorSemanticDeriveService.KEY_ORIGIN),
                    "旧行顺手补上来源标记，之后不再靠措辞认它");
            assertFalse(String.valueOf(d.get(SemanticValueProfiler.DETAIL_KEY)).contains("华东"));
        }

        @Test
        @DisplayName("★ 档位不开放、值域阶段根本不跑：旧版带取值的 gloss 照样在验证开头被换掉")
        void legacyGlossIsNeutralisedEvenWhenValueStageDoesNotRun() {
            snapshot(table("orders", col("id", "bigint"), col("region", "varchar(16)")));
            ConnectorSemantic legacy = fieldRow(5L, "orders", "region", ConnectorSemanticService.EV_DATA, LEGACY_GLOSS,
                    Map.of(SemanticValueProfiler.DETAIL_KEY, enumeratedDomain()));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(legacy));

            service.validate(CONNECTOR_ID);

            ConnectorSemantic u = updates().get(0);
            assertEquals(5L, u.getId());
            assertEquals(ConnectorSemanticDeriveService.VALUE_ROW_GLOSS, u.getGloss());
        }

        /** 提示词允许模型给字段含义标 DATA；按「INFERRED + FIELD + DATA」一刀切会把模型写的说明抹掉。 */
        @Test
        @DisplayName("模型写的字段说明（哪怕标了 DATA）不被当成补写行改写")
        void modelDataFieldGlossIsNotTouched() {
            snapshot(table("orders", col("id", "bigint"), col("region", "varchar(16)")));
            ConnectorSemantic model = fieldRow(6L, "orders", "region", ConnectorSemanticService.EV_DATA,
                    "所属大区，varchar(16) 的短码", Map.of(SemanticValueProfiler.DETAIL_KEY, enumeratedDomain()));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(model));
            profilerReturns(highCardinality("orders", "region"));

            service.validate(CONNECTOR_ID);

            for (ConnectorSemantic u : updates()) {
                assertNull(u.getGloss(), "模型写的说明不许被改写");
            }
        }

        /** 清除样本值那一处写的是它自己那句不含取值的话。两边各自改写会在每次刷新与每轮验证之间来回覆盖。 */
        @Test
        @DisplayName("已经不含取值的补写行 gloss（例如清除样本值写下的那句）不被来回改写")
        void neutralGlossOfAnotherWriterIsNotFlipped() {
            snapshot(table("orders", col("id", "bigint"), col("region", "varchar(16)")));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put(ConnectorSemanticDeriveService.KEY_ORIGIN, ConnectorSemanticDeriveService.ORIGIN_VALUE_PROFILE);
            d.put(SemanticValueProfiler.DETAIL_KEY, Map.of("complete", false, "note", "已删除"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(fieldRow(7L, "orders", "region",
                    ConnectorSemanticService.EV_DATA, "这一列的样本取值已按数据出库档位删除", d)));

            service.validate(CONNECTOR_ID);

            verify(semanticMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("旧版识别只认那一版的原话")
        void legacyFingerprint() {
            assertTrue(ConnectorSemanticDeriveService.legacyValueGloss(LEGACY_GLOSS));
            assertTrue(ConnectorSemanticDeriveService.legacyValueGloss(
                    "取值共 40 种，太长放不进这句话，完整集合见本行 detail_json 的 value_domain。（采样时点的完整集合）"));
            assertFalse(ConnectorSemanticDeriveService.legacyValueGloss("取值只有这 2 种：A、B"), "没有那半句尾巴不算");
            assertFalse(ConnectorSemanticDeriveService.legacyValueGloss(ConnectorSemanticDeriveService.VALUE_ROW_GLOSS));
            assertFalse(ConnectorSemanticDeriveService.legacyValueGloss(null));
        }
    }

    // ================================================================ 2 / 3 / 7. 结构补标

    @Nested
    @DisplayName("2/3/7. 结构补标：不冻结未知、不看范围、与结论矛盾时打回")
    class Structure {

        private ConnectorSchema orders() {
            return table("orders", col("id", "bigint"), col("sku_code", "varchar(32)"));
        }

        private Map<String, Object> patch(String kind, Object... kv) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("join_kind", kind);
            for (int i = 0; i < kv.length; i += 2) {
                p.put((String) kv[i], kv[i + 1]);
            }
            return p;
        }

        private Map<String, Object> confirmedDetail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cardinality", "N:1");
            d.put("auto_joinable", true);
            d.put("sample_n", 1000);
            d.put("match_n", 990);
            d.put("containment", 0.99);
            d.put("basis", "采样 1000 个取值，命中 990（包含率 99.0%）；实测基数 N:1");
            d.put("source_sql", "来自视图 v_order_sku 的定义");
            return d;
        }

        @Test
        @DisplayName("★ 目标表唯一键还不知道：判成普通关系也不写 join_kind（否则组合键永远被冻成 SIMPLE），note 说暂缓")
        void simpleIsNotWrittenWhileTargetKeysUnknown() {
            snapshot(orders(), table("sku", col("shop_id", "bigint"), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_CONFIRMED, "关联 sku.code（N:1）。已通过采样验证：…", confirmedDetail())));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(patch("SIMPLE"));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            verify(joinValidator).structureOnly(any(), any(), any());
            verify(semanticMapper, never()).updateById(any());
            assertTrue(r.getNote().contains("暂缓"), r.getNote());
        }

        @Test
        @DisplayName("目标表唯一键已知：普通关系照写 join_kind=SIMPLE，结论不动")
        void simpleIsWrittenOnceKeysAreKnown() {
            snapshot(orders(), tableWithKeys("sku", List.of(List.of("code")), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_CONFIRMED, "关联 sku.code。已通过采样验证：…", confirmedDetail())));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(patch("SIMPLE"));

            service.validate(CONNECTOR_ID);

            ConnectorSemantic u = updates().get(0);
            assertEquals("SIMPLE", detailOf(u.getDetailJson()).get("join_kind"));
            assertNull(u.getVerified());
            assertNull(u.getGloss());
        }

        @Test
        @DisplayName("★ 验证器结论那条路同一条规矩：唯一键不知道时不写 SIMPLE，知道时照写")
        void verdictPathFollowsTheSameKeysRule() {
            SemanticJoinValidator.JoinVerdict v = SemanticJoinValidator.JoinVerdict.builder()
                    .fromObject("orders").fromColumn("sku_code").toObject("sku").toColumn("code")
                    .verified(ConnectorSemanticService.V_CONFIRMED).cardinality("N:1").autoJoinable(true).probed(true)
                    .basis("采样 1000 个取值，命中 990").joinKind("SIMPLE").build();
            when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenAnswer(inv -> {
                SemanticJoinValidator.ProbeProgress p = inv.getArgument(4);
                p.onDecided(v, 1, 1);
                return SemanticJoinValidator.JoinValidationResult.builder().outcome(SemanticJoinValidator.OUT_RAN)
                        .note("测试").verdicts(List.of(v)).probeCount(1).build();
            });

            snapshot(orders(), table("sku", col("shop_id", "bigint"), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_NONE, "关联 sku.code。本条未经数据验证。", Map.of("basis", "未经数据验证"))));
            service.validate(CONNECTOR_ID);
            assertFalse(detailOf(updates().get(0).getDetailJson()).containsKey("join_kind"),
                    "唯一键不知道时写下 SIMPLE，这条已决的关系就再也不会被回头判一次");

            setUp();
            when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenAnswer(inv -> {
                SemanticJoinValidator.ProbeProgress p = inv.getArgument(4);
                p.onDecided(v, 1, 1);
                return SemanticJoinValidator.JoinValidationResult.builder().outcome(SemanticJoinValidator.OUT_RAN)
                        .note("测试").verdicts(List.of(v)).probeCount(1).build();
            });
            snapshot(orders(), tableWithKeys("sku", List.of(List.of("code")), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_NONE, "关联 sku.code。本条未经数据验证。", Map.of("basis", "未经数据验证"))));
            service.validate(CONNECTOR_ID);
            assertEquals("SIMPLE", detailOf(updates().get(0).getDetailJson()).get("join_kind"));
        }

        /** 部署之后唯一会自动跑的验证是范围很窄的那种；从前它在任何结构写入之前就跳过范围之外的 V_NONE 行。 */
        @Test
        @DisplayName("★ 范围很窄的一轮：范围之外、没决过的存量关系照样补标结构，但不进候选、不花客户配额")
        void scopedRoundStillMarksOutOfScopeRows() {
            snapshot(table("role_resource", col("id", "bigint"), col("resource_type", "varchar(16)"),
                    col("resource_id", "bigint")), table("agent", col("id", "bigint")), table("t_new", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_NONE, "关联 agent.id。本条未经数据验证。", Map.of())));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(
                    patch("POLYMORPHIC", "discriminator_column", "resource_type", "care_reason", "疑似多态外键"));

            service.validate(CONNECTOR_ID, Set.of("t_new"));

            ConnectorSemantic u = updates().get(0);
            assertEquals("POLYMORPHIC", detailOf(u.getDetailJson()).get("join_kind"));
            assertNull(u.getVerified());
            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
        }

        /** auto_joinable=true 会让注入层不给 fan-out 警告；留着它，结构键说「要当心」、结论说「放心连」。 */
        @Test
        @DisplayName("★ 已决的关系翻成组合键：CONFIRMED / auto_joinable / 基数 / 「已通过采样验证」整条打回未验证，本轮就重验（不看范围）")
        void compositeResetsContradictingVerdictAndRevalidates() {
            snapshot(orders(), tableWithKeys("sku", List.of(List.of("shop_id", "code")),
                    col("shop_id", "bigint"), col("code", "varchar(32)")));
            ConnectorSemantic row = joinRow(1L, "orders", "sku_code", "sku", "code", ConnectorSemanticService.V_CONFIRMED,
                    "关联 sku.code（N:1）。已通过采样验证：采样 1000 个取值，命中 990。", confirmedDetail());
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(patch("COMPOSITE",
                    "composite_columns", List.of("shop_id", "code"), "care_reason", "sku.code 只是组合唯一键的一部分"));

            service.validate(CONNECTOR_ID, Set.of("t_other"));

            ConnectorSemantic u = updates().get(0);
            assertEquals(ConnectorSemanticService.V_NONE, u.getVerified());
            assertFalse(u.getGloss().contains("已通过"), u.getGloss());
            assertTrue(u.getGloss().contains("未经数据验证") && u.getGloss().contains("v_order_sku"), u.getGloss());
            Map<String, Object> d = detailOf(u.getDetailJson());
            assertEquals("COMPOSITE", d.get("join_kind"));
            assertEquals(List.of("shop_id", "code"), d.get("composite_columns"));
            for (String k : List.of("auto_joinable", "cardinality", "sample_n", "match_n", "containment")) {
                assertFalse(d.containsKey(k), "与组合键矛盾的结论键必须删掉：" + k);
            }
            assertEquals("来自视图 v_order_sku 的定义；未经数据验证", d.get("basis"));
            assertEquals("来自视图 v_order_sku 的定义", d.get("source_sql"), "S1 的来源不能跟着结论一起抹掉");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SemanticJoinValidator.JoinCandidate>> cands = ArgumentCaptor.forClass(List.class);
            verify(joinValidator).validate(eq(CONNECTOR_ID), cands.capture(), any(), any(), any());
            assertEquals("sku_code", cands.getValue().get(0).getFromColumn(), "打回就是为了按结构重判，本轮就验，不看范围");
        }

        @Test
        @DisplayName("组合键上的 REJECTED 仍然成立（包含率探查一模一样）：只补结构，不打回")
        void rejectedStaysRejectedOnComposite() {
            snapshot(orders(), tableWithKeys("sku", List.of(List.of("shop_id", "code")),
                    col("shop_id", "bigint"), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_REJECTED, "关联 sku.code。采样验证不成立", Map.of("containment", 0.1))));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(
                    patch("COMPOSITE", "composite_columns", List.of("shop_id", "code")));

            service.validate(CONNECTOR_ID);

            ConnectorSemantic u = updates().get(0);
            assertNull(u.getVerified());
            assertEquals("COMPOSITE", detailOf(u.getDetailJson()).get("join_kind"));
            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
        }

        /** 多态外键的 REJECTED 可能只是被别的类型的行拉低；而 REJECTED 永不注入，这条关系就此消失。 */
        @Test
        @DisplayName("多态外键上的 REJECTED 打回未验证")
        void rejectedIsResetOnPolymorphic() {
            snapshot(table("role_resource", col("resource_type", "varchar(16)"), col("resource_id", "bigint")),
                    table("agent", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_REJECTED, "关联 agent.id。采样验证不成立", Map.of())));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(
                    patch("POLYMORPHIC", "discriminator_column", "resource_type"));

            service.validate(CONNECTOR_ID);

            assertEquals(ConnectorSemanticService.V_NONE, updates().get(0).getVerified());
        }

        @Test
        @DisplayName("唯一键到了之后回头再判多态外键：真的多出组合键才写，判别列与判别值原样留着，结论不动")
        void polymorphicReexamAddsCompositeAndKeepsDiscriminatorValue() {
            snapshot(table("role_resource", col("resource_type", "varchar(16)"), col("resource_id", "bigint")),
                    tableWithKeys("orders_p", List.of(List.of("id", "create_time")),
                            col("id", "bigint"), col("create_time", "datetime")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "orders_p", "id", ConnectorSemanticService.V_CONFIRMED, "关联 orders_p.id。已通过采样验证",
                    Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "resource_type",
                            "discriminator_value", "ORDER", "care_reason", "多态外键：… = 'ORDER' 时才指向 orders_p.id"))));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(patch("POLYMORPHIC",
                    "discriminator_column", "resource_type", "composite_columns", List.of("id", "create_time"),
                    "care_reason", "疑似多态外键…组合唯一键"));

            service.validate(CONNECTOR_ID);

            ConnectorSemantic u = updates().get(0);
            assertNull(u.getVerified());
            Map<String, Object> d = detailOf(u.getDetailJson());
            assertEquals(List.of("id", "create_time"), d.get("composite_columns"));
            assertEquals("ORDER", d.get("discriminator_value"), "判别值来自第 3 档探查，结构判定补不回它");
        }

        @Test
        @DisplayName("回头再判的多态外键没有多出组合键：一个字不写（验证侧那句 care_reason 更具体）")
        void polymorphicReexamWithoutNewCompositeWritesNothing() {
            snapshot(table("role_resource", col("resource_type", "varchar(16)"), col("resource_id", "bigint")),
                    tableWithKeys("agent", List.of(List.of("id")), col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_UNDECIDABLE, "关联 agent.id。",
                    Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "resource_type",
                            ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES, true))));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(
                    patch("POLYMORPHIC", "discriminator_column", "resource_type", "care_reason", "泛泛的一句"));

            service.validate(CONNECTOR_ID);

            verify(semanticMapper, never()).updateById(any());
        }
    }

    // ================================================================ 3. 刷新心跳上的结构补标

    @Nested
    @DisplayName("3. 刷新结构这条心跳上补标结构（不依赖验证被派发）")
    class RefreshHeartbeat {

        @BeforeEach
        void noIncrementalWork() {
            // 说明书没 READY：补写那一圈不跑，单独看心跳这一趟。
            conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_FAILED);
        }

        @Test
        @DisplayName("★ 一条不再出新表的连接：每次刷新都会补标存量关系的结构，不派验证")
        void refreshMarksLegacyRelationsWithoutValidation() {
            snapshot(table("orders", col("sku_code", "varchar(32)")),
                    tableWithKeys("sku", List.of(List.of("code")), col("code", "varchar(32)")));
            when(semanticMapper.selectList(any())).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_NONE, "关联 sku.code。本条未经数据验证。", Map.of())));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(Map.of("join_kind", "SIMPLE"));

            enqueueAndDrain(List.of());

            assertEquals("SIMPLE", detailOf(updates().get(0).getDetailJson()).get("join_kind"));
            verify(semanticStageExecutor, times(1)).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("★ 心跳上打回了矛盾的结论：派一轮只限那几张表的验证，让它们按结构重判")
        void resetOnRefreshDispatchesScopedValidation() {
            snapshot(table("orders", col("sku_code", "varchar(32)")), tableWithKeys("sku",
                    List.of(List.of("shop_id", "code")), col("shop_id", "bigint"), col("code", "varchar(32)")));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cardinality", "N:1");
            d.put("auto_joinable", true);
            when(semanticMapper.selectList(any())).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_CONFIRMED, "关联 sku.code（N:1）。已通过采样验证", d)));
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(
                    Map.of("join_kind", "COMPOSITE", "composite_columns", List.of("shop_id", "code")));

            enqueueAndDrain(List.of());

            assertEquals(ConnectorSemanticService.V_NONE, updates().get(0).getVerified());
            verify(semanticStageExecutor, times(2)).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("验证正在跑：心跳这一趟不补（它开头已经扫过全部关系），也不交错写")
        void skipsWhileValidationRuns() {
            @SuppressWarnings("unchecked")
            Set<Long> validating = (Set<Long>) ReflectionTestUtils.getField(service, "validating");
            validating.add(CONNECTOR_ID);
            when(semanticMapper.selectList(any())).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_NONE, "关联 sku.code。", Map.of())));

            enqueueAndDrain(List.of());

            verify(joinValidator, never()).structureOnly(any(), any(), any());
            verify(semanticMapper, never()).updateById(any());
            assertTrue(validating.contains(CONNECTOR_ID), "别人手里的闸不能被这一趟放掉");
        }
    }

    // ================================================================ 4. 增量补写的认领

    @Nested
    @DisplayName("4. 增量补写的认领：超时接管，每条出路回到 READY")
    class IncrementalClaim {

        @BeforeEach
        void snapshotReady() {
            snapshot(table("t_refund", col("id", "bigint"), col("amt", "decimal(12,2)")));
            modelOutputs("{\"objects\":[{\"name\":\"t_refund\",\"gloss\":\"退款单\",\"evidence\":\"NAME\"}]}");
        }

        private void running(long claimedMinutesAgo, Date syncedAt) {
            conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_RUNNING);
            conn.setSemanticNote("正在为 3 张表补写说明书……");
            conn.setSemanticClaimAt(new Date(System.currentTimeMillis() - claimedMinutesAgo * 60_000L));
            conn.setSemanticSyncedAt(syncedAt);
        }

        /** 发版杀掉一次补写：从前状态永远停在 RUNNING，每次刷新的覆盖检查都静默跳过，只能整层重新生成。 */
        @Test
        @DisplayName("★ 超时的 RUNNING（说明书成功生成过）：接管、补写、回到 READY，note 说清发生了什么")
        void staleClaimIsTakenOver() {
            running(31, new Date());

            ConnectorSemanticDeriveService.AddedOutcome out = service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            assertTrue(out.result().isOk(), out.result().getNote());
            verify(claudeService).messages(any());
            Connection last = lastStatusWrite();
            assertEquals(ConnectorSemanticDeriveService.SEM_READY, last.getSemanticStatus());
            assertTrue(last.getSemanticNote().contains("已接管"), last.getSemanticNote());
        }

        @Test
        @DisplayName("★ 刷新触发的那一圈也认得超时的 RUNNING（覆盖检查不再静默跳过）")
        void drainTakesOverStaleClaim() {
            running(45, new Date());
            when(semanticMapper.selectList(any())).thenReturn(List.of());

            enqueueAndDrain(List.of());

            verify(claudeService).messages(any());
        }

        @Test
        @DisplayName("超时的 RUNNING 但从没成功生成过：不接管（那会把「全量没生成出来」盖成可用），留给全量推导")
        void staleClaimWithoutSuccessfulManualIsLeftAlone() {
            running(31, null);

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            verify(connectionMapper, never()).update(any(), any());
            verify(claudeService, never()).messages(any());
        }

        @Test
        @DisplayName("还没超时的 RUNNING：另一次推导正在跑，不接管")
        void freshClaimIsNotTakenOver() {
            running(5, new Date());

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            verify(connectionMapper, never()).update(any(), any());
            verify(claudeService, never()).messages(any());
        }

        @Test
        @DisplayName("★ 认领之后抛出 catch 接不住的 Error：状态照样回到 READY")
        void errorAfterClaimStillRestoresReady() {
            when(claudeService.messages(any())).thenThrow(new StackOverflowError());

            assertThrows(StackOverflowError.class, () -> service.deriveAdded(CONNECTOR_ID, Set.of("t_refund")));

            assertEquals(ConnectorSemanticDeriveService.SEM_READY, lastStatusWrite().getSemanticStatus());
        }
    }

    // ================================================================ 5. 客户库上的重复剖析

    @Nested
    @DisplayName("5. 不在客户库上天天重跑剖析")
    class RepeatedProfiling {

        @Test
        @DisplayName("★ 模型一行都没写出来：不派验证（从前每个冷却期都派一轮，天天测表形态、采取值）")
        void noNewRowsMeansNoValidation() {
            snapshot(table("t_kv", col("k", "varchar(32)"), col("v", "varchar(64)")));
            modelOutputs("{\"objects\":[{\"name\":\"t_kv\",\"gloss\":\"猜的\",\"evidence\":\"GUESS\"}]}");
            when(semanticService.upsertInferred(eq(CONNECTOR_ID), any(), any()))
                    .thenReturn(new ConnectorSemanticService.UpsertResult(0, 0, 0, 0, 0));

            service.deriveAdded(CONNECTOR_ID, Set.of("t_kv"), Set.of());

            verify(semanticStageExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("★ 验证只派给真的多出了行的表：已经有行的那张不再被剖析")
        @SuppressWarnings("unchecked")
        void validationScopedToTablesWithNewRows() {
            snapshot(table("users", col("id", "bigint")), table("t_refund", col("id", "bigint")));
            ConnectorSemantic existingUsers = new ConnectorSemantic();
            existingUsers.setScope(ConnectorSemanticService.SCOPE_OBJECT);
            existingUsers.setObjectName("users");
            existingUsers.setFieldName("");
            existingUsers.setTerm("");
            existingUsers.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(existingUsers));
            modelOutputs("{\"objects\":[{\"name\":\"users\",\"gloss\":\"用户\",\"evidence\":\"NAME\"},"
                    + "{\"name\":\"t_refund\",\"gloss\":\"退款单\",\"evidence\":\"NAME\"}]}");
            when(semanticService.upsertInferred(eq(CONNECTOR_ID), any(), any()))
                    .thenReturn(new ConnectorSemanticService.UpsertResult(1, 0, 0, 1, 0));

            service.deriveAdded(CONNECTOR_ID, Set.of("users", "t_refund"), Set.of());

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(semanticStageExecutor).execute(task.capture());
            task.getValue().run();
            TenantContext.set(TENANT);
            ArgumentCaptor<List<ConnectorSchema>> profiled = ArgumentCaptor.forClass(List.class);
            verify(valueProfiler).profile(eq(CONNECTOR_ID), profiled.capture());
            assertEquals(List.of("t_refund"), profiled.getValue().stream().map(ConnectorSchema::getObjectName).toList());
        }

        private TableShapeDetector.ShapeVerdict verdict(String table, TableShapeDetector.Outcome outcome) {
            return TableShapeDetector.ShapeVerdict.builder().objectName(table).outcome(outcome)
                    .nameColumn("k").valueColumn("v").basis("采样 12 行").probed(true).build();
        }

        private void detectorReturns(String outcome, TableShapeDetector.ShapeVerdict... vs) {
            when(shapeDetector.detect(eq(CONNECTOR_ID), any(), any())).thenAnswer(inv -> {
                TableShapeDetector.Progress p = inv.getArgument(2);
                for (TableShapeDetector.ShapeVerdict v : vs) {
                    p.onDecided(v);
                }
                return TableShapeDetector.ShapeRun.builder().outcome(outcome).tierAllowed(true)
                        .verdicts(List.of(vs)).statements(vs.length).note("表形态测量 " + vs.length + " 张").build();
            });
        }

        @Test
        @DisplayName("★ 没有用途行的表测完记冷却（Redis、带结构指纹），冷却期内不再测")
        void unownedTableMeasurementIsCooledDown() {
            snapshot(table("t_x", col("k", "varchar(32)"), col("v", "decimal(12,2)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            detectorReturns(TableShapeDetector.OUT_RAN, verdict("t_x", TableShapeDetector.Outcome.NOT_KEY_VALUE));
            String key = ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_x", "hash-t_x");

            service.validate(CONNECTOR_ID);
            verify(bucket(key)).set(any(), eq(168L), eq(TimeUnit.HOURS));

            when(bucket(key).isExists()).thenReturn(true);
            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            verify(shapeDetector, times(1)).detect(anyLong(), any(), any());
            assertTrue(r.getNote().contains("冷却"), r.getNote());
        }

        @Test
        @DisplayName("测出键值对表的不记冷却（已补用途行）；中途停下那一轮的最后一张不记（多半就是它让这一轮停下的）")
        void keyValueAndAbortingVerdictAreNotCooled() {
            snapshot(table("t_a", col("k", "varchar(32)")), table("t_kv", col("k", "varchar(32)")),
                    table("t_b", col("k", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            detectorReturns(TableShapeDetector.OUT_ABORTED,
                    verdict("t_a", TableShapeDetector.Outcome.NOT_KEY_VALUE),
                    verdict("t_kv", TableShapeDetector.Outcome.KEY_VALUE),
                    verdict("t_b", TableShapeDetector.Outcome.UNDECIDABLE));

            service.validate(CONNECTOR_ID);

            verify(bucket(ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_a", "hash-t_a")))
                    .set(any(), anyLong(), any(TimeUnit.class));
            verify(bucket(ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_kv", "hash-t_kv")), never())
                    .set(any(), anyLong(), any(TimeUnit.class));
            verify(bucket(ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_b", "hash-t_b")), never())
                    .set(any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("冷却键：表名大小写不敏感；结构指纹变了就是另一个键")
        void shapeCooldownKeyTracksStructure() {
            String k = ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "T_X", "h1");
            assertEquals(k, ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_x", "h1"));
            assertFalse(k.equals(ConnectorSemanticDeriveService.shapeCooldownKey(CONNECTOR_ID, "t_x", "h2")));
            assertFalse(k.equals(ConnectorSemanticDeriveService.addedCooldownKey(CONNECTOR_ID, "t_x", "h1")),
                    "两种冷却不能共用一个键");
        }
    }

    // ================================================================ 6. K-2

    @Nested
    @DisplayName("6. probed_with_sample_values 记的是分组探查真的跑了没有（K-2）")
    class GroupedProbeFlag {

        private void polymorphicVerdict(boolean groupedProbeRan) {
            snapshot(table("role_resource", col("resource_type", "varchar(16)"), col("resource_id", "bigint")),
                    table("agent", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_NONE, "关联 agent.id。本条未经数据验证。", Map.of())));
            when(semanticService.allowsSampleValues(CONNECTOR_ID)).thenReturn(true);
            SemanticJoinValidator.JoinVerdict v = SemanticJoinValidator.JoinVerdict.builder()
                    .fromObject("role_resource").fromColumn("resource_id").toObject("agent").toColumn("id")
                    .verified(ConnectorSemanticService.V_UNDECIDABLE).probed(true).basis("判不出来")
                    .joinKind(SemanticJoinValidator.KIND_POLYMORPHIC).discriminatorColumn("resource_type")
                    .groupedProbeRan(groupedProbeRan).build();
            when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenAnswer(inv -> {
                SemanticJoinValidator.ProbeProgress p = inv.getArgument(4);
                p.onDecided(v, 1, 1);
                return SemanticJoinValidator.JoinValidationResult.builder().outcome(SemanticJoinValidator.OUT_RAN)
                        .note("测试").verdicts(List.of(v)).probeCount(1).build();
            });
        }

        /** 急停开着、或判别列名没过 PII：档位允许，却一条分组探查都没发。按档位记 true，就再也不会回头补判别值。 */
        @Test
        @DisplayName("★ 档位允许、分组探查没跑：记 false，之后仍可重探")
        void notRunStaysReprobeable() {
            polymorphicVerdict(false);

            service.validate(CONNECTOR_ID);

            assertEquals(Boolean.FALSE, detailOf(updates().get(0).getDetailJson())
                    .get(ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES));
        }

        @Test
        @DisplayName("分组探查真的跑了：记 true，不再重探")
        void ranIsRecorded() {
            polymorphicVerdict(true);

            service.validate(CONNECTOR_ID);

            assertEquals(Boolean.TRUE, detailOf(updates().get(0).getDetailJson())
                    .get(ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES));
        }
    }

    // ================================================================ 8. 提示名单

    @Nested
    @DisplayName("8. 提示名单（「允许重写」）不被悄悄丢掉")
    class Hints {

        @Test
        @DisplayName("★ 结构变了重推：这一批装不下的 ADDED 名字也一起带回去，最后仍按「允许重写」补写")
        void snapshotMovedRetryKeepsOverflowHints() {
            List<ConnectorSchema> first = new ArrayList<>();
            List<ConnectorSchema> moved = new ArrayList<>();
            List<String> added = new ArrayList<>();
            for (int i = 0; i <= ConnectorSemanticDeriveService.ADDED_BATCH_MAX; i++) {
                String name = String.format("t%02d", i);
                added.add(name);
                first.add(table(name, col("id", "bigint")));
                moved.add(table(name, col("id", i == 0 ? "int" : "bigint")));
            }
            // 计划一次、推导开工一次都是旧结构，落库前复核时结构变了；之后一直是新结构。
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(first, first, moved);
            when(semanticMapper.selectList(any())).thenReturn(List.of());
            modelOutputs("{\"objects\":[]}");

            enqueueAndDrain(added);

            List<Collection<String>> updatable = updatableArgs();
            assertEquals(List.of("t30"), new ArrayList<>(updatable.get(updatable.size() - 1)),
                    "装不下的那张是报过 ADDED 的，重推之后它的「允许重写」不能丢");
        }

        @Test
        @DisplayName("★ 认领之前读连接抖了一下：不冲出 drain，这一批原地重试，提示名单还在")
        void connectionReadHiccupBeforeClaimDoesNotDropTheBatch() {
            snapshot(table("t_new", col("id", "bigint")));
            when(semanticMapper.selectList(any())).thenReturn(List.of());
            when(connectionMapper.selectById(CONNECTOR_ID))
                    .thenReturn(conn)
                    .thenThrow(new IllegalStateException("db hiccup"))
                    .thenReturn(conn);
            modelOutputs("{\"objects\":[{\"name\":\"t_new\",\"gloss\":\"新表\",\"evidence\":\"NAME\"}]}");

            enqueueAndDrain(List.of("t_new"));

            verify(claudeService, times(1)).messages(any());
            assertEquals(List.of("t_new"), new ArrayList<>(updatableArgs().get(0)));
        }

        @Test
        @DisplayName("★ 这一圈读不出连接：提示名单停到一边，不原地空转；下一次刷新带回去，仍按「允许重写」补写")
        void planReadFailureParksHintsUntilNextRefresh() {
            snapshot(table("t_new", col("id", "bigint")));
            when(semanticMapper.selectList(any())).thenReturn(List.of());
            when(connectionMapper.selectById(CONNECTOR_ID))
                    .thenThrow(new IllegalStateException("db down"))
                    .thenReturn(conn);
            modelOutputs("{\"objects\":[{\"name\":\"t_new\",\"gloss\":\"新表\",\"evidence\":\"NAME\"}]}");

            enqueueAndDrain(List.of("t_new"));
            verify(claudeService, never()).messages(any());

            enqueueAndDrain(List.of());

            verify(claudeService, times(1)).messages(any());
            assertEquals(List.of("t_new"), new ArrayList<>(updatableArgs().get(0)));
        }
    }

    @Test
    @DisplayName("mergeStructure：判别列变了，旧判别值一并作废")
    void mergeStructureDropsValueWhenDiscriminatorChanges() {
        Map<String, Object> d = new LinkedHashMap<>(Map.of("join_kind", "POLYMORPHIC",
                "discriminator_column", "resource_type", "discriminator_value", "AGENT"));
        assertTrue(ConnectorSemanticDeriveService.mergeStructure(d,
                Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "owner_type")));
        assertNull(d.get("discriminator_value"));
        assertNotNull(d.get("discriminator_column"));
    }
}

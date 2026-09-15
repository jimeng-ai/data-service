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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推导侧补的两块接线：新出现的表只跑 S2（增量推导），以及验证阶段里的表形态测量。
 */
class ConnectorSemanticAddedDeriveTest {

    private static final Long CONNECTOR_ID = 7L;
    private static final String TENANT = "t1";

    private ConnectorSchemaService schemaService;
    private ConnectorSemanticService semanticService;
    private ConnectionMapper connectionMapper;
    private ClaudeService claudeService;
    private SemanticJoinValidator joinValidator;
    private SemanticValueProfiler valueProfiler;
    private ConnectorSemanticMapper semanticMapper;
    private ThreadPoolTaskExecutor semanticStageExecutor;
    private TableShapeDetector shapeDetector;
    private ConnectorSemanticDeriveService service;
    private Connection conn;

    @BeforeEach
    void setUp() {
        schemaService = mock(ConnectorSchemaService.class);
        semanticService = mock(ConnectorSemanticService.class);
        connectionMapper = mock(ConnectionMapper.class);
        claudeService = mock(ClaudeService.class);
        joinValidator = mock(SemanticJoinValidator.class);
        valueProfiler = mock(SemanticValueProfiler.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        semanticStageExecutor = mock(ThreadPoolTaskExecutor.class);
        shapeDetector = mock(TableShapeDetector.class);
        service = new ConnectorSemanticDeriveService(schemaService, semanticService, connectionMapper,
                claudeService, new ConnectorProperties(), mock(SemanticSqlCorpusReader.class), joinValidator,
                valueProfiler, semanticMapper, mock(ConnectorAuditService.class),
                mock(ThreadPoolTaskExecutor.class), semanticStageExecutor, shapeDetector,
                mock(org.redisson.api.RedissonClient.class));

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
        when(valueProfiler.profile(anyLong(), any())).thenReturn(SemanticValueProfiler.ValueProfile.builder()
                .tierAllowed(false).tierStatement(SemanticValueProfiler.NOT_ENABLED_NOTE).build());
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

    private static ConnectorSchema snapshotOf(String name, FieldDetail... fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType("BASE TABLE");
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(
                    new ObjectDetail(name, "BASE TABLE", null, List.of(fields), Map.of()))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** orders / users 是老表，t_refund 是这次刷新新出现的。 */
    private List<ConnectorSchema> snapshot() {
        return List.of(
                snapshotOf("orders", col("id", "bigint"), col("cid", "bigint"), col("amt", "decimal(12,2)")),
                snapshotOf("users", col("id", "bigint"), col("name", "varchar(64)")),
                snapshotOf("t_refund", col("id", "bigint"), col("order_id", "bigint"), col("amt", "decimal(12,2)")));
    }

    private void modelOutputs(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        when(claudeService.messages(any())).thenReturn(Map.of("content", List.of(block)));
    }

    private static final String MODEL_OUTPUT = """
            {"objects":[{"name":"t_refund","gloss":"退款单","evidence":"NAME","table_shape":"DETAIL"},
                        {"name":"orders","gloss":"订单","evidence":"NAME"}],
             "fields":[{"object":"t_refund","name":"amt","gloss":"退款金额","evidence":"NAME"}],
             "joins":[{"object":"t_refund","column":"order_id","to_object":"orders","to_column":"id","evidence":"NAME"},
                      {"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME"}],
             "ambiguities":[{"term":"退款率","question":"按单数还是按金额？","applies_to":["t_refund"]},
                            {"term":"客单价","question":"含不含运费？","applies_to":["orders"]}]}
            """;

    private Connection lastStatusWrite() {
        ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper, atLeastOnce()).update(cap.capture(), any());
        List<Connection> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(ConnectorSemantic row) {
        try {
            return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prompt(ClaudeService claude) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(claude).messages(cap.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) cap.getValue().get("messages");
        return String.valueOf(messages.get(0).get("content"));
    }

    // ================================================================ 增量推导

    @Nested
    @DisplayName("§8 ADDED：新对象入队，只跑 S2")
    class Added {

        @BeforeEach
        void upsertOk() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot());
            when(semanticService.upsertInferred(eq(CONNECTOR_ID), any(), any()))
                    .thenReturn(new ConnectorSemanticService.UpsertResult(4, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("★ 绝不整层替换；只把新表范围内的行交给 upsert，可原地覆盖的只有新表")
        void neverReplacesAndScopesToNewTables() {
            modelOutputs(MODEL_OUTPUT);

            ConnectorSemanticDeriveService.AddedOutcome out = service.deriveAdded(CONNECTOR_ID, Set.of("T_REFUND"));

            assertTrue(out.result().isOk(), out.result().getNote());
            verify(semanticService, never()).replaceInferred(any(), any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConnectorSemantic>> rows = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> updatable = ArgumentCaptor.forClass(Collection.class);
            verify(semanticService).upsertInferred(eq(CONNECTOR_ID), rows.capture(), updatable.capture());
            assertEquals(List.of("t_refund"), new ArrayList<>(updatable.getValue()), "请求名按快照写法对齐");
            List<String> keys = rows.getValue().stream()
                    .map(r -> r.getScope() + ":" + r.getObjectName() + "." + r.getFieldName() + r.getTerm()).toList();
            assertEquals(List.of("OBJECT:t_refund.", "FIELD:t_refund.amt", "JOIN:t_refund.order_id", "CAVEAT:.退款率"),
                    keys, "老表的表用途、两端都是老表的关系、只和老表有关的口径问题都不进库");
            // 新表的表形态照样按枚举落、标 MODEL。
            assertEquals("MODEL", detailOf(rows.getValue().get(0)).get("table_shape_source"));

            String p = prompt(claudeService);
            assertTrue(p.contains("待补写的表（为它们写说明书）") && p.contains("## t_refund"), p);
            assertTrue(p.contains("- orders：id bigint"), "老表要给模型看，否则写不出新表指向老表的关系：" + p);
        }

        @Test
        @DisplayName("★ 成功后回到 READY、盖 synced_at，并派发一轮只限这批表的验证")
        void restoresReadyAndDispatchesScopedValidation() {
            modelOutputs(MODEL_OUTPUT);

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            Connection last = lastStatusWrite();
            assertEquals("READY", last.getSemanticStatus());
            assertNotNull(last.getSemanticSyncedAt());
            assertTrue(last.getSemanticNote().startsWith("新增 1 张表"), last.getSemanticNote());
            assertTrue(last.getSemanticNote().contains("覆盖 3/3 个对象"), "上一版结论接在后面，不丢");
            verify(semanticStageExecutor).execute(any(Runnable.class));
        }

        /** FAILED 上写一批行再写 READY，会把「全量没生成出来」盖成「可用」。 */
        @Test
        @DisplayName("★ 说明书没生成成功时不跑：不认领、不叫模型、不写库")
        void skipsUnlessReady() {
            conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_FAILED);

            ConnectorSemanticDeriveService.AddedOutcome out = service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            assertFalse(out.result().isOk());
            verify(connectionMapper, never()).update(any(), any());
            verify(claudeService, never()).messages(any());
            verify(semanticService, never()).upsertInferred(any(), any(), any());
        }

        @Test
        @DisplayName("★ 认领抢不到（全量推导在跑）就不跑")
        void skipsWhenClaimLost() {
            when(connectionMapper.update(any(), any())).thenReturn(0);

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            verify(claudeService, never()).messages(any());
            verify(semanticService, never()).upsertInferred(any(), any(), any());
        }

        @Test
        @DisplayName("★ 推导期间列真的变了：本批不落库，交回去用新结构重推")
        void structureMovedMeansRequeue() {
            List<ConnectorSchema> changed = new ArrayList<>(snapshot());
            changed.set(2, snapshotOf("t_refund", col("id", "bigint"), col("order_id", "varchar(32)")));
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot(), changed);
            modelOutputs(MODEL_OUTPUT);

            ConnectorSemanticDeriveService.AddedOutcome out = service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            assertTrue(out.snapshotMoved());
            verify(semanticService, never()).upsertInferred(any(), any(), any());
            assertEquals("READY", lastStatusWrite().getSemanticStatus(), "说明书一行没动，状态回 READY");
        }

        @Test
        @DisplayName("失败回到 READY、不盖 synced_at，原因写进 note")
        void failureRestoresReadyWithoutStamp() {
            when(claudeService.messages(any())).thenThrow(new RuntimeException("上游超时"));

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            Connection last = lastStatusWrite();
            assertEquals("READY", last.getSemanticStatus());
            assertNull(last.getSemanticSyncedAt());
            assertTrue(last.getSemanticNote().contains("失败"), last.getSemanticNote());
        }

        /** 刷新几次、每次冒出几张新表：并成一批、一次模型调用。 */
        @Test
        @DisplayName("★ 同一条连接上连着入队：只派发一次，后台一批一次模型调用")
        void batchesQueuedNames() {
            modelOutputs("{\"objects\":[],\"fields\":[],\"joins\":[],\"ambiguities\":[]}");

            service.deriveAddedAsync(CONNECTOR_ID, List.of("t_refund"));
            service.deriveAddedAsync(CONNECTOR_ID, List.of("users"));

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(semanticStageExecutor, times(1)).execute(task.capture());
            task.getValue().run();

            String p = prompt(claudeService);
            assertTrue(p.contains("## t_refund") && p.contains("## users"), p);
        }
    }

    // ================================================================ 表形态测量

    @Nested
    @DisplayName("验证阶段：表形态测量的落库")
    class Shape {

        @BeforeEach
        void snapshotReady() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot());
        }

        private ConnectorSemantic objectRow(long id, String table, String source, Map<String, Object> detail) {
            ConnectorSemantic r = new ConnectorSemantic();
            r.setId(id);
            r.setScope(ConnectorSemanticService.SCOPE_OBJECT);
            r.setObjectName(table);
            r.setFieldName("");
            r.setTerm("");
            r.setSource(source);
            try {
                r.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(detail));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return r;
        }

        private TableShapeDetector.ShapeVerdict verdict(String table, TableShapeDetector.Outcome outcome, TableShape guess) {
            return TableShapeDetector.ShapeVerdict.builder().objectName(table).outcome(outcome)
                    .nameColumn("metric_code").valueColumn("metric_value").modelGuess(guess)
                    .basis("采样 6000 行…").probed(true).build();
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<List<TableShapeDetector.Target>> detectorReturns(TableShapeDetector.ShapeVerdict... vs) {
            ArgumentCaptor<List<TableShapeDetector.Target>> targets = ArgumentCaptor.forClass(List.class);
            when(shapeDetector.detect(eq(CONNECTOR_ID), targets.capture(), any())).thenAnswer(inv -> {
                TableShapeDetector.Progress p = inv.getArgument(2);
                for (TableShapeDetector.ShapeVerdict v : vs) {
                    p.onDecided(v);
                }
                return TableShapeDetector.ShapeRun.builder().outcome(TableShapeDetector.OUT_RAN).tierAllowed(true)
                        .verdicts(List.of(vs)).statements(vs.length).note("表形态测量 " + vs.length + " 张").build();
            });
            return targets;
        }

        @Test
        @DisplayName("★ 测出键值对表：写 MEASURED、kv 两列，模型原来的判断留在 model_guess")
        void measuredKeyValueOverridesAndKeepsModelGuess() {
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(objectRow(1L, "orders",
                    ConnectorSemanticService.SOURCE_INFERRED, Map.of("table_shape", "DETAIL", "table_shape_source", "MODEL"))));
            detectorReturns(verdict("orders", TableShapeDetector.Outcome.KEY_VALUE, TableShape.DETAIL));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertEquals(1, r.getShapeOverrides());
            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(u.capture());
            Map<String, Object> d = detailOf(u.getValue());
            assertEquals("KEY_VALUE", d.get("table_shape"));
            assertEquals("MEASURED", d.get("table_shape_source"));
            assertEquals("DETAIL", d.get("table_shape_model_guess"));
            assertEquals("metric_code", d.get("kv_name_column"));
            assertEquals("metric_value", d.get("kv_value_column"));
            assertNotNull(d.get("table_shape_measurement"));
        }

        /** 名列是按结构挑的，挑错了列测出的「不是」说明不了任何事。 */
        @Test
        @DisplayName("★ 测不出键值对形态：不改模型的判断，只留测量痕迹")
        void notKeyValueNeverOverridesModel() {
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(objectRow(1L, "orders",
                    ConnectorSemanticService.SOURCE_INFERRED, Map.of("table_shape", "KEY_VALUE", "table_shape_source", "MODEL"))));
            detectorReturns(verdict("orders", TableShapeDetector.Outcome.NOT_KEY_VALUE, TableShape.KEY_VALUE));

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(u.capture());
            Map<String, Object> d = detailOf(u.getValue());
            assertEquals("KEY_VALUE", d.get("table_shape"));
            assertEquals("MODEL", d.get("table_shape_source"));
            assertFalse(d.containsKey("table_shape_model_guess"));
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d.get("table_shape_measurement");
            assertEquals("NOT_KEY_VALUE", m.get("outcome"));
        }

        @Test
        @DisplayName("★ 测过的表不重测；人工确认的表用途不进候选")
        void measuredAndHumanRowsAreNotTargets() {
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(
                    objectRow(1L, "orders", ConnectorSemanticService.SOURCE_INFERRED,
                            Map.of("table_shape", "KEY_VALUE", "table_shape_source", "MEASURED")),
                    objectRow(2L, "users", ConnectorSemanticService.SOURCE_HUMAN, Map.of())));
            ArgumentCaptor<List<TableShapeDetector.Target>> targets = detectorReturns();

            service.validate(CONNECTOR_ID);

            assertEquals(List.of("t_refund"),
                    targets.getValue().stream().map(t -> t.table().getObjectName()).toList());
        }

        @Test
        @DisplayName("测出键值对表但说明书里没有这张表的用途行：补一行（依据 DATA）")
        void createsObjectRowForMeasuredKeyValue() {
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            detectorReturns(verdict("t_refund", TableShapeDetector.Outcome.KEY_VALUE, null));

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> ins = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(ins.capture());
            assertEquals(ConnectorSemanticService.SCOPE_OBJECT, ins.getValue().getScope());
            assertEquals(ConnectorSemanticService.EV_DATA, ins.getValue().getEvidence());
            assertEquals(TENANT, ins.getValue().getTenantId());
            assertEquals("MEASURED", detailOf(ins.getValue()).get("table_shape_source"));
            assertFalse(detailOf(ins.getValue()).containsKey("table_shape_model_guess"), "模型没说过，就没有可留的原话");
        }
    }

    // ================================================================ 验证范围与补跑

    @Nested
    @DisplayName("验证阶段：范围与补跑")
    class Scope {

        @Test
        @DisplayName("★ 增量派来的验证只挑这批表：S3 候选、S4 的表都收窄")
        void scopeNarrowsCandidatesAndProfiledTables() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot());
            ConnectorSemantic oldJoin = new ConnectorSemantic();
            oldJoin.setId(1L);
            oldJoin.setScope(ConnectorSemanticService.SCOPE_JOIN);
            oldJoin.setObjectName("orders");
            oldJoin.setFieldName("cid");
            oldJoin.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            oldJoin.setVerified(ConnectorSemanticService.V_NONE);
            oldJoin.setDetailJson("{\"to_object\":\"users\",\"to_column\":\"id\"}");
            ConnectorSemantic newJoin = new ConnectorSemantic();
            newJoin.setId(2L);
            newJoin.setScope(ConnectorSemanticService.SCOPE_JOIN);
            newJoin.setObjectName("t_refund");
            newJoin.setFieldName("order_id");
            newJoin.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            newJoin.setVerified(ConnectorSemanticService.V_NONE);
            newJoin.setDetailJson("{\"to_object\":\"orders\",\"to_column\":\"id\"}");
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(oldJoin, newJoin));
            when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenReturn(
                    SemanticJoinValidator.JoinValidationResult.builder().outcome(SemanticJoinValidator.OUT_RAN)
                            .note("测试").verdicts(List.of()).build());

            service.validate(CONNECTOR_ID, Set.of("t_refund"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SemanticJoinValidator.JoinCandidate>> cands = ArgumentCaptor.forClass(List.class);
            verify(joinValidator).validate(eq(CONNECTOR_ID), cands.capture(), any(), any(), any());
            assertEquals(1, cands.getValue().size());
            assertEquals("t_refund", cands.getValue().get(0).getFromObject());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConnectorSchema>> profiled = ArgumentCaptor.forClass(List.class);
            verify(valueProfiler).profile(eq(CONNECTOR_ID), profiled.capture());
            assertEquals(List.of("t_refund"), profiled.getValue().stream().map(ConnectorSchema::getObjectName).toList());
        }

        /** 正在跑的那一轮开跑时就读完了语义行，看不见之后新写的行；新派发的又被闸挡回。两边都以为对方会做。 */
        @Test
        @DisplayName("★ 验证进行中又来一次：不是丢掉，而是这一轮收尾时补派一轮")
        @SuppressWarnings("unchecked")
        void rerunIsDispatchedAfterRunningRound() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot());
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            Set<Long> validating = (Set<Long>) ReflectionTestUtils.getField(service, "validating");

            validating.add(CONNECTOR_ID);
            ConnectorSemanticDeriveService.ValidationResult deferred = service.validate(CONNECTOR_ID, Set.of("t_refund"));
            assertFalse(deferred.isOk());
            verify(semanticStageExecutor, never()).execute(any(Runnable.class));

            validating.remove(CONNECTOR_ID);
            service.validate(CONNECTOR_ID);

            verify(semanticStageExecutor, times(1)).execute(any(Runnable.class));
        }
    }
}

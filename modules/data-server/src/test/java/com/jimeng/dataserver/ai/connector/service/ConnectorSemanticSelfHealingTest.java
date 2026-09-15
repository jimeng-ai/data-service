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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两块自愈的接线：增量补写<b>按数据算覆盖</b>（不靠进程内存里的通知），以及验证阶段把<b>结构形态</b>真的落到行上。
 *
 * <p>与 {@code ConnectorSemanticStageWiringTest} 同一个立场：验证器、值域、表形态测量全部 mock，这里只钉接线。
 * 这一层的失效方式全是静默的——一张表永远没有说明书、一条多态外键永远以普通关系的身份进 joins、
 * 一张模型写不出东西的表每次刷新都花一次模型调用——没有一处会报错。
 */
class ConnectorSemanticSelfHealingTest {

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
        redisson = mock(RedissonClient.class);
        buckets = new HashMap<>();
        when(redisson.getBucket(anyString())).thenAnswer(inv ->
                buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        service = new ConnectorSemanticDeriveService(schemaService, semanticService, connectionMapper,
                claudeService, properties, mock(SemanticSqlCorpusReader.class), joinValidator, valueProfiler,
                semanticMapper, mock(ConnectorAuditService.class), mock(ThreadPoolTaskExecutor.class),
                semanticStageExecutor, mock(TableShapeDetector.class), redisson);

        conn = new Connection();
        conn.setId(CONNECTOR_ID);
        conn.setName("客户生产库");
        conn.setKind("MYSQL");
        conn.setTenantId(TENANT);
        conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_READY);
        conn.setSemanticNote("覆盖 60/200 个对象：表用途 60");
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(conn);
        when(connectionMapper.update(any(), any())).thenReturn(1);
        when(semanticMapper.updateById(any())).thenReturn(1);
        when(semanticService.upsertInferred(eq(CONNECTOR_ID), any(), any()))
                .thenReturn(new ConnectorSemanticService.UpsertResult(1, 0, 0, 0, 0));
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

    private static ConnectorSchema table(String name, FieldDetail... fields) {
        return tableWithExtra(name, Map.of(), fields);
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

    /** 这些表已经有表用途行。 */
    private void covered(String... tables) {
        List<ConnectorSemantic> rows = new ArrayList<>();
        for (String t : tables) {
            ConnectorSemantic r = new ConnectorSemantic();
            r.setScope(ConnectorSemanticService.SCOPE_OBJECT);
            r.setObjectName(t);
            r.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            rows.add(r);
        }
        when(semanticMapper.selectList(any())).thenReturn(rows);
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(ConnectorSemantic row) {
        try {
            return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void modelOutputs(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        when(claudeService.messages(any())).thenReturn(Map.of("content", List.of(block)));
    }

    @SuppressWarnings("unchecked")
    private List<String> prompts() {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(claudeService, atLeastOnce()).messages(cap.capture());
        List<String> out = new ArrayList<>();
        for (Map<String, Object> body : cap.getAllValues()) {
            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
            out.add(String.valueOf(messages.get(0).get("content")));
        }
        return out;
    }

    /** 「待补写的表」那一段。老表出现在后面的参照列表里是对的，不能算「送进去写说明书」。 */
    private static String targetSection(String prompt) {
        int a = prompt.indexOf("======== 待补写的表");
        int b = prompt.indexOf("======== 已有表");
        assertTrue(a >= 0 && b > a, prompt);
        return prompt.substring(a, b);
    }

    private RBucket<Object> bucket(String table) {
        return buckets.computeIfAbsent(
                ConnectorSemanticDeriveService.addedCooldownKey(CONNECTOR_ID, table, "hash-" + table),
                k -> {
                    @SuppressWarnings("unchecked")
                    RBucket<Object> b = mock(RBucket.class);
                    return b;
                });
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Set<String>> pending() {
        return (Map<Long, Set<String>>) ReflectionTestUtils.getField(service, "pendingAdded");
    }

    /** 入队，并把刚派发出去的那个后台任务就地跑完。 */
    private void enqueueAndDrain(Collection<String> added) {
        service.deriveAddedAsync(CONNECTOR_ID, added);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(semanticStageExecutor, atLeastOnce()).execute(task.capture());
        List<Runnable> all = task.getAllValues();
        all.get(all.size() - 1).run();
        TenantContext.set(TENANT);
    }

    // ================================================================ 增量补写：覆盖按数据算

    @Nested
    @DisplayName("增量补写：覆盖按数据算，不靠进程内存里的通知")
    class Coverage {

        /** 通知只活在进程内存里，每次部署清零；刷新才是可靠的心跳。 */
        @Test
        @DisplayName("★ 名单为空也跑一圈：有列却没有表用途行的表被补上；已覆盖的、没取到列的不送")
        void emptyNotificationStillCoversUncoveredTables() {
            snapshot(table("orders", col("id", "bigint")),
                    table("users", col("id", "bigint"), col("name", "varchar(64)")),
                    table("t_denied"));
            covered("orders");
            modelOutputs("{\"objects\":[{\"name\":\"users\",\"gloss\":\"用户\",\"evidence\":\"NAME\"}]}");

            enqueueAndDrain(List.of());

            List<String> ps = prompts();
            assertEquals(1, ps.size());
            String targets = targetSection(ps.get(0));
            assertTrue(targets.contains("## users"), targets);
            assertFalse(targets.contains("## orders"), "已经有表用途行的表不送");
            assertFalse(targets.contains("## t_denied"), "没取到列的表模型写不出东西，送进去就是每个冷却期白叫一次模型");
            assertFalse(ps.get(0).contains("新出现了"), "对一直在库里的表说「新出现了」是一句会被模型当依据的假话");
        }

        /** 没覆盖到的表身上可能已经有值域和验证过的关系，重写会把它们打回原形，而且每个冷却期重演一次。 */
        @Test
        @DisplayName("★ 一直没覆盖到的表只补缺、不重写；报过 ADDED 的表才允许原地重写")
        void onlyAddedTablesAreRewritable() {
            snapshot(table("t_new", col("id", "bigint")), table("users", col("id", "bigint")));
            covered();
            modelOutputs("{\"objects\":[]}");

            enqueueAndDrain(List.of("T_NEW"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> updatable = ArgumentCaptor.forClass(Collection.class);
            verify(semanticService).upsertInferred(eq(CONNECTOR_ID), any(), updatable.capture());
            assertEquals(List.of("t_new"), new ArrayList<>(updatable.getValue()));
            assertTrue(targetSection(prompts().get(0)).contains("## users"), "没覆盖的表同一次调用里一起补");
        }

        @Test
        @DisplayName("★ 冷却中的表不送；送过的记冷却（TTL 按小时）")
        void cooldownSkipsAndMarks() {
            snapshot(table("users", col("id", "bigint")), table("shops", col("id", "bigint")));
            covered();
            modelOutputs("{\"objects\":[]}");
            when(bucket("users").isExists()).thenReturn(true);

            enqueueAndDrain(List.of());

            String targets = targetSection(prompts().get(0));
            assertTrue(targets.contains("## shops"), targets);
            assertFalse(targets.contains("## users"), "冷却还在的表不送");
            verify(bucket("shops")).set(any(), eq(24L), eq(TimeUnit.HOURS));
            verify(bucket("users"), never()).set(any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("冷却键：表名大小写不敏感；结构指纹变了就是另一个键（客户补了注释的那一刻正该重试）")
        void cooldownKeyFoldsNameAndTracksStructure() {
            String k = ConnectorSemanticDeriveService.addedCooldownKey(CONNECTOR_ID, "T_Refund", "h1");
            assertEquals(k, ConnectorSemanticDeriveService.addedCooldownKey(CONNECTOR_ID, "t_refund", "h1"));
            assertNotEquals(k, ConnectorSemanticDeriveService.addedCooldownKey(CONNECTOR_ID, "t_refund", "h2"));
            assertTrue(k.startsWith(ConnectorSemanticDeriveService.ADDED_COOLDOWN_KEY_PREFIX + CONNECTOR_ID + ":"), k);
        }

        /**
         * 冷却防的死循环：模型对一张表只给得出 GUESS，它就永远「没覆盖」。Redis 挂了冷却不起作用，
         * 同一轮 drain 里再来一次入队（刷新又触发了一次），不兜底的话它会被立刻再送一遍、一遍又一遍。
         */
        @Test
        @DisplayName("★ Redis 挂了按放行，但同一轮 drain 里模型写不出东西的表绝不送第二次")
        void redisDownStillNeverLoopsWithinOneDrain() {
            snapshot(table("kv_x", col("k", "varchar(32)"), col("v", "varchar(64)")));
            covered();
            doThrow(new IllegalStateException("redis down")).when(redisson).getBucket(anyString());
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", "{\"objects\":[{\"name\":\"kv_x\",\"gloss\":\"猜的\",\"evidence\":\"GUESS\"}]}");
            when(claudeService.messages(any())).thenAnswer(inv -> {
                service.deriveAddedAsync(CONNECTOR_ID, List.of());
                return Map.of("content", List.of(block));
            });

            enqueueAndDrain(List.of());

            verify(claudeService, times(1)).messages(any());
            assertTrue(pending().isEmpty(), "转完补的那一圈就停，不留尾巴");
        }

        @Test
        @DisplayName("★ 一批装不下的在同一轮 drain 里接着补，不等下一次刷新")
        void overflowBeyondBatchCapIsDrainedInTheSameRound() {
            List<ConnectorSchema> rows = new ArrayList<>();
            for (int i = 0; i <= ConnectorSemanticDeriveService.ADDED_BATCH_MAX; i++) {
                rows.add(table(String.format("t%02d", i), col("id", "bigint")));
            }
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(rows);
            covered();
            modelOutputs("{\"objects\":[]}");

            enqueueAndDrain(List.of());

            List<String> ps = prompts();
            assertEquals(2, ps.size());
            assertTrue(targetSection(ps.get(0)).contains("## t29"));
            assertFalse(targetSection(ps.get(0)).contains("## t30"));
            assertTrue(targetSection(ps.get(1)).contains("## t30"));
            assertFalse(targetSection(ps.get(1)).contains("## t00"), "这一轮送过的不再送");
        }

        /** 从前的丢表点：超出 max-digest-chars 的那部分不会再入队，只能等一个可能永远不来的 ADDED 事件。 */
        @Test
        @DisplayName("★ 摘要字符上限挤出去的表重新入队，并且两张都记了冷却")
        void digestOverflowIsRequeued() {
            properties.getSemantic().setMaxDigestChars(10);
            snapshot(table("aaa", col("id", "bigint")), table("bbb", col("id", "bigint")));
            covered();
            modelOutputs("{\"objects\":[]}");

            enqueueAndDrain(List.of());

            List<String> ps = prompts();
            assertEquals(2, ps.size());
            assertTrue(targetSection(ps.get(0)).contains("## aaa"));
            assertFalse(targetSection(ps.get(0)).contains("## bbb"));
            assertTrue(targetSection(ps.get(1)).contains("## bbb"));
            verify(bucket("aaa")).set(any(), anyLong(), any(TimeUnit.class));
            verify(bucket("bbb")).set(any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("★ 说明书没 READY：不叫模型、不问 Redis、不认领，drain 自己停")
        void notReadyDoesNothing() {
            conn.setSemanticStatus(ConnectorSemanticDeriveService.SEM_FAILED);
            snapshot(table("users", col("id", "bigint")));
            covered();

            enqueueAndDrain(List.of("users"));

            verify(claudeService, never()).messages(any());
            verify(redisson, never()).getBucket(anyString());
            verify(connectionMapper, never()).update(any(), any());
            assertTrue(pending().isEmpty());
        }

        @Test
        @DisplayName("★ 表用途行读失败：一张都不算没覆盖，只补报了 ADDED 的表")
        void coverageReadFailureFallsBackToAddedOnly() {
            snapshot(table("t_new", col("id", "bigint")), table("users", col("id", "bigint")));
            when(semanticMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));
            modelOutputs("{\"objects\":[]}");

            enqueueAndDrain(List.of("t_new"));

            String targets = targetSection(prompts().get(0));
            assertTrue(targets.contains("## t_new"));
            assertFalse(targets.contains("## users"), "读不出来 = 不知道，不能当成「一张都没覆盖」把整个快照送进去");
        }

        @Test
        @DisplayName("★ 认领被全量推导抢走：一张没送就不原地转圈，也不记冷却")
        void lostClaimDoesNotSpin() {
            snapshot(table("users", col("id", "bigint")));
            covered();
            when(connectionMapper.update(any(), any())).thenReturn(0);

            enqueueAndDrain(List.of());

            verify(claudeService, never()).messages(any());
            verify(connectionMapper, times(1)).update(any(), any());
            verify(bucket("users"), never()).set(any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("派发被拒：提示名单留着、写一句 note，下一次刷新入队时重新派发")
        void rejectedDispatchKeepsHintsAndRetriesOnNextRefresh() {
            doThrow(new IllegalStateException("队列满")).doNothing()
                    .when(semanticStageExecutor).execute(any(Runnable.class));

            service.deriveAddedAsync(CONNECTOR_ID, List.of("t_new"));

            assertEquals(Set.of("t_new"), pending().get(CONNECTOR_ID));
            verify(connectionMapper, times(1)).update(any(), any());

            service.deriveAddedAsync(CONNECTOR_ID, List.of());

            verify(semanticStageExecutor, times(2)).execute(any(Runnable.class));
        }

        /** 每次刷新都会触发一圈例行检查。它被拒时写 note，就是一条不需要任何人做任何事的假警报。 */
        @Test
        @DisplayName("例行的空检查派发被拒：不写 note")
        void rejectedEmptyPassWritesNoNote() {
            doThrow(new IllegalStateException("队列满")).when(semanticStageExecutor).execute(any(Runnable.class));

            service.deriveAddedAsync(CONNECTOR_ID, List.of());

            verify(connectionMapper, never()).update(any(), any());
        }
    }

    // ================================================================ 验证阶段：结构形态

    @Nested
    @DisplayName("验证阶段：结构形态真的落到行上")
    class StructureForm {

        private ConnectorSchema roleResource() {
            return table("role_resource", col("id", "bigint"), col("resource_type", "varchar(16)"),
                    col("resource_id", "bigint"));
        }

        private ConnectorSemantic joinRow(long id, String obj, String col, String toObj, String toCol,
                                          String verified, Map<String, Object> extra) {
            ConnectorSemantic r = new ConnectorSemantic();
            r.setId(id);
            r.setConnectorId(CONNECTOR_ID);
            r.setTenantId(TENANT);
            r.setScope(ConnectorSemanticService.SCOPE_JOIN);
            r.setObjectName(obj);
            r.setFieldName(col);
            r.setTerm("");
            r.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            r.setVerified(verified);
            r.setGloss("关联 " + toObj + "." + toCol + "。本条未经数据验证。");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("to_object", toObj);
            d.put("to_column", toCol);
            d.put("basis", "未经数据验证");
            d.putAll(extra);
            r.setDetailJson(json(d));
            return r;
        }

        private SemanticJoinValidator.JoinVerdict polymorphicNotProbed() {
            return SemanticJoinValidator.JoinVerdict.builder()
                    .fromObject("role_resource").fromColumn("resource_id").toObject("agent").toColumn("id")
                    .verified(ConnectorSemanticService.V_NONE).probed(false)
                    .basis("未经数据验证").reason("未启用派生统计，本条未做采样验证")
                    .joinKind(SemanticJoinValidator.KIND_POLYMORPHIC).discriminatorColumn("resource_type")
                    .careReason("疑似多态外键：role_resource.resource_id 指向哪张表可能由同表的 resource_type 决定。")
                    .build();
        }

        /** @param callback 真实实现只对真的决出来的结论回调；TIER_BLOCKED / DISABLED 整批一次都不回调 */
        private void validatorReturns(String outcome, boolean callback, SemanticJoinValidator.JoinVerdict... verdicts) {
            List<SemanticJoinValidator.JoinVerdict> list = List.of(verdicts);
            when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenAnswer(inv -> {
                SemanticJoinValidator.ProbeProgress p = inv.getArgument(4);
                if (callback && p != null) {
                    for (int i = 0; i < list.size(); i++) {
                        p.onDecided(list.get(i), i + 1, list.size());
                    }
                }
                return SemanticJoinValidator.JoinValidationResult.builder()
                        .outcome(outcome).note("测试").verdicts(list).probeCount(0).build();
            });
        }

        @Test
        @DisplayName("★ 组合键判定接上线：五参 validate 拿到从同一份快照解出来的唯一键")
        void uniqueKeysFromTheSameSnapshotReachTheValidator() {
            Map<String, Object> extra = Map.of(SemanticJoinValidator.DETAIL_UNIQUE_KEYS,
                    List.of(Map.of("columns", List.of("shop_id", "code"))));
            snapshot(table("orders", col("id", "bigint"), col("sku_code", "varchar(32)")),
                    tableWithExtra("sku", extra, col("shop_id", "bigint"), col("code", "varchar(32)")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "orders", "sku_code", "sku", "code",
                    ConnectorSemanticService.V_NONE, Map.of())));
            validatorReturns(SemanticJoinValidator.OUT_RAN, true);

            service.validate(CONNECTOR_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, List<List<String>>>> keys = ArgumentCaptor.forClass(Map.class);
            verify(joinValidator).validate(eq(CONNECTOR_ID), any(), any(), keys.capture(), any());
            assertNotNull(keys.getValue(), "传 null 等于组合键判定在线上不生效");
            assertEquals(List.of(List.of("shop_id", "code")), keys.getValue().get("sku"));
        }

        /** 第 1 档连接恰恰是最需要结构警告的客户：它们永远不会有探查结论，结构判定是它们唯一能拿到的那句提醒。 */
        @Test
        @DisplayName("★ 第 1 档整批不回调：多态外键的结构照样落库，verified / basis / gloss 一个字不碰")
        void unprobedStructureLandsWithoutTouchingVerdictColumns() {
            snapshot(roleResource(), table("agent", col("id", "bigint")));
            ConnectorSemantic row = joinRow(1L, "role_resource", "resource_id", "agent", "id",
                    ConnectorSemanticService.V_NONE,
                    Map.of("source_sql", "来自视图 v_rr 的定义", "basis", "来自视图 v_rr 的定义；未经数据验证"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
            validatorReturns(SemanticJoinValidator.OUT_TIER_BLOCKED, false, polymorphicNotProbed());

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertEquals(1, r.getJoinNotProbed());
            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(u.capture());
            assertEquals(1L, u.getValue().getId());
            assertNull(u.getValue().getVerified(), "没探查就不许写 verified，下一轮它仍然得是候选");
            assertNull(u.getValue().getGloss());
            Map<String, Object> d = detailOf(u.getValue());
            assertEquals("POLYMORPHIC", d.get("join_kind"));
            assertEquals("resource_type", d.get("discriminator_column"));
            assertNotNull(d.get("care_reason"));
            assertFalse(d.containsKey("discriminator_value"), "判别值只能来自第 3 档的真实探查");
            assertEquals("来自视图 v_rr 的定义；未经数据验证", d.get("basis"), "basis 不能被抹成泛泛的「未经数据验证」");
            assertEquals("来自视图 v_rr 的定义", d.get("source_sql"));
            assertTrue(r.getNote().contains("补标了结构形态"), r.getNote());
        }

        @Test
        @DisplayName("回调里已经落过的结构，扫返回全集时不重复写")
        void structureWrittenInCallbackIsNotWrittenTwice() {
            snapshot(roleResource(), table("agent", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_NONE, Map.of())));
            validatorReturns(SemanticJoinValidator.OUT_ABORTED, true, polymorphicNotProbed());

            service.validate(CONNECTOR_ID);

            verify(semanticMapper, times(1)).updateById(any());
        }

        /**
         * 线上已有的关系全是结构判定上线之前验的，而验证阶段按规矩跳过已决的行——不补，它们永远是普通关系。
         * 这里的结论是 UNDECIDABLE：它与多态外键不矛盾，只补结构、不打回（与结构矛盾的 CONFIRMED / REJECTED 会被打回，
         * 见 {@code ConnectorSemanticPipelineSilentFailureTest}）。
         */
        @Test
        @DisplayName("★ 已决过、没有 join_kind 的存量关系：structureOnly 补标，一条探查都不发")
        void decidedLegacyRowsGetStructureBackfilledWithoutProbing() {
            snapshot(roleResource(), table("agent", col("id", "bigint")));
            ConnectorSemantic legacy = joinRow(1L, "role_resource", "resource_id", "agent", "id",
                    ConnectorSemanticService.V_UNDECIDABLE, Map.of());
            ConnectorSemantic marked = joinRow(2L, "orders", "cid", "users", "id",
                    ConnectorSemanticService.V_CONFIRMED, Map.of("join_kind", "SIMPLE"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(legacy, marked));
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("join_kind", SemanticJoinValidator.KIND_POLYMORPHIC);
            patch.put("discriminator_column", "resource_type");
            patch.put("care_reason", "疑似多态外键");
            when(joinValidator.structureOnly(any(), any(), any())).thenReturn(patch);

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
            ArgumentCaptor<SemanticJoinValidator.JoinCandidate> cand =
                    ArgumentCaptor.forClass(SemanticJoinValidator.JoinCandidate.class);
            verify(joinValidator, times(1)).structureOnly(cand.capture(), any(), any());
            assertEquals("role_resource", cand.getValue().getFromObject(), "已经带 join_kind 的行不再算");
            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(u.capture());
            assertEquals(1L, u.getValue().getId());
            assertNull(u.getValue().getVerified(), "已决的结论原样留着");
            assertEquals("POLYMORPHIC", detailOf(u.getValue()).get("join_kind"));
            assertEquals(0, r.getProbeCount());
        }

        /**
         * 第 3 档上判别值记不下来是常态之一（semval 实测自增 id 重叠时 AGENT 2/2、KB 2/2 分不出来）。
         * 只看「没有判别值」就重探，这些行每一轮验证都会把客户的库再扫一遍，而结论一条不变。
         */
        @Test
        @DisplayName("★ 档位调到第 3 档：当时读不到取值的多态外键重新成为候选；当时已能读取值的、已有判别值的不重探")
        void tierUpgradeReprobesOnlyRowsDecidedWithoutSampleValues() {
            snapshot(roleResource(), table("agent", col("id", "bigint")), table("kb", col("id", "bigint")));
            ConnectorSemantic decidedAtTier2 = joinRow(1L, "role_resource", "resource_id", "agent", "id",
                    ConnectorSemanticService.V_UNDECIDABLE, Map.of("join_kind", "POLYMORPHIC",
                            "discriminator_column", "resource_type",
                            ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES, false));
            ConnectorSemantic decidedAtTier3 = joinRow(2L, "role_resource", "resource_id", "kb", "id",
                    ConnectorSemanticService.V_UNDECIDABLE, Map.of("join_kind", "POLYMORPHIC",
                            "discriminator_column", "resource_type",
                            ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES, true));
            ConnectorSemantic withValue = joinRow(3L, "role_resource", "owner_id", "agent", "id",
                    ConnectorSemanticService.V_CONFIRMED, Map.of("join_kind", "POLYMORPHIC",
                            "discriminator_column", "owner_type", "discriminator_value", "AGENT"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(decidedAtTier2, decidedAtTier3, withValue));
            when(semanticService.allowsSampleValues(CONNECTOR_ID)).thenReturn(true);
            validatorReturns(SemanticJoinValidator.OUT_RAN, true);

            service.validate(CONNECTOR_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SemanticJoinValidator.JoinCandidate>> cands = ArgumentCaptor.forClass(List.class);
            verify(joinValidator).validate(eq(CONNECTOR_ID), cands.capture(), any(), any(), any());
            assertEquals(1, cands.getValue().size());
            assertEquals("agent", cands.getValue().get(0).getToObject());
            assertEquals("resource_id", cands.getValue().get(0).getFromColumn());
        }

        @Test
        @DisplayName("档位不允许读取值：已决过的多态外键不重探")
        void noReprobeBelowSampleValuesTier() {
            snapshot(roleResource(), table("agent", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_UNDECIDABLE,
                    Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "resource_type"))));
            when(semanticService.allowsSampleValues(CONNECTOR_ID)).thenReturn(false);

            service.validate(CONNECTOR_ID);

            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
        }

        /** K-2：档位允许不等于分组探查跑了（急停、判别列名没过 PII）。按档位记 true，这条多态外键就再也不会补上判别值。 */
        @Test
        @DisplayName("★ 多态外键决出结论时记下分组探查真的跑了没有——没跑的，档位允许时据此重探一次")
        void polymorphicVerdictRecordsWhetherSampleValuesWereAllowed() {
            snapshot(roleResource(), table("agent", col("id", "bigint")));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(joinRow(1L, "role_resource", "resource_id",
                    "agent", "id", ConnectorSemanticService.V_NONE, Map.of())));
            when(semanticService.allowsSampleValues(CONNECTOR_ID)).thenReturn(true);
            validatorReturns(SemanticJoinValidator.OUT_RAN, true, SemanticJoinValidator.JoinVerdict.builder()
                    .fromObject("role_resource").fromColumn("resource_id").toObject("agent").toColumn("id")
                    .verified(ConnectorSemanticService.V_UNDECIDABLE).probed(true)
                    .basis("采样 100 个取值，命中 100（包含率 100.0%）——疑似多态外键")
                    .joinKind(SemanticJoinValidator.KIND_POLYMORPHIC).discriminatorColumn("resource_type")
                    .careReason("疑似多态外键").build());

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> u = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(u.capture());
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, u.getValue().getVerified());
            assertEquals(Boolean.FALSE,
                    detailOf(u.getValue()).get(ConnectorSemanticDeriveService.KEY_PROBED_WITH_SAMPLE_VALUES));
        }
    }
}

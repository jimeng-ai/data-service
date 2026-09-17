package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeHandlerRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticGenerationOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectorSemanticStagedMapper stagedMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaService schemaService;
    private SemanticGenerationLease lease;
    private SemanticConnectionClaim claim;
    private SemanticGenerationHeartbeat heartbeat;
    private SemanticSlicePlanner planner;
    private SemanticSliceDispatcher dispatcher;
    private SemanticGenerationFinalizer finalizer;
    private SingleCallSemanticGenerator singleCall;
    private ThreadPoolTaskExecutor executor;
    private ConnectorProperties properties;
    private TestRuntime runtime;
    private RecordingTransactionManager txManager;
    private SemanticGenerationOrchestrator orchestrator;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(Connection.class, ConnectorSchema.class,
                ConnectorSemanticGeneration.class, ConnectorSemanticGenerationTable.class,
                ConnectorSemanticStaged.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaService = mock(ConnectorSchemaService.class);
        lease = mock(SemanticGenerationLease.class);
        claim = mock(SemanticConnectionClaim.class);
        heartbeat = mock(SemanticGenerationHeartbeat.class);
        planner = mock(SemanticSlicePlanner.class);
        dispatcher = mock(SemanticSliceDispatcher.class);
        finalizer = mock(SemanticGenerationFinalizer.class);
        singleCall = mock(SingleCallSemanticGenerator.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        properties = new ConnectorProperties();
        properties.getSemantic().getAgent().setEnabled(true);
        properties.getSemantic().getAgent().setSliceMaxRetries(1);
        properties.getSemantic().getAgent().setRetryBackoffSec(1);
        runtime = new TestRuntime();
        txManager = new RecordingTransactionManager();
        orchestrator = new SemanticGenerationOrchestrator(generationMapper, tableMapper, stagedMapper,
                connectionMapper, schemaService, lease, claim, heartbeat, planner, dispatcher,
                new SliceOutcomeHandlerRegistry(), finalizer, singleCall, properties,
                new SemanticGenerationNotes(), txManager, executor, runtime);

        when(generationMapper.update(any(), any())).thenReturn(1);
        when(tableMapper.update(any(), any())).thenReturn(1);
        when(claim.release(any(), any(), any(), anyBoolean())).thenReturn(true);
        when(heartbeat.checkpoint()).thenReturn(
                new SemanticGenerationHeartbeat.Checkpoint(false, false, false));
        when(finalizer.finish(any(), any(Double.class)))
                .thenReturn(SemanticGenerationFinalizer.FinishResult.DONE);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("全局租约未取得时不扫描队列，且恢复调用线程 TenantContext")
    void leaseOccupiedDoesNotScan() {
        TenantContext.set("caller");
        when(lease.acquire("lease-1")).thenReturn(false);

        orchestrator.drainLoop();

        verify(generationMapper, never()).selectList(any());
        verify(lease, never()).release(anyString());
        assertEquals("caller", TenantContext.get());
        assertFalse(orchestrator.isDraining());
    }

    @Test
    @DisplayName("kick 被执行器拒绝后 draining 复位，下一次 kick 仍能提交")
    void rejectedKickCanBeRetried() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new TaskRejectedException("full");
            }
            return null;
        }).when(executor).execute(any(Runnable.class));

        orchestrator.kick();
        assertFalse(orchestrator.isDraining());
        orchestrator.kick();

        verify(executor, times(2)).execute(any(Runnable.class));
        assertTrue(orchestrator.isDraining());
    }

    @Test
    @DisplayName("TOKEN_CAP 只算本轮三类 token，total_tables 为 0 时不触发")
    void tokenCapUsesResumeBaselineAndZeroTotalIsUnlimited() {
        ConnectorSemanticGeneration generation = generation();
        generation.setTotalTables(2);
        generation.setInputTokens(400L);
        generation.setOutputTokens(300L);
        generation.setCacheWriteTokens(200L);
        generation.setCacheReadTokens(99_999L);
        generation.setTokensAtResume(300L);

        assertTrue(orchestrator.tokenCapReached(generation, 300L));
        generation.setTotalTables(0);
        assertFalse(orchestrator.tokenCapReached(generation, 1L));
    }

    @Test
    @DisplayName("对账新增有效/无效表、恢复 SKIPPED、清残留 DISPATCHED，并重算批次计数")
    void reconcileAppliesStateMatrix() {
        ConnectorSemanticGeneration generation = generation();
        ConnectorSemanticGenerationTable skipped = table(1L, "revived", "SKIPPED");
        ConnectorSemanticGenerationTable dispatched = table(2L, "orders", "DISPATCHED");
        ConnectorSemanticGenerationTable missing = table(3L, "gone", "DONE");
        when(tableMapper.selectList(any())).thenReturn(List.of(skipped, dispatched, missing));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of(
                schema("revived", true, 1), schema("orders", true, 2),
                schema("new_valid", true, 3), schema("new_invalid", false, 4)));
        when(tableMapper.insert(any())).thenReturn(1);

        assertTrue(orchestrator.reconcile(generation));

        assertEquals("PENDING", skipped.getStatus());
        assertEquals("PENDING", dispatched.getStatus());
        assertEquals("REMOVED", missing.getStatus());
        ArgumentCaptor<ConnectorSemanticGenerationTable> inserted =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        verify(tableMapper, times(2)).insert(inserted.capture());
        assertEquals(List.of("PENDING", "SKIPPED"), inserted.getAllValues().stream()
                .map(ConnectorSemanticGenerationTable::getStatus).toList());
        assertEquals(3, generation.getTotalTables());
        assertEquals(1, generation.getSkippedTables());
        assertEquals(1, generation.getRemovedTables());
    }

    @Test
    @DisplayName("已开始片先按 DONE/GAVE_UP 算进展，再结算未提交表派发次数")
    void progressIsCountedBeforeSettlement() {
        ConnectorSemanticGeneration generation = generation();
        ConnectorSemanticGenerationTable row = table(1L, "orders", "DISPATCHED");
        row.setSliceNo(1);
        row.setDispatchCount(0);
        when(tableMapper.selectCount(any())).thenReturn(1L);
        when(tableMapper.selectList(any())).thenReturn(List.of(row));
        AtomicBoolean counted = new AtomicBoolean(false);
        when(tableMapper.selectCount(any())).thenAnswer(invocation -> {
            counted.set(true);
            return 1L;
        });
        doAnswer(invocation -> {
            ConnectorSemanticGenerationTable update = invocation.getArgument(0);
            if ("PENDING".equals(update.getStatus()) || "GAVE_UP".equals(update.getStatus())) {
                assertTrue(counted.get(), "settle happened before progress count");
            }
            return 1;
        }).when(tableMapper).update(any(), any());

        int progress = orchestrator.progressBeforeSettle(generation, 1);
        orchestrator.settleSlice(generation, new SemanticSlice(List.of(row), 10, 1),
                new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                        "success", null, null, 1, Set.of()), 3);

        assertEquals(1, progress);
        assertEquals("PENDING", row.getStatus());
        assertEquals(1, row.getDispatchCount());
    }

    @Test
    @DisplayName("done=0 的降级在释放全局租约后才提交单次推导，原因不会重复加前缀")
    void fallbackRunsOnlyAfterLeaseRelease() {
        ConnectorSemanticGeneration generation = generation();
        ConnectorSemanticGenerationTable pending = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        Connection connection = connection();
        SemanticSlice slice = new SemanticSlice(List.of(pending), 50, 1);

        when(lease.acquire("lease-1")).thenReturn(true);
        when(generationMapper.selectList(any())).thenReturn(List.of(generation), List.of());
        when(generationMapper.selectById(10L)).thenReturn(generation);
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.selectById(20L)).thenReturn(connection);
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of(schema));
        when(tableMapper.selectList(any())).thenReturn(List.of(pending));
        when(tableMapper.selectCount(any())).thenReturn(0L);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(dispatcher.runSlice(any(), eq(1), eq(slice), any())).thenReturn(
                new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                        "failed", "upstream_5xx", null, 1, Set.of()));
        when(singleCall.submit(any())).thenReturn(
                new GenerationAck(GeneratorKind.SINGLE_CALL, true, null, "ok"));

        orchestrator.drainLoop();

        InOrder order = inOrder(lease, singleCall);
        order.verify(lease).release("lease-1");
        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        order.verify(singleCall).submit(request.capture());
        assertEquals("连续多片没有进展：upstream_5xx", request.getValue().degradeReason());
        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("完整 QUEUED→RUNNING→READY：单批次逐片完成后调用 finalizer，随后继续扫描队列")
    void queuedBatchCompletesReady() {
        ConnectorSemanticGeneration generation = generation();
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        SemanticSlice slice = new SemanticSlice(List.of(row), 50, 1);
        String stamp = stamp(schema);
        when(lease.acquire("lease-1")).thenReturn(true);
        when(generationMapper.selectList(any())).thenReturn(List.of(generation), List.of());
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of(schema));
        when(tableMapper.selectList(any())).thenReturn(List.of(row));
        when(tableMapper.selectCount(any())).thenReturn(1L);
        when(generationMapper.selectById(10L)).thenReturn(generation);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice, new SemanticSlice(List.of(), 0, 1));
        when(dispatcher.runSlice(any(), eq(1), eq(slice), any())).thenAnswer(invocation -> {
            row.setStatus("DONE");
            row.setStructureStamp(stamp);
            return new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                    "success", null, null, 1, Set.of("deepseek-flash"));
        });
        when(finalizer.finish(any(), any(Double.class))).thenAnswer(invocation -> {
            generation.setStatus("READY");
            return SemanticGenerationFinalizer.FinishResult.DONE;
        });

        orchestrator.drainLoop();

        assertEquals("READY", generation.getStatus());
        verify(finalizer).finish(eq(generation), eq(0.0));
        verify(lease).release("lease-1");
    }

    @Test
    @DisplayName("QUEUED→RUNNING CAS 失败跳过该候选并继续下一批")
    void queuedCasFailureSkipsCandidate() {
        ConnectorSemanticGeneration lost = generation();
        lost.setId(11L);
        lost.setConnectorId(21L);
        ConnectorSemanticGeneration next = generation();
        next.setId(12L);
        next.setConnectorId(22L);
        when(lease.acquire("lease-1")).thenReturn(true);
        when(generationMapper.selectList(any())).thenReturn(List.of(lost), List.of(next), List.of());
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(generationMapper.update(any(), any())).thenReturn(0, 1, 1);
        when(connectionMapper.selectById(22L)).thenReturn(null);

        orchestrator.drainLoop();

        verify(connectionMapper, never()).selectById(21L);
        verify(connectionMapper).selectById(22L);
        assertEquals("CANCELLED", next.getStatus());
    }

    @Test
    @DisplayName("DONE 指纹连续变化第三次转 GAVE_UP，并清理该表 STAGED 暂存")
    void structureRetriesEventuallyGiveUp() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        generation.setMode("STAGED");
        ConnectorSemanticGenerationTable done = table(1L, "orders", "DONE");
        done.setStructureRetries(2);
        done.setStructureStamp("old");
        ConnectorSchema current = schema("orders", true, 1);
        when(tableMapper.selectList(any())).thenReturn(List.of(done));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of(current));

        assertTrue(orchestrator.reconcile(generation));

        assertEquals("GAVE_UP", done.getStatus());
        assertEquals(3, done.getStructureRetries());
        assertEquals(1, generation.getGaveUpTables());
        verify(stagedMapper).physicalDeleteOwnedRows("tenant-a", 10L, "orders");
    }

    @Test
    @DisplayName("NO_CALLBACK 连续两片后 done=0 回落，第一次按 NO_PROGRESS 退避")
    void twoNoCallbackSlicesFallBack() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        prepareRun(generation, List.of(schema), List.of(row));
        properties.getSemantic().getAgent().setSliceMaxRetries(5);
        SemanticSlice slice = new SemanticSlice(List.of(row), 20, 2);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(tableMapper.selectCount(any())).thenReturn(0L);
        when(dispatcher.runSlice(any(), anyInt(), eq(slice), any())).thenReturn(
                new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                        "success", null, null, 0, Set.of()));

        GenerationRequest fallback = orchestrator.runGeneration(generation);

        assertEquals(2, org.mockito.Mockito.mockingDetails(dispatcher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("runSlice")).count());
        assertEquals("回调没有到达 data-service，请检查 callback-base-url 或 sandbox 版本",
                fallback.degradeReason());
    }

    @Test
    @DisplayName("Option B：已有 DONE 时持续繁忙不回落，改为 INTERRUPTED")
    void doneTablesPreventFallback() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSchema doneSchema = schema("done", true, 1);
        ConnectorSchema pendingSchema = schema("pending", true, 2);
        ConnectorSemanticGenerationTable done = table(1L, "done", "DONE");
        done.setStructureStamp(stamp(doneSchema));
        ConnectorSemanticGenerationTable pending = table(2L, "pending", "PENDING");
        prepareRun(generation, List.of(doneSchema, pendingSchema), List.of(done, pending));
        SemanticSlice slice = new SemanticSlice(List.of(pending), 20, 1);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(tableMapper.selectCount(any())).thenReturn(0L);
        when(dispatcher.runSlice(any(), anyInt(), eq(slice), any())).thenReturn(
                new SliceRunResult(SliceRunKind.BUSY_EXHAUSTED, false, false, 503, 2,
                        null, null, null, 0, Set.of()));

        assertNull(orchestrator.runGeneration(generation));

        assertEquals("INTERRUPTED", generation.getStatus());
        assertEquals(GenerationReasonCode.SANDBOX_BUSY.name(), generation.getReasonCode());
        verify(singleCall, never()).submit(any());
    }

    @Test
    @DisplayName("dispatcher 因连接删除返回 ABORTED 后，Orchestrator 立即收口 CANCELLED")
    void abortedForDeletedConnectionIsCancelled() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        prepareRun(generation, List.of(schema), List.of(row));
        SemanticSlice slice = new SemanticSlice(List.of(row), 20, 1);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(dispatcher.runSlice(any(), anyInt(), eq(slice), any())).thenReturn(SliceRunResult.aborted());
        when(heartbeat.checkpoint()).thenReturn(
                new SemanticGenerationHeartbeat.Checkpoint(false, false, false),
                new SemanticGenerationHeartbeat.Checkpoint(false, true, false));

        orchestrator.runGeneration(generation);

        assertEquals("CANCELLED", generation.getStatus());
        assertEquals(GenerationReasonCode.CONNECTION_DELETED.name(), generation.getReasonCode());
    }

    @Test
    @DisplayName("dispatcher 因连接停用返回 ABORTED 后，Orchestrator 立即 INTERRUPTED 并归还 DISPATCHED")
    void abortedForDisabledConnectionIsInterrupted() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        prepareRun(generation, List.of(schema), List.of(row));
        SemanticSlice slice = new SemanticSlice(List.of(row), 20, 1);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(dispatcher.runSlice(any(), anyInt(), eq(slice), any())).thenReturn(SliceRunResult.aborted());
        when(heartbeat.checkpoint()).thenReturn(
                new SemanticGenerationHeartbeat.Checkpoint(false, false, false),
                new SemanticGenerationHeartbeat.Checkpoint(false, false, true));

        orchestrator.runGeneration(generation);

        assertEquals("INTERRUPTED", generation.getStatus());
        assertEquals(GenerationReasonCode.CONNECTION_DISABLED.name(), generation.getReasonCode());
        verify(tableMapper, org.mockito.Mockito.atLeastOnce()).update(any(), any());
        verify(claim).release(eq(20L), eq("FAILED"), anyString(), eq(false));
    }

    @Test
    @DisplayName("heartbeat lost 时不写任何终态，直接退出交给持有者/对账器")
    void heartbeatLostWritesNoTerminalState() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        prepareRun(generation, List.of(schema("orders", true, 1)), List.of());
        when(heartbeat.checkpoint()).thenReturn(
                new SemanticGenerationHeartbeat.Checkpoint(true, false, false));

        orchestrator.runGeneration(generation);

        assertEquals("RUNNING", generation.getStatus());
        assertEquals("lease-1", generation.getOwnerToken());
        verify(claim, never()).release(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("agent 开关在片边界关闭时 INTERRUPTED，不再派发")
    void agentSwitchOffStopsAtBoundary() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        prepareRun(generation, List.of(schema), List.of(row));
        SemanticSlice slice = new SemanticSlice(List.of(row), 20, 1);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(slice);
        when(tableMapper.selectCount(any())).thenReturn(1L);
        when(dispatcher.runSlice(any(), anyInt(), eq(slice), any())).thenAnswer(invocation -> {
            properties.getSemantic().getAgent().setEnabled(false);
            return new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                    "success", null, null, 1, Set.of());
        });

        orchestrator.runGeneration(generation);

        assertEquals("INTERRUPTED", generation.getStatus());
        assertEquals(GenerationReasonCode.AGENT_DISABLED.name(), generation.getReasonCode());
        verify(dispatcher, times(1)).runSlice(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("连接认领暂时繁忙退回 QUEUED，not_before 精确延后 2 分钟并保留续跑成果")
    void claimBusyRequeuesForTwoMinutes() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(null);

        orchestrator.runGeneration(generation);

        assertEquals("QUEUED", generation.getStatus());
        assertEquals(GenerationReasonCode.CLAIM_BUSY.name(), generation.getReasonCode());
        assertEquals(runtime.nowMillis() + 120_000L, generation.getNotBefore().getTime());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
    }

    @Test
    @DisplayName("连接认领连续繁忙超过 60 分钟原子 FAILED，并清理 STAGED 暂存")
    void claimBusyForHourFailsAndCleansStaged() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        generation.setMode("STAGED");
        generation.setCreateTime(new Date(runtime.nowMillis() - 3_600_001L));
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(null);

        orchestrator.runGeneration(generation);

        assertEquals("FAILED", generation.getStatus());
        assertEquals(GenerationReasonCode.CLAIM_BUSY.name(), generation.getReasonCode());
        verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
        assertEquals(1, txManager.commits);
    }

    @Test
    @DisplayName("CLAIM_BUSY 到期后可再次排空并成功认领")
    void claimBusyCanBeClaimedAgainWhenDue() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        SemanticConnectionClaim.Credential credential =
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW.plusSeconds(121)), "READY");
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(null, credential);

        orchestrator.runGeneration(generation);
        runtime.advance(121_000L);
        generation.setStatus("RUNNING");
        generation.setOwnerToken("lease-2");
        generation.setNotBefore(null);
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of(schema("orders", true, 1)));
        when(tableMapper.selectList(any())).thenReturn(List.of());
        when(generationMapper.selectById(10L)).thenReturn(generation);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(new SemanticSlice(List.of(), 0, 0));

        orchestrator.runGeneration(generation);

        verify(claim, times(2)).claim(eq(20L), any(), anyString());
        verify(finalizer).finish(eq(generation), eq(0.0));
    }

    @Test
    @DisplayName("空快照补拉成功后继续编排，不写失败")
    void bootstrapSnapshotSuccessContinues() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSchema schema = schema("orders", true, 1);
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(20L))
                .thenReturn(List.of(), List.of(schema), List.of(schema));
        when(tableMapper.selectList(any())).thenReturn(List.of());
        when(tableMapper.insert(any())).thenReturn(1);
        when(generationMapper.selectById(10L)).thenReturn(generation);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(new SemanticSlice(List.of(), 0, 0));

        orchestrator.runGeneration(generation);

        verify(schemaService).refresh(20L);
        verify(finalizer).finish(eq(generation), eq(0.0));
        assertNull(generation.getReasonCode());
    }

    @Test
    @DisplayName("空快照补拉不适用写 FAILED(SNAPSHOT_NOT_APPLICABLE) 与连接 NOT_APPLICABLE")
    void bootstrapUnsupportedIsNotApplicable() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of());
        doThrow(new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED)).when(schemaService).refresh(20L);

        orchestrator.runGeneration(generation);

        assertEquals("FAILED", generation.getStatus());
        assertEquals(GenerationReasonCode.SNAPSHOT_NOT_APPLICABLE.name(), generation.getReasonCode());
        verify(claim).release(eq(20L), eq("NOT_APPLICABLE"), anyString(), eq(false));
        verify(singleCall, never()).submit(any());
    }

    @Test
    @DisplayName("空快照补拉异常写 FAILED(SNAPSHOT_REFRESH_FAILED)，不回落单次推导")
    void bootstrapFailureDoesNotFallback() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        when(connectionMapper.selectById(20L)).thenReturn(connection());
        when(claim.claim(eq(20L), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(20L, Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(20L)).thenReturn(List.of());
        doThrow(new IllegalStateException("jdbc://secret-host"))
                .when(schemaService).refresh(20L);

        orchestrator.runGeneration(generation);

        assertEquals("FAILED", generation.getStatus());
        assertEquals(GenerationReasonCode.SNAPSHOT_REFRESH_FAILED.name(), generation.getReasonCode());
        assertFalse(generation.getNote().contains("secret-host"));
        verify(singleCall, never()).submit(any());
    }

    @Test
    @DisplayName("跨租户队列扫描/CAS 用 runAsSystem，业务读取切到批次真实租户并最终恢复")
    void scanIsSystemButWritesUseRealTenant() {
        ConnectorSemanticGeneration generation = generation();
        AtomicInteger scans = new AtomicInteger();
        AtomicBoolean systemScan = new AtomicBoolean();
        AtomicBoolean tenantRead = new AtomicBoolean();
        when(lease.acquire("lease-1")).thenReturn(true);
        when(generationMapper.selectList(any())).thenAnswer(invocation -> {
            systemScan.set(systemScan.get() || TenantContext.isSystemMode());
            return scans.getAndIncrement() == 0 ? List.of(generation) : List.of();
        });
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.selectById(20L)).thenAnswer(invocation -> {
            tenantRead.set("tenant-a".equals(TenantContext.get()) && !TenantContext.isSystemMode());
            return null;
        });
        TenantContext.set("caller");

        orchestrator.drainLoop();

        assertTrue(systemScan.get());
        assertTrue(tenantRead.get());
        assertEquals("caller", TenantContext.get());
    }

    @Test
    @DisplayName("中断事务严格 connection→generation 锁序；连接 release CAS=0 时整体回滚")
    void interruptLocksConnectionFirstAndRollsBackOnReleaseLoss() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        prepareRun(generation, List.of(schema("orders", true, 1)), List.of());
        properties.getSemantic().getAgent().setEnabled(false);
        when(claim.release(any(), any(), any(), anyBoolean())).thenReturn(false);

        orchestrator.runGeneration(generation);

        InOrder order = inOrder(claim, generationMapper);
        order.verify(claim).lockRow(20L);
        order.verify(generationMapper).update(any(), any());
        order.verify(claim).release(eq(20L), eq("FAILED"), anyString(), eq(false));
        assertEquals(1, txManager.rollbacks);
    }

    @Test
    @DisplayName("中断时批次 owner CAS=0 立即回滚，且不尝试释放连接认领")
    void interruptRollsBackWhenBatchOwnerIsLost() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        prepareRun(generation, List.of(schema("orders", true, 1)), List.of());
        properties.getSemantic().getAgent().setEnabled(false);
        when(generationMapper.update(any(), any())).thenReturn(1, 0);

        orchestrator.runGeneration(generation);

        assertEquals(1, txManager.rollbacks);
        verify(claim, never()).release(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("每片重新读取实时 limits，超时后下一片 cap 减半")
    void liveLimitsAndTimeoutHalvesCap() {
        assertEquals(10, SemanticGenerationOrchestrator.clampSliceCap(10));
        assertEquals(5, SemanticGenerationOrchestrator.nextSliceCap(10, true));
        assertEquals(1, SemanticGenerationOrchestrator.nextSliceCap(1, true));
        assertEquals(10, SemanticGenerationOrchestrator.nextSliceCap(10, false));
    }

    @Test
    @DisplayName("limits 每片读取实时配置；首片超时后下一片表数 cap 减半")
    void limitsAreLiveAndTimeoutHalvesNextPlannedSlice() {
        ConnectorSemanticGeneration generation = generation();
        generation.setStatus("RUNNING");
        ConnectorSemanticGenerationTable row = table(1L, "orders", "PENDING");
        ConnectorSchema schema = schema("orders", true, 1);
        prepareRun(generation, List.of(schema), List.of(row));
        properties.getSemantic().getAgent().setSliceMaxTables(10);
        properties.getSemantic().getAgent().setSliceWallClockSec(120);
        properties.getSemantic().getAgent().setSliceMaxRetries(3);
        SemanticSlice first = new SemanticSlice(List.of(row), 20, 2);
        SemanticSlice second = new SemanticSlice(List.of(row), 20, 2);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(first, second,
                new SemanticSlice(List.of(), 0, 2));
        when(tableMapper.selectCount(any())).thenReturn(0L, 1L);
        AtomicInteger runs = new AtomicInteger();
        List<Integer> walls = new java.util.ArrayList<>();
        when(dispatcher.runSlice(any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            SemanticDispatchLimits limits = invocation.getArgument(3);
            walls.add(limits.wallClockSeconds());
            if (runs.getAndIncrement() == 0) {
                properties.getSemantic().getAgent().setSliceWallClockSec(600);
                return new SliceRunResult(SliceRunKind.ERROR, true, true, null, null,
                        "failed", "timeout", null, 1, Set.of());
            }
            row.setStatus("DONE");
            row.setStructureStamp(stamp(schema));
            return new SliceRunResult(SliceRunKind.COMPLETED, true, false, 200, null,
                    "success", null, null, 1, Set.of());
        });

        orchestrator.runGeneration(generation);

        ArgumentCaptor<Integer> caps = ArgumentCaptor.forClass(Integer.class);
        verify(planner, times(3)).plan(eq(generation), caps.capture(), anyInt());
        assertEquals(List.of(10, 5, 5), caps.getAllValues());
        assertEquals(List.of(120, 600), walls);
    }

    private void prepareRun(ConnectorSemanticGeneration generation,
                            List<ConnectorSchema> schemas,
                            List<ConnectorSemanticGenerationTable> tables) {
        when(connectionMapper.selectById(generation.getConnectorId())).thenReturn(connection());
        when(claim.claim(eq(generation.getConnectorId()), any(), anyString())).thenReturn(
                new SemanticConnectionClaim.Credential(generation.getConnectorId(),
                        Date.from(NOW), "READY"));
        when(schemaService.snapshotRowsByImportance(generation.getConnectorId())).thenReturn(schemas);
        when(tableMapper.selectList(any())).thenReturn(tables);
        when(generationMapper.selectById(generation.getId())).thenReturn(generation);
        when(planner.plan(any(), anyInt(), anyInt())).thenReturn(new SemanticSlice(List.of(), 0, 0));
    }

    private static String stamp(ConnectorSchema schema) {
        return ConnectorSemanticService.tableStamps(
                SemanticRowAssembler.parseFields(List.of(schema))).get(schema.getObjectName());
    }

    private static ConnectorSemanticGeneration generation() {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(10L);
        generation.setTenantId("tenant-a");
        generation.setConnectorId(20L);
        generation.setTriggeredBy(30L);
        generation.setTriggerKind(GenerationTrigger.MANUAL_REGENERATE.name());
        generation.setMode("DIRECT");
        generation.setStatus("QUEUED");
        generation.setOwnerToken("lease-1");
        generation.setCurrentSliceNo(0);
        generation.setTotalTables(1);
        generation.setDoneTables(0);
        generation.setCreateTime(Date.from(NOW.minusSeconds(60)));
        return generation;
    }

    private static ConnectorSemanticGenerationTable table(Long id, String name, String status) {
        ConnectorSemanticGenerationTable table = new ConnectorSemanticGenerationTable();
        table.setId(id);
        table.setTenantId("tenant-a");
        table.setConnectorId(20L);
        table.setGenerationId(10L);
        table.setObjectName(name);
        table.setObjectType("TABLE");
        table.setStatus(status);
        table.setImportanceRank(id.intValue());
        table.setDispatchCount(0);
        table.setSubmitCount(0);
        table.setStructureRetries(0);
        return table;
    }

    private static ConnectorSchema schema(String name, boolean valid, int rank) {
        ConnectorSchema schema = new ConnectorSchema();
        schema.setId((long) rank);
        schema.setTenantId("tenant-a");
        schema.setConnectorId(20L);
        schema.setObjectName(name);
        schema.setObjectType("TABLE");
        schema.setImportanceRank(rank);
        schema.setDetailJson(valid
                ? "{\"fields\":[{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false}]}"
                : "{\"fields\":[],\"extra\":{\"__error__\":\"describe failed\"}}");
        schema.setSyncedAt(Date.from(NOW));
        return schema;
    }

    private static Connection connection() {
        Connection connection = new Connection();
        connection.setId(20L);
        connection.setTenantId("tenant-a");
        connection.setStatus("ACTIVE");
        connection.setSemanticStatus("READY");
        return connection;
    }

    private static final class TestRuntime implements SemanticGenerationOrchestrator.OrchestratorRuntime {
        private long now = NOW.toEpochMilli();

        @Override
        public String newToken() {
            return "lease-1";
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            // deterministic, no wall-clock sleep in unit tests
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}

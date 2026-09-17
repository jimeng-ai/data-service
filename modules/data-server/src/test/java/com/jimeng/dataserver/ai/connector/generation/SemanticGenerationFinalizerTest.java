package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Date;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticGenerationFinalizerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticStagedMapper stagedMapper;
    private SemanticConnectionClaim claim;
    private SemanticGenerationHeartbeat heartbeat;
    private ConnectorSemanticService semanticService;
    private RecordingTransactionManager txManager;
    private SemanticGenerationFinalizer finalizer;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(Connection.class, ConnectorSchema.class, ConnectorSemantic.class,
                ConnectorSemanticGeneration.class, ConnectorSemanticGenerationTable.class,
                ConnectorSemanticStaged.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        claim = mock(SemanticConnectionClaim.class);
        heartbeat = mock(SemanticGenerationHeartbeat.class);
        semanticService = mock(ConnectorSemanticService.class);
        txManager = new RecordingTransactionManager();
        finalizer = new SemanticGenerationFinalizer(generationMapper, tableMapper, schemaMapper,
                semanticMapper, stagedMapper, claim, heartbeat, new SemanticGenerationNotes(),
                semanticService, txManager, Clock.fixed(NOW, ZoneOffset.UTC));

        when(generationMapper.update(any(), any())).thenReturn(1);
        when(claim.release(any(), any(), any(), any(Boolean.class))).thenReturn(true);
        stubTables(List.of());
        when(schemaMapper.selectList(any())).thenReturn(List.of());
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        when(stagedMapper.selectCount(any())).thenReturn(0L);
        when(stagedMapper.selectList(any())).thenReturn(List.of());
        when(stagedMapper.update(any(), any())).thenReturn(1);
        when(tableMapper.update(any(), any())).thenReturn(1);
        when(semanticMapper.selectCount(any())).thenReturn(0L);
        when(semanticMapper.moveStagedIn(any(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("零产出按 connection→generation 锁序写 FAILED(NO_OUTPUT)，STAGED 清暂存并恢复上一版")
    void zeroOutputFailsAndCleansStaged() {
        ConnectorSemanticGeneration generation = generation("STAGED");

        SemanticGenerationFinalizer.FinishResult result = finalizer.finish(generation, 0.0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, result);
        InOrder order = inOrder(heartbeat, claim, generationMapper, stagedMapper);
        order.verify(heartbeat).stop();
        order.verify(claim).lockRow(20L);
        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        order.verify(generationMapper).update(update.capture(), any());
        assertEquals("FAILED", update.getValue().getStatus());
        assertEquals(GenerationReasonCode.NO_OUTPUT.name(), update.getValue().getReasonCode());
        order.verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
        verify(claim).release(20L, "READY", update.getValue().getNote(), false);
        assertEquals(1, txManager.commits);
    }

    @Test
    @DisplayName("DIRECT 有产出先进入 FINALIZING，再在连接锁内 READY、盖 synced_at，且不触发任何验证派发")
    void directReadyIsAtomicAndDoesNotDispatchValidation() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT"), semantic("FIELD")));

        SemanticGenerationFinalizer.FinishResult result = finalizer.finish(generation, 0.0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, result);
        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.times(2)).update(updates.capture(), any());
        assertEquals("FINALIZING", updates.getAllValues().get(0).getStatus());
        assertEquals("READY", updates.getAllValues().get(1).getStatus());
        assertNull(updates.getAllValues().get(1).getOwnerToken());
        verify(claim).lockRow(20L);
        verify(claim).release(any(), org.mockito.ArgumentMatchers.eq("READY"),
                org.mockito.ArgumentMatchers.contains("覆盖 1/1 张表"), org.mockito.ArgumentMatchers.eq(true));
        verify(semanticService, org.mockito.Mockito.times(2)).requireOwned(20L);
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
    }

    @Test
    @DisplayName("STAGED 默认 max-gave-up=0，有一张放弃即中断并保留暂存")
    void stagedGaveUpInterruptsAndKeepsStaged() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        stubTables(List.of(table("DONE"), table("GAVE_UP")));

        SemanticGenerationFinalizer.FinishResult result = finalizer.finish(generation, 0.0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, result);
        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(update.capture(), any());
        assertEquals("INTERRUPTED", update.getValue().getStatus());
        assertEquals(GenerationReasonCode.GAVE_UP_RATIO.name(), update.getValue().getReasonCode());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
        verify(claim).release(any(), org.mockito.ArgumentMatchers.eq("READY"),
                org.mockito.ArgumentMatchers.contains("上一版"), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    @DisplayName("DIRECT 收尾连接 CAS 为 0 时整个事务回滚，批次 READY 不提交")
    void directLostClaimRollsBack() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(claim.release(any(), any(), any(), any(Boolean.class))).thenReturn(false);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
    }

    @Test
    @DisplayName("STAGED 预检发现 DONE 表结构变化时删本表暂存、重置计数并回 RUNNING")
    void stagedPrecheckChangedTableReturnsContinue() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSemanticGenerationTable done = table("DONE");
        done.setObjectName("orders");
        done.setStructureStamp("old-stamp");
        done.setDispatchCount(2);
        done.setSubmitCount(2);
        done.setStructureRetries(0);
        stubTables(List.of(done));
        when(schemaMapper.selectList(any())).thenReturn(List.of(schema("orders", "id", "bigint", 1L)));

        SemanticGenerationFinalizer.FinishResult result = finalizer.finish(generation, 0.0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.CONTINUE, result);
        ArgumentCaptor<ConnectorSemanticGenerationTable> tableUpdate =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        verify(tableMapper).update(tableUpdate.capture(), any());
        assertEquals("PENDING", tableUpdate.getValue().getStatus());
        assertEquals(1, tableUpdate.getValue().getStructureRetries());
        assertEquals(0, tableUpdate.getValue().getDispatchCount());
        assertEquals(0, tableUpdate.getValue().getSubmitCount());
        verify(stagedMapper).physicalDeleteOwnedRows("tenant-a", 10L, "orders");

        ArgumentCaptor<ConnectorSemanticGeneration> batchUpdates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.times(3)).update(batchUpdates.capture(), any());
        assertEquals("FINALIZING", batchUpdates.getAllValues().get(0).getStatus());
        assertEquals("RUNNING", batchUpdates.getAllValues().get(2).getStatus());
        verify(heartbeat, never()).stop();
    }

    @Test
    @DisplayName("STAGED 预检通过后锁连接、复核快照版本、删旧搬新清暂存并 READY")
    void stagedPromotesAtomically() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        ConnectorSemanticGenerationTable done = coveredTable(current);
        stubTables(List.of(done));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(2L);
        when(semanticMapper.selectCount(any())).thenReturn(3L);
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT"), semantic("FIELD")));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        InOrder order = inOrder(heartbeat, claim, semanticMapper, stagedMapper, generationMapper);
        order.verify(generationMapper).update(any(), any()); // RUNNING -> FINALIZING
        order.verify(heartbeat).stop();
        order.verify(claim).lockRow(20L);
        order.verify(generationMapper).update(any(), any()); // FINALIZING owner heartbeat CAS
        order.verify(semanticMapper).physicalDeleteInferredForPromote("tenant-a", 20L, 10L);
        order.verify(semanticMapper).moveStagedIn("tenant-a", 20L, 10L);
        order.verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
        order.verify(generationMapper).update(any(), any()); // READY
        verify(claim).release(any(), org.mockito.ArgumentMatchers.eq("READY"),
                org.mockito.ArgumentMatchers.contains("上一版机器生成的说明已替换"),
                org.mockito.ArgumentMatchers.eq(true));
        verify(semanticService, org.mockito.Mockito.times(2)).requireOwned(20L);
    }

    @Test
    @DisplayName("STAGED 空暂存但主表已有 INFERRED 时拒绝替换并记 FAILED(EMPTY_REPLACE)")
    void emptyReplaceFailsWithoutDeletingOldRows() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(current)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(0L);
        when(semanticMapper.selectCount(any())).thenReturn(2L);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.atLeast(3)).update(updates.capture(), any());
        ConnectorSemanticGeneration last = updates.getAllValues().get(updates.getAllValues().size() - 1);
        assertEquals("FAILED", last.getStatus());
        assertEquals(GenerationReasonCode.EMPTY_REPLACE.name(), last.getReasonCode());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
        verify(semanticMapper, never()).moveStagedIn(any(), any(), any());
        verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
        assertTrue(txManager.rollbacks >= 1);
    }

    @Test
    @DisplayName("快照版本连续冲突三次后中断 SNAPSHOT_CHURN，暂存保留")
    void snapshotVersionConflictsThreeTimesThenInterrupts() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema v1 = schema("orders", "id", "bigint", 1L);
        ConnectorSchema v2 = schema("orders", "id", "bigint", 2L);
        ConnectorSemanticGenerationTable done = coveredTable(v1);
        stubTables(List.of(done));
        when(schemaMapper.selectList(any())).thenReturn(
                List.of(v1), List.of(v2), List.of(v1), List.of(v2), List.of(v1), List.of(v2));
        when(stagedMapper.selectCount(any())).thenReturn(1L);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        // 三次 promote 事务均在快照复核处抛异常回滚，只有进入 FINALIZING 与最终中断两次写出事务。
        verify(generationMapper, org.mockito.Mockito.times(2)).update(updates.capture(), any());
        ConnectorSemanticGeneration last = updates.getAllValues().get(updates.getAllValues().size() - 1);
        assertEquals("INTERRUPTED", last.getStatus());
        assertEquals(GenerationReasonCode.SNAPSHOT_CHURN.name(), last.getReasonCode());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
    }

    @Test
    @DisplayName("STAGED 预检按当前两端列重算 JOIN anchor，不匹配时只把暂存关系标 STALE")
    void precheckMarksChangedJoinStale() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema orders = schema("orders", "user_id", "bigint", 1L);
        ConnectorSchema users = schema("users", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(orders)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(orders, users));
        ConnectorSemanticStaged join = new ConnectorSemanticStaged();
        join.setId(99L);
        join.setGenerationId(10L);
        join.setTenantId("tenant-a");
        join.setScope("JOIN");
        join.setObjectName("orders");
        join.setFieldName("user_id");
        join.setDetailJson("{\"to_object\":\"users\",\"to_column\":\"id\"}");
        join.setAnchorHash("outdated");
        when(stagedMapper.selectList(any())).thenReturn(List.of(join));
        when(stagedMapper.selectCount(any())).thenReturn(1L);

        finalizer.finish(generation, 0.0);

        ArgumentCaptor<ConnectorSemanticStaged> update =
                ArgumentCaptor.forClass(ConnectorSemanticStaged.class);
        verify(stagedMapper).update(update.capture(), any());
        assertEquals("STALE", update.getValue().getStatus());
    }

    @Test
    @DisplayName("STAGED 替换保留 SKIPPED 表旧行且 HUMAN/IMPORTED 永不删除")
    void stagedPromotionUsesPreservingDeleteContract() throws Exception {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        ConnectorSemanticGenerationTable skipped = table("SKIPPED");
        skipped.setObjectName("legacy_skipped");
        stubTables(List.of(coveredTable(current), skipped));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(1L);
        when(semanticMapper.selectCount(any())).thenReturn(1L);
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT")));

        finalizer.finish(generation, 0.0);

        verify(semanticMapper).physicalDeleteInferredForPromote("tenant-a", 20L, 10L);
        Delete delete = ConnectorSemanticMapper.class
                .getMethod("physicalDeleteInferredForPromote", String.class, Long.class, Long.class)
                .getAnnotation(Delete.class);
        String sql = String.join(" ", Arrays.asList(delete.value()));
        assertTrue(sql.contains("source = 'INFERRED'"), sql);
        assertTrue(sql.contains("'GAVE_UP','SKIPPED'"), sql);
        verify(semanticMapper, never()).physicalDeleteInferred(any());
    }

    @Test
    @DisplayName("调大 max-gave-up-ratio 后允许替换，并由 SQL 保留 GAVE_UP 表旧行")
    void raisedGaveUpRatioAllowsPromotion() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(current), table("GAVE_UP")));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(1L);
        when(semanticMapper.selectCount(any())).thenReturn(2L);
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT")));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.5));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.atLeast(3)).update(updates.capture(), any());
        assertEquals("READY", updates.getAllValues().get(updates.getAllValues().size() - 1).getStatus());
        verify(semanticMapper).physicalDeleteInferredForPromote("tenant-a", 20L, 10L);
        verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
    }

    @Test
    @DisplayName("STAGED 预检新增 GAVE_UP 时返回 CONTINUE，先让下一轮比例闸处理")
    void precheckNewGaveUpReturnsContinue() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema old = schema("orders", "id", "bigint", 1L);
        ConnectorSchema changed = schema("orders", "id", "varchar", 2L);
        ConnectorSemanticGenerationTable done = coveredTable(old);
        done.setStructureRetries(2);
        stubTables(List.of(done));
        when(schemaMapper.selectList(any())).thenReturn(List.of(changed));

        assertEquals(SemanticGenerationFinalizer.FinishResult.CONTINUE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGenerationTable> update =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        verify(tableMapper).update(update.capture(), any());
        assertEquals("GAVE_UP", update.getValue().getStatus());
        assertEquals(3, update.getValue().getStructureRetries());
        verify(stagedMapper).physicalDeleteOwnedRows("tenant-a", 10L, "orders");
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
        verify(heartbeat, never()).stop();
    }

    @Test
    @DisplayName("FINALIZING owner 已丢时不写 READY、不替换，promote 事务回滚")
    void lostOwnerDuringPromoteDoesNotWriteReady() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(current)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(1L);
        when(semanticMapper.selectCount(any())).thenReturn(1L);
        when(generationMapper.update(any(), any())).thenReturn(1, 0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.times(2)).update(updates.capture(), any());
        assertTrue(updates.getAllValues().stream().noneMatch(update -> "READY".equals(update.getStatus())));
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
        verify(semanticMapper, never()).moveStagedIn(any(), any(), any());
        verify(claim, never()).release(any(), any(), any(), any(Boolean.class));
        assertEquals(1, txManager.rollbacks);
    }

    @Test
    @DisplayName("进入收尾时 heartbeat 已丢失则直接停止且不写任何状态")
    void lostHeartbeatAtEntryWritesNothing() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(heartbeat.checkpoint()).thenReturn(checkpoint(true, false, false));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        verify(generationMapper, never()).update(any(), any());
        verify(tableMapper, never()).update(any(), any());
        verify(claim, never()).lockRow(any());
        verify(claim, never()).release(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("进入收尾时连接已删除则 CANCELLED，绝不写 READY")
    void deletedConnectionAtEntryCancels() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        stubTables(List.of(table("DONE")));
        when(heartbeat.checkpoint()).thenReturn(checkpoint(false, true, false));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(update.capture(), any());
        assertEquals("CANCELLED", update.getValue().getStatus());
        assertEquals(GenerationReasonCode.CONNECTION_DELETED.name(), update.getValue().getReasonCode());
        verify(stagedMapper).physicalDeleteByGeneration("tenant-a", 10L);
        verify(claim, never()).release(any(), any(), any(), any(Boolean.class));
        verify(semanticService, never()).requireOwned(any());
    }

    @Test
    @DisplayName("租户归属校验失败发生在 FINALIZING 与所有 STAGED 预检写之前")
    void ownershipFailurePreventsEveryFinalizerWrite() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        stubTables(List.of(table("DONE")));
        when(semanticService.requireOwned(20L))
                .thenThrow(new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在"));

        assertThrows(ServiceException.class, () -> finalizer.finish(generation, 0.0));

        verify(generationMapper, never()).update(any(), any());
        verify(tableMapper, never()).update(any(), any());
        verify(stagedMapper, never()).update(any(), any());
        verify(stagedMapper, never()).physicalDeleteOwnedRows(any(), any(), any());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
    }

    @Test
    @DisplayName("DIRECT 最终提交前发现连接停用则 INTERRUPTED，绝不写 READY")
    void disabledConnectionBeforeDirectCommitInterrupts() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(heartbeat.checkpoint()).thenReturn(
                checkpoint(false, false, false), checkpoint(false, false, true));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, org.mockito.Mockito.times(2)).update(updates.capture(), any());
        assertEquals("FINALIZING", updates.getAllValues().get(0).getStatus());
        assertEquals("INTERRUPTED", updates.getAllValues().get(1).getStatus());
        assertEquals(GenerationReasonCode.CONNECTION_DISABLED.name(),
                updates.getAllValues().get(1).getReasonCode());
        assertTrue(updates.getAllValues().stream().noneMatch(update -> "READY".equals(update.getStatus())));
    }

    @Test
    @DisplayName("STAGED 预检后 heartbeat 丢失则不进入替换事务")
    void lostHeartbeatAfterPrecheckDoesNotPromote() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(current)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(1L);
        when(heartbeat.checkpoint()).thenReturn(
                checkpoint(false, false, false), checkpoint(true, false, false));

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        ArgumentCaptor<ConnectorSemanticGeneration> updates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(updates.capture(), any());
        assertEquals("FINALIZING", updates.getValue().getStatus());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
        verify(semanticMapper, never()).moveStagedIn(any(), any(), any());
        verify(claim, never()).release(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("STAGED 旧 owner 在 DONE 重置事务起点失去 FINALIZING 后不得改表或删暂存")
    void staleOwnerCannotInvalidateDoneTable() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSemanticGenerationTable done = table("DONE");
        done.setId(31L);
        done.setTenantId("tenant-a");
        done.setObjectName("orders");
        done.setStructureStamp("old-stamp");
        done.setStructureRetries(0);
        stubTables(List.of(done));
        when(schemaMapper.selectList(any())).thenReturn(List.of(schema("orders", "id", "bigint", 1L)));
        when(generationMapper.update(any(), any())).thenReturn(1, 0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        verify(tableMapper, never()).update(any(), any());
        verify(stagedMapper, never()).physicalDeleteOwnedRows(any(), any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
    }

    @Test
    @DisplayName("STAGED 旧 owner 在 JOIN 标旧事务起点失去 FINALIZING 后不得改暂存")
    void staleOwnerCannotMarkJoinStale() {
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema orders = schema("orders", "user_id", "bigint", 1L);
        ConnectorSchema users = schema("users", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(orders)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(orders, users));
        ConnectorSemanticStaged join = new ConnectorSemanticStaged();
        join.setId(99L);
        join.setGenerationId(10L);
        join.setTenantId("tenant-a");
        join.setScope("JOIN");
        join.setObjectName("orders");
        join.setFieldName("user_id");
        join.setDetailJson("{\"to_object\":\"users\",\"to_column\":\"id\"}");
        join.setAnchorHash("outdated");
        when(stagedMapper.selectList(any())).thenReturn(List.of(join));
        when(generationMapper.update(any(), any())).thenReturn(1, 0);

        assertEquals(SemanticGenerationFinalizer.FinishResult.DONE, finalizer.finish(generation, 0.0));

        verify(stagedMapper, never()).update(any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredForPromote(any(), any(), any());
    }

    private static ConnectorSemanticGeneration generation(String mode) {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(10L);
        generation.setTenantId("tenant-a");
        generation.setConnectorId(20L);
        generation.setMode(mode);
        generation.setStatus("RUNNING");
        generation.setOwnerToken("owner-1");
        generation.setPrevSemanticStatus("READY");
        generation.setCurrentSliceNo(2);
        generation.setSliceCount(2);
        return generation;
    }

    private static SemanticGenerationHeartbeat.Checkpoint checkpoint(boolean lost,
                                                                     boolean deleted,
                                                                     boolean disabled) {
        return new SemanticGenerationHeartbeat.Checkpoint(lost, deleted, disabled);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubTables(List<ConnectorSemanticGenerationTable> rows) {
        when(tableMapper.selectList(any())).thenReturn(rows);
        List<ConnectorSemanticGenerationTable> doneRows = rows.stream()
                .filter(row -> "DONE".equals(row.getStatus()))
                .toList();
        when(tableMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<ConnectorSemanticGenerationTable> page = invocation.getArgument(0);
            int from = (int) Math.min(doneRows.size(), (page.getCurrent() - 1L) * page.getSize());
            int to = (int) Math.min(doneRows.size(), from + page.getSize());
            page.setRecords(doneRows.subList(from, to));
            return page;
        });
    }

    private static ConnectorSemanticGenerationTable table(String status) {
        ConnectorSemanticGenerationTable row = new ConnectorSemanticGenerationTable();
        row.setGenerationId(10L);
        row.setStatus(status);
        return row;
    }

    private static ConnectorSemanticGenerationTable coveredTable(ConnectorSchema schema) {
        ConnectorSemanticGenerationTable row = table("DONE");
        row.setId(31L);
        row.setTenantId("tenant-a");
        row.setConnectorId(20L);
        row.setObjectName(schema.getObjectName());
        row.setStructureRetries(0);
        row.setStructureStamp(ConnectorSemanticService.tableStamp(schema.getObjectName(),
                SemanticRowAssembler.parseFields(List.of(schema)).get(schema.getObjectName())));
        return row;
    }

    private static ConnectorSchema schema(String object, String field, String type, long syncedSecond) {
        ConnectorSchema row = new ConnectorSchema();
        row.setId((long) object.hashCode() & 0x7fffffffL);
        row.setTenantId("tenant-a");
        row.setConnectorId(20L);
        row.setObjectType("TABLE");
        row.setObjectName(object);
        row.setDetailJson("{\"fields\":[{\"name\":\"" + field + "\",\"type\":\"" + type
                + "\",\"nullable\":false,\"comment\":\"\",\"extra\":\"\"}]}");
        row.setSyncedAt(Date.from(Instant.ofEpochSecond(syncedSecond)));
        return row;
    }

    private static ConnectorSemantic semantic(String scope) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope(scope);
        row.setSource("INFERRED");
        return row;
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

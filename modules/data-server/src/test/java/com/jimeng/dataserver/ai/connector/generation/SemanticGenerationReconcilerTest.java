package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticGenerationReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T03:04:05Z");

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectionMapper connectionMapper;
    private SemanticConnectionClaim claim;
    private SemanticGenerationKick kick;
    private RecordingTransactionManager txManager;
    private SemanticGenerationReconciler reconciler;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Connection.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGeneration.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGenerationTable.class);
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        claim = mock(SemanticConnectionClaim.class);
        kick = mock(SemanticGenerationKick.class);
        txManager = new RecordingTransactionManager();
        reconciler = new SemanticGenerationReconciler(generationMapper, tableMapper, connectionMapper, claim,
                new SemanticGenerationNotes(), kick, txManager, Clock.fixed(NOW, ZoneOffset.UTC));

        scanRows(List.of(), List.of());
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(tableMapper.update(any(), any())).thenReturn(1);
        when(connectionMapper.update(any(), any())).thenReturn(1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("CommandLineRunner 启动执行一次，定时入口每 60 秒执行")
    void lifecycle() throws Exception {
        assertInstanceOf(CommandLineRunner.class, reconciler);
        Method scheduledMethod = SemanticGenerationReconciler.class.getMethod("reconcile");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals(60_000L, scheduled.fixedDelay());

        when(generationMapper.selectCount(any())).thenReturn(1L);
        reconciler.run();
        verify(kick).kick();
    }

    @Test
    @DisplayName("过期 RUNNING 在一个事务内先锁连接，再用观察状态和心跳 CAS 中断、重置表并按 DB claimAt 释放")
    @SuppressWarnings("unchecked")
    void staleDirectIsInterruptedInLockOrder() {
        ConnectorSemanticGeneration row = stale(101L, 41L, "tenant-a", "RUNNING", "DIRECT", null);
        scanRows(List.of(row), List.of());

        reconciler.reconcile();

        InOrder order = inOrder(claim, generationMapper, tableMapper, connectionMapper);
        order.verify(claim).lockRow(41L);

        ArgumentCaptor<ConnectorSemanticGeneration> generationUpdate =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> generationWhere =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        order.verify(generationMapper).update(generationUpdate.capture(), generationWhere.capture());
        assertEquals("INTERRUPTED", generationUpdate.getValue().getStatus());
        assertEquals("HEARTBEAT_LOST", generationUpdate.getValue().getReasonCode());
        generationWhere.getValue().getSqlSegment();
        assertTrue(generationWhere.getValue().getParamNameValuePairs().containsValue(101L));
        assertTrue(generationWhere.getValue().getParamNameValuePairs().containsValue("RUNNING"));
        assertTrue(generationWhere.getValue().getParamNameValuePairs().containsValue(row.getHeartbeatAt()));
        assertTrue(generationWhere.getValue().getSqlSet().contains("current_run_id"));
        assertTrue(generationWhere.getValue().getSqlSet().contains("owner_token"));

        ArgumentCaptor<ConnectorSemanticGenerationTable> tableUpdate =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGenerationTable>> tableWhere =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        order.verify(tableMapper).update(tableUpdate.capture(), tableWhere.capture());
        assertEquals("PENDING", tableUpdate.getValue().getStatus());
        assertNull(tableUpdate.getValue().getDispatchCount());
        tableWhere.getValue().getSqlSegment();
        assertTrue(tableWhere.getValue().getParamNameValuePairs().containsValue(101L));
        assertTrue(tableWhere.getValue().getParamNameValuePairs().containsValue("DISPATCHED"));

        ArgumentCaptor<ConnectorSemanticGeneration> progressUpdate =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        order.verify(generationMapper).update(progressUpdate.capture(), any());
        assertTrue(progressUpdate.getValue().getNote().contains("服务重启或卡住"));

        ArgumentCaptor<Connection> connectionUpdate = ArgumentCaptor.forClass(Connection.class);
        ArgumentCaptor<LambdaUpdateWrapper<Connection>> connectionWhere =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        order.verify(connectionMapper).update(connectionUpdate.capture(), connectionWhere.capture());
        assertEquals("FAILED", connectionUpdate.getValue().getSemanticStatus());
        assertNull(connectionUpdate.getValue().getSemanticSyncedAt());
        assertTrue(connectionUpdate.getValue().getSemanticNote().startsWith("语义层生成已中断"));
        connectionWhere.getValue().getSqlSegment();
        assertTrue(connectionWhere.getValue().getParamNameValuePairs().containsValue(41L));
        assertTrue(connectionWhere.getValue().getParamNameValuePairs().containsValue("RUNNING"));
        assertTrue(connectionWhere.getValue().getParamNameValuePairs().containsValue(row.getClaimAt()));
        verify(claim, never()).release(any(), any(), any(), any(Boolean.class));
        assertEquals(1, txManager.commits);
        assertEquals(0, txManager.rollbacks);
    }

    @Test
    @DisplayName("过期 FINALIZING 的 STAGED 批次保留暂存，上一版 READY 时连接恢复 READY")
    void stagedRestoresReady() {
        ConnectorSemanticGeneration row = stale(101L, 41L, "tenant-a", "FINALIZING", "STAGED", "READY");
        scanRows(List.of(row), List.of());

        reconciler.reconcile();

        ArgumentCaptor<Connection> update = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper).update(update.capture(), any());
        assertEquals("READY", update.getValue().getSemanticStatus());
        assertNull(update.getValue().getSemanticSyncedAt());
        assertTrue(update.getValue().getSemanticNote().startsWith("重新生成已中断"));
        assertTrue(update.getValue().getSemanticNote().contains("当前仍是上一版说明书"));
    }

    @Test
    @DisplayName("STAGED 的上一状态不是 READY 时连接转 FAILED")
    void stagedWithoutReadyFails() {
        ConnectorSemanticGeneration row = stale(101L, 41L, "tenant-a", "RUNNING", "STAGED", "FAILED");
        scanRows(List.of(row), List.of());

        reconciler.reconcile();

        ArgumentCaptor<Connection> update = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper).update(update.capture(), any());
        assertEquals("FAILED", update.getValue().getSemanticStatus());
    }

    @Test
    @DisplayName("批次 CAS 为 0 说明心跳恢复，事务回滚且不碰表和连接")
    void recoveredHeartbeatRollsBack() {
        scanRows(List.of(stale(101L, 41L, "tenant-a", "RUNNING", "DIRECT", null)), List.of());
        when(generationMapper.update(any(), any())).thenReturn(0);

        reconciler.reconcile();

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verify(tableMapper, never()).update(any(), any());
        verify(connectionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("连接 claimAt CAS 为 0 仍提交批次 INTERRUPTED 与 DISPATCHED 重置")
    void releaseMissStillCommits() {
        scanRows(List.of(stale(101L, 41L, "tenant-a", "RUNNING", "DIRECT", null)), List.of());
        when(connectionMapper.update(any(), any())).thenReturn(0);

        reconciler.reconcile();

        assertEquals(1, txManager.commits);
        assertEquals(0, txManager.rollbacks);
        verify(tableMapper).update(any(), any());
    }

    @Test
    @DisplayName("中断说明与批次计数以每表状态为准，不使用扫描时已滞后的 done/total")
    void staleInterruptionUsesAuthoritativeTableProgress() {
        ConnectorSemanticGeneration row = stale(101L, 41L, "tenant-a", "RUNNING", "DIRECT", null);
        row.setDoneTables(1);
        row.setTotalTables(99);
        scanRows(List.of(row), List.of());
        when(tableMapper.selectList(any())).thenReturn(List.of(
                tableStatus("DONE"), tableStatus("DONE"), tableStatus("DONE"),
                tableStatus("PENDING"), tableStatus("SKIPPED"), tableStatus("REMOVED")));

        reconciler.reconcile();

        ArgumentCaptor<ConnectorSemanticGeneration> generationUpdates =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper, times(2)).update(generationUpdates.capture(), any());
        ConnectorSemanticGeneration progressUpdate = generationUpdates.getAllValues().get(1);
        assertEquals(3, progressUpdate.getDoneTables());
        assertEquals(4, progressUpdate.getTotalTables());
        assertEquals(1, progressUpdate.getSkippedTables());
        assertEquals(1, progressUpdate.getRemovedTables());
        assertTrue(progressUpdate.getNote().contains("3/4"));

        ArgumentCaptor<Connection> connectionUpdate = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper).update(connectionUpdate.capture(), any());
        assertTrue(connectionUpdate.getValue().getSemanticNote().contains("3/4"));
    }

    @Test
    @DisplayName("跨租户候选只在系统身份扫描，逐批真实租户写入；单批异常不阻断下一批并恢复调用方上下文")
    void systemScanBatchIsolationAndContextRestore() {
        ConnectorSemanticGeneration a = stale(101L, 41L, "tenant-a", "RUNNING", "DIRECT", null);
        ConnectorSemanticGeneration b = stale(102L, 42L, "tenant-b", "RUNNING", "DIRECT", null);
        AtomicInteger scans = new AtomicInteger();
        when(generationMapper.selectList(any())).thenAnswer(invocation -> {
            assertTrue(TenantContext.isSystemMode());
            return scans.getAndIncrement() == 0 ? List.of(a, b) : List.of();
        });
        when(generationMapper.selectCount(any())).thenAnswer(invocation -> {
            assertTrue(TenantContext.isSystemMode());
            return 0L;
        });
        TenantContext.set("caller-tenant");
        doAnswer(invocation -> {
            Long connectorId = invocation.getArgument(0);
            assertEquals(connectorId.equals(41L) ? "tenant-a" : "tenant-b", TenantContext.get());
            if (connectorId.equals(41L)) throw new IllegalStateException("first failed");
            return null;
        }).when(claim).lockRow(any());

        reconciler.reconcile();

        verify(claim).lockRow(41L);
        verify(claim).lockRow(42L);
        verify(generationMapper, times(2)).update(any(), any());
        assertEquals(1, txManager.rollbacks);
        assertEquals(1, txManager.commits);
        assertEquals("caller-tenant", TenantContext.get());
    }

    @Test
    @DisplayName("扫描只取 heartbeat 早于 180 秒的 RUNNING/FINALIZING，空调用方上下文最终仍为空")
    @SuppressWarnings("unchecked")
    void staleScanCriteriaAndEmptyContext() {
        TenantContext.clear();

        reconciler.reconcile();

        ArgumentCaptor<LambdaQueryWrapper<ConnectorSemanticGeneration>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(generationMapper, times(2)).selectList(captor.capture());
        LambdaQueryWrapper<ConnectorSemanticGeneration> staleWhere = captor.getAllValues().get(0);
        staleWhere.getSqlSegment();
        assertTrue(staleWhere.getParamNameValuePairs().containsValue("RUNNING"));
        assertTrue(staleWhere.getParamNameValuePairs().containsValue("FINALIZING"));
        assertTrue(staleWhere.getParamNameValuePairs().containsValue(Date.from(NOW.minusSeconds(180))));
        verify(generationMapper, never()).update(any(), any());
        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("连接不存在的 QUEUED 以 id+QUEUED CAS 取消并记录 CONNECTION_DELETED")
    @SuppressWarnings("unchecked")
    void deletedConnectionCancelsQueued() {
        ConnectorSemanticGeneration queued = queued(103L, 43L, "tenant-a", null);
        scanRows(List.of(), List.of(queued));
        when(connectionMapper.selectById(43L)).thenReturn(null);

        reconciler.reconcile();

        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> where =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(generationMapper).update(update.capture(), where.capture());
        assertEquals("CANCELLED", update.getValue().getStatus());
        assertEquals("CONNECTION_DELETED", update.getValue().getReasonCode());
        where.getValue().getSqlSegment();
        assertTrue(where.getValue().getParamNameValuePairs().containsValue(103L));
        assertTrue(where.getValue().getParamNameValuePairs().containsValue("QUEUED"));
    }

    @Test
    @DisplayName("连接仍存在的 QUEUED 不取消；到期队列才通过最小 kick 接口唤醒 O5")
    @SuppressWarnings("unchecked")
    void queuedExistenceAndDueKick() {
        ConnectorSemanticGeneration queued = queued(103L, 43L, "tenant-a", Date.from(NOW.plusSeconds(30)));
        scanRows(List.of(), List.of(queued));
        Connection connection = new Connection();
        connection.setId(43L);
        when(connectionMapper.selectById(43L)).thenReturn(connection);
        when(generationMapper.selectCount(any())).thenReturn(1L);

        reconciler.reconcile();

        verify(generationMapper, never()).update(any(), any());
        verify(kick).kick();
        ArgumentCaptor<LambdaQueryWrapper<ConnectorSemanticGeneration>> due =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(generationMapper).selectCount(due.capture());
        due.getValue().getSqlSegment();
        assertTrue(due.getValue().getParamNameValuePairs().containsValue("QUEUED"));
        assertTrue(due.getValue().getParamNameValuePairs().containsValue(Date.from(NOW)));
    }

    @Test
    @DisplayName("notBefore 尚未到期、数据库不存在其它到期队列时不 kick")
    void futureNotBeforeDoesNotKick() {
        ConnectorSemanticGeneration queued = queued(103L, 43L, "tenant-a", Date.from(NOW.plusSeconds(30)));
        scanRows(List.of(), List.of(queued));
        Connection connection = new Connection();
        connection.setId(43L);
        when(connectionMapper.selectById(43L)).thenReturn(connection);
        when(generationMapper.selectCount(any())).thenReturn(0L);

        reconciler.reconcile();

        verify(kick, never()).kick();
    }

    private void scanRows(List<ConnectorSemanticGeneration> stale, List<ConnectorSemanticGeneration> queued) {
        when(generationMapper.selectList(any())).thenReturn(stale, queued);
    }

    private static ConnectorSemanticGeneration stale(long id, long connectorId, String tenant, String status,
                                                       String mode, String previousStatus) {
        ConnectorSemanticGeneration row = new ConnectorSemanticGeneration();
        row.setId(id);
        row.setTenantId(tenant);
        row.setConnectorId(connectorId);
        row.setStatus(status);
        row.setMode(mode);
        row.setPrevSemanticStatus(previousStatus);
        row.setHeartbeatAt(Date.from(NOW.minusSeconds(181)));
        row.setClaimAt(Date.from(NOW.minusSeconds(600)));
        row.setCurrentRunId("run-1");
        row.setOwnerToken("owner-1");
        row.setDoneTables(2);
        row.setTotalTables(10);
        return row;
    }

    private static ConnectorSemanticGeneration queued(long id, long connectorId, String tenant, Date notBefore) {
        ConnectorSemanticGeneration row = new ConnectorSemanticGeneration();
        row.setId(id);
        row.setTenantId(tenant);
        row.setConnectorId(connectorId);
        row.setStatus("QUEUED");
        row.setNotBefore(notBefore);
        return row;
    }

    private static ConnectorSemanticGenerationTable tableStatus(String status) {
        ConnectorSemanticGenerationTable row = new ConnectorSemanticGenerationTable();
        row.setStatus(status);
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
            // 真实经过 TransactionTemplate，只记录边界。
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

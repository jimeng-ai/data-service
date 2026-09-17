package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * 语义层生成对账器：回收失去心跳的运行批次、取消连接已删除的排队批次，并唤醒到期队列。
 *
 * <p>跨租户只用于找候选；每一行的读写都切回它自己的真实租户。失心跳批次在一个事务里严格按
 * 「锁连接 → CAS 批次 → 重置表 → CAS 释放连接」执行，和生成编排/收尾保持同一锁序。
 */
@Slf4j
@Component
public class SemanticGenerationReconciler implements CommandLineRunner {

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FINALIZING = "FINALIZING";
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String TABLE_DISPATCHED = "DISPATCHED";
    private static final String TABLE_PENDING = "PENDING";
    private static final String MODE_STAGED = "STAGED";
    private static final String SEMANTIC_READY = "READY";
    private static final String SEMANTIC_FAILED = "FAILED";
    private static final String HEARTBEAT_REASON = "服务重启或卡住";
    private static final Duration STALE_AFTER = Duration.ofSeconds(180);

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectionMapper connectionMapper;
    private final SemanticConnectionClaim connectionClaim;
    private final SemanticGenerationNotes notes;
    private final SemanticGenerationKick kick;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SemanticGenerationReconciler(ConnectorSemanticGenerationMapper generationMapper,
                                        ConnectorSemanticGenerationTableMapper tableMapper,
                                        ConnectionMapper connectionMapper,
                                        SemanticConnectionClaim connectionClaim,
                                        SemanticGenerationNotes notes,
                                        SemanticGenerationKick kick,
                                        PlatformTransactionManager transactionManager) {
        this(generationMapper, tableMapper, connectionMapper, connectionClaim, notes, kick, transactionManager,
                Clock.systemDefaultZone());
    }

    SemanticGenerationReconciler(ConnectorSemanticGenerationMapper generationMapper,
                                 ConnectorSemanticGenerationTableMapper tableMapper,
                                 ConnectionMapper connectionMapper,
                                 SemanticConnectionClaim connectionClaim,
                                 SemanticGenerationNotes notes,
                                 SemanticGenerationKick kick,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.generationMapper = generationMapper;
        this.tableMapper = tableMapper;
        this.connectionMapper = connectionMapper;
        this.connectionClaim = connectionClaim;
        this.notes = notes;
        this.kick = kick;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        reconcile();
    }

    /** 启动时由 {@link #run} 调一次；运行中固定延迟 60 秒再扫。 */
    @Scheduled(fixedDelay = 60_000L)
    public void reconcile() {
        Date now = Date.from(clock.instant());
        for (ConnectorSemanticGeneration row : staleCandidates(new Date(now.getTime() - STALE_AFTER.toMillis()))) {
            try {
                inTenant(row.getTenantId(), () -> interruptStale(row));
            } catch (RuntimeException e) {
                log.warn("语义层失心跳批次对账失败，留待下轮重试 generationId={} connectorId={}: {}",
                        row.getId(), row.getConnectorId(), safeMessage(e));
            }
        }

        for (ConnectorSemanticGeneration row : queuedCandidates()) {
            try {
                inTenant(row.getTenantId(), () -> cancelIfConnectionDeleted(row, now));
            } catch (RuntimeException e) {
                log.warn("语义层排队批次连接检查失败，留待下轮重试 generationId={} connectorId={}: {}",
                        row.getId(), row.getConnectorId(), safeMessage(e));
            }
        }

        try {
            Long due = TenantContext.runAsSystem(() -> generationMapper.selectCount(
                    new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getStatus, STATUS_QUEUED)
                            .and(w -> w.isNull(ConnectorSemanticGeneration::getNotBefore)
                                    .or().le(ConnectorSemanticGeneration::getNotBefore, now))));
            if (due != null && due > 0) {
                kick.kick();
            }
        } catch (RuntimeException e) {
            log.warn("语义层到期队列唤醒失败，留待下轮重试: {}", safeMessage(e));
        }
    }

    private List<ConnectorSemanticGeneration> staleCandidates(Date cutoff) {
        try {
            List<ConnectorSemanticGeneration> rows = TenantContext.runAsSystem(() -> generationMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                            .in(ConnectorSemanticGeneration::getStatus, List.of(STATUS_RUNNING, STATUS_FINALIZING))
                            .lt(ConnectorSemanticGeneration::getHeartbeatAt, cutoff)));
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("扫描语义层失心跳批次失败，留待下轮重试: {}", safeMessage(e));
            return List.of();
        }
    }

    private List<ConnectorSemanticGeneration> queuedCandidates() {
        try {
            List<ConnectorSemanticGeneration> rows = TenantContext.runAsSystem(() -> generationMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getStatus, STATUS_QUEUED)));
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("扫描语义层排队批次失败，留待下轮重试: {}", safeMessage(e));
            return List.of();
        }
    }

    private void interruptStale(ConnectorSemanticGeneration observed) {
        transactionTemplate.executeWithoutResult(tx -> {
            connectionClaim.lockRow(observed.getConnectorId());

            ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
            update.setStatus(STATUS_INTERRUPTED);
            update.setReasonCode(GenerationReasonCode.HEARTBEAT_LOST.name());
            int generationRows = generationMapper.update(update,
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                            .set(ConnectorSemanticGeneration::getOwnerToken, null)
                            .eq(ConnectorSemanticGeneration::getId, observed.getId())
                            .eq(ConnectorSemanticGeneration::getStatus, observed.getStatus())
                            .eq(ConnectorSemanticGeneration::getHeartbeatAt, observed.getHeartbeatAt()));
            if (generationRows == 0) {
                tx.setRollbackOnly();
                return;
            }

            ConnectorSemanticGenerationTable tableUpdate = new ConnectorSemanticGenerationTable();
            tableUpdate.setStatus(TABLE_PENDING);
            tableMapper.update(tableUpdate, new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                    .eq(ConnectorSemanticGenerationTable::getGenerationId, observed.getId())
                    .eq(ConnectorSemanticGenerationTable::getStatus, TABLE_DISPATCHED));

            // callback 可以在批次行计数回写前已把若干表置为 DONE。上面的批次 CAS 已与在途提交串行，
            // 此刻再从每表状态聚合，才是这次中断说明和续跑计数的权威快照。
            ProgressSnapshot progress = readProgress(observed.getId());
            observed.setDoneTables(progress.done());
            observed.setTotalTables(progress.total());
            observed.setGaveUpTables(progress.gaveUp());
            observed.setSkippedTables(progress.skipped());
            observed.setRemovedTables(progress.removed());
            String note = notes.interrupted(observed, HEARTBEAT_REASON);

            ConnectorSemanticGeneration progressUpdate = new ConnectorSemanticGeneration();
            progressUpdate.setDoneTables(progress.done());
            progressUpdate.setTotalTables(progress.total());
            progressUpdate.setGaveUpTables(progress.gaveUp());
            progressUpdate.setSkippedTables(progress.skipped());
            progressUpdate.setRemovedTables(progress.removed());
            progressUpdate.setNote(note);
            int progressRows = generationMapper.update(progressUpdate,
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getId, observed.getId())
                            .eq(ConnectorSemanticGeneration::getStatus, STATUS_INTERRUPTED)
                            .eq(ConnectorSemanticGeneration::getReasonCode,
                                    GenerationReasonCode.HEARTBEAT_LOST.name()));
            if (progressRows == 0) {
                tx.setRollbackOnly();
                return;
            }

            Connection connectionUpdate = new Connection();
            connectionUpdate.setSemanticStatus(releasedSemanticStatus(observed));
            connectionUpdate.setSemanticNote(note);
            int connectionRows = connectionMapper.update(connectionUpdate, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, observed.getConnectorId())
                    .eq(Connection::getSemanticStatus, STATUS_RUNNING)
                    .eq(Connection::getSemanticClaimAt, observed.getClaimAt()));
            if (connectionRows == 0) {
                // 别人已经释放或接管连接认领；批次自己的 CAS 已成功，按设计仍提交 INTERRUPTED。
                log.info("语义层失心跳批次已中断，连接认领已由别处处理 generationId={} connectorId={}",
                        observed.getId(), observed.getConnectorId());
            }
        });
    }

    private ProgressSnapshot readProgress(Long generationId) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .select(ConnectorSemanticGenerationTable::getStatus)
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generationId));
        int total = 0;
        int done = 0;
        int gaveUp = 0;
        int skipped = 0;
        int removed = 0;
        for (ConnectorSemanticGenerationTable row : rows == null
                ? List.<ConnectorSemanticGenerationTable>of() : rows) {
            String status = row.getStatus();
            if ("SKIPPED".equals(status)) {
                skipped++;
            } else if ("REMOVED".equals(status)) {
                removed++;
            } else {
                total++;
                if ("DONE".equals(status)) {
                    done++;
                } else if ("GAVE_UP".equals(status)) {
                    gaveUp++;
                }
            }
        }
        return new ProgressSnapshot(total, done, gaveUp, skipped, removed);
    }

    private void cancelIfConnectionDeleted(ConnectorSemanticGeneration observed, Date now) {
        if (connectionMapper.selectById(observed.getConnectorId()) != null) {
            return;
        }
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(STATUS_CANCELLED);
        update.setReasonCode(GenerationReasonCode.CONNECTION_DELETED.name());
        update.setNote("连接已删除，语义层生成已取消");
        update.setFinishedAt(now);
        generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                .set(ConnectorSemanticGeneration::getOwnerToken, null)
                .set(ConnectorSemanticGeneration::getNotBefore, null)
                .eq(ConnectorSemanticGeneration::getId, observed.getId())
                .eq(ConnectorSemanticGeneration::getStatus, STATUS_QUEUED));
    }

    private static String releasedSemanticStatus(ConnectorSemanticGeneration generation) {
        if (MODE_STAGED.equals(generation.getMode()) && SEMANTIC_READY.equals(generation.getPrevSemanticStatus())) {
            return SEMANTIC_READY;
        }
        return SEMANTIC_FAILED;
    }

    private static void inTenant(String tenantId, Runnable work) {
        String previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            work.run();
        } finally {
            if (previous == null || previous.isEmpty()) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record ProgressSnapshot(int total, int done, int gaveUp, int skipped, int removed) {
    }
}

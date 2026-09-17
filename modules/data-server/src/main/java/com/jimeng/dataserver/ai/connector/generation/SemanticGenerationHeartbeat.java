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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 当前语义层生成批次的独立心跳 ticker。
 *
 * <p>全平台租约保证单进程最多持有一个运行批次，所以本组件也只维护一个 active context。
 * 编排器可在等待循环与步骤边界读取 {@link #checkpoint()}，不需要碰 ticker 的内部线程。
 */
@Slf4j
@Component
public class SemanticGenerationHeartbeat implements AutoCloseable {

    static final Duration CLAIM_RENEW_AFTER = Duration.ofMinutes(20);
    static final Duration PROGRESS_REFRESH_AFTER = Duration.ofSeconds(20);
    private static final long TICK_SECONDS = 30L;
    private static final String RUNNING = "RUNNING";
    private static final String FINALIZING = "FINALIZING";

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectionMapper connectionMapper;
    private final SemanticGenerationLease lease;
    private final SemanticConnectionClaim claim;
    private final SemanticGenerationNotes notes;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean lost = new AtomicBoolean(false);
    private final AtomicBoolean connectionDeleted = new AtomicBoolean(false);
    private final AtomicBoolean connectionDisabled = new AtomicBoolean(false);
    private volatile Active active;
    private volatile ScheduledFuture<?> task;
    private ProgressSnapshot lastProgress;
    private long lastProgressAt = Long.MIN_VALUE;

    @Autowired
    public SemanticGenerationHeartbeat(ConnectorSemanticGenerationMapper generationMapper,
                                       ConnectorSemanticGenerationTableMapper tableMapper,
                                       ConnectionMapper connectionMapper,
                                       SemanticGenerationLease lease,
                                       SemanticConnectionClaim claim,
                                       SemanticGenerationNotes notes) {
        this(generationMapper, tableMapper, connectionMapper, lease, claim, notes, Clock.systemDefaultZone());
    }

    SemanticGenerationHeartbeat(ConnectorSemanticGenerationMapper generationMapper,
                                ConnectorSemanticGenerationTableMapper tableMapper,
                                ConnectionMapper connectionMapper,
                                SemanticGenerationLease lease,
                                SemanticConnectionClaim claim,
                                SemanticGenerationNotes notes,
                                Clock clock) {
        this(generationMapper, tableMapper, connectionMapper, lease, claim, notes, clock,
                Executors.newSingleThreadScheduledExecutor(threadFactory()));
    }

    SemanticGenerationHeartbeat(ConnectorSemanticGenerationMapper generationMapper,
                                ConnectorSemanticGenerationTableMapper tableMapper,
                                ConnectionMapper connectionMapper,
                                SemanticGenerationLease lease,
                                SemanticConnectionClaim claim,
                                SemanticGenerationNotes notes,
                                Clock clock,
                                ScheduledExecutorService scheduler) {
        this.generationMapper = generationMapper;
        this.tableMapper = tableMapper;
        this.connectionMapper = connectionMapper;
        this.lease = lease;
        this.claim = claim;
        this.notes = notes;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /**
     * 启动 ticker；批次 {@code owner_token} 就是当前 drain 持有的 Redis 租约 token。
     */
    public synchronized void start(ConnectorSemanticGeneration generation) {
        start(generation, generation.getOwnerToken());
    }

    /** 启动 ticker；同一批次重复 start 不会创建第二个定时任务。 */
    public synchronized void start(ConnectorSemanticGeneration generation, String leaseToken) {
        if (running()) {
            return;
        }
        active = new Active(generation.getId(), generation.getTenantId(), generation.getConnectorId(),
                generation.getOwnerToken(), leaseToken);
        lost.set(false);
        connectionDeleted.set(false);
        connectionDisabled.set(false);
        lastProgress = null;
        lastProgressAt = Long.MIN_VALUE;
        task = scheduler.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** 停止 ticker；可重复调用。checkpoint 保留，供编排器在退出路径读取。 */
    public synchronized void stop() {
        ScheduledFuture<?> current = task;
        task = null;
        active = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    /**
     * 执行一拍。它是正式 API，既供 scheduler 调用，也供编排器在关键边界主动 checkpoint。
     */
    public synchronized void tick() {
        Active current = active;
        if (current == null) {
            return;
        }
        String previousTenant = TenantContext.get();
        TenantContext.set(current.tenantId());
        try {
            if (lost.get()) {
                return;
            }
            Date now = Date.from(clock.instant().truncatedTo(ChronoUnit.SECONDS));
            if (!writeBatchHeartbeat(current, now)) {
                lost.set(true);
                return;
            }
            if (!lease.renew(current.leaseToken())) {
                lost.set(true);
                return;
            }
            if (!claim.renewIfDue(current.generationId(), current.ownerToken(), CLAIM_RENEW_AFTER)) {
                lost.set(true);
                return;
            }

            Connection connection = connectionMapper.selectById(current.connectorId());
            if (connection == null) {
                connectionDeleted.set(true);
                return;
            }
            if (!"ACTIVE".equals(connection.getStatus())) {
                connectionDisabled.set(true);
                return;
            }

            ConnectorSemanticGeneration live = generationMapper.selectById(current.generationId());
            if (live == null) {
                lost.set(true);
                return;
            }
            writeProgressIfDue(current, live);
        } catch (RuntimeException e) {
            lost.set(true);
            log.warn("语义层生成心跳失败 generationId={}: {}", current.generationId(),
                    e.getClass().getSimpleName());
        } finally {
            if (previousTenant == null || previousTenant.isEmpty()) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private boolean writeBatchHeartbeat(Active current, Date now) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setHeartbeatAt(now);
        return generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getId, current.generationId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, current.ownerToken())
                .in(ConnectorSemanticGeneration::getStatus, RUNNING, FINALIZING)) > 0;
    }

    private void writeProgressIfDue(Active current, ConnectorSemanticGeneration live) {
        ProgressSnapshot progress = readProgress(current.generationId());
        long now = clock.millis();
        boolean first = lastProgress == null;
        boolean changed = !progress.equals(lastProgress);
        boolean aged = lastProgressAt == Long.MIN_VALUE || now - lastProgressAt >= PROGRESS_REFRESH_AFTER.toMillis();
        if (!first && (!changed || !aged)) {
            return;
        }
        writeProgress(current, live, progress, now);
    }

    /** 片开始和结束时立即写一次进度，不受 20 秒节流限制。 */
    public synchronized void forceProgressNote() {
        Active current = active;
        if (current == null || lost.get()) {
            return;
        }
        try {
            ConnectorSemanticGeneration live = generationMapper.selectById(current.generationId());
            if (live == null) {
                lost.set(true);
                return;
            }
            writeProgress(current, live, readProgress(current.generationId()), clock.millis());
        } catch (RuntimeException e) {
            lost.set(true);
            log.warn("强制写语义层生成进度失败 generationId={}: {}", current.generationId(),
                    e.getClass().getSimpleName());
        }
    }

    private void writeProgress(Active current, ConnectorSemanticGeneration live,
                               ProgressSnapshot progress, long writtenAt) {
        String note = notes.progress(live,
                new SemanticGenerationNotes.ProgressCounts(progress.doneTables(), progress.gaveUpTables()));
        if (!claim.writeNote(current.connectorId(), note)) {
            lost.set(true);
            return;
        }
        lastProgress = progress;
        lastProgressAt = writtenAt;
    }

    /** 批次行计数可能落后于片内 callback；进度以每表状态为唯一真相源。 */
    private ProgressSnapshot readProgress(Long generationId) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .select(ConnectorSemanticGenerationTable::getStatus)
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generationId));
        int done = 0;
        int gaveUp = 0;
        if (rows != null) {
            for (ConnectorSemanticGenerationTable row : rows) {
                if ("DONE".equals(row.getStatus())) {
                    done++;
                } else if ("GAVE_UP".equals(row.getStatus())) {
                    gaveUp++;
                }
            }
        }
        return new ProgressSnapshot(done, gaveUp);
    }

    public Checkpoint checkpoint() {
        return new Checkpoint(lost.get(), connectionDeleted.get(), connectionDisabled.get());
    }

    public boolean lost() {
        return lost.get();
    }

    public boolean connectionDeleted() {
        return connectionDeleted.get();
    }

    public boolean connectionDisabled() {
        return connectionDisabled.get();
    }

    public boolean running() {
        ScheduledFuture<?> current = task;
        return current != null && !current.isCancelled() && !current.isDone();
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        stop();
        scheduler.shutdownNow();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "semantic-gen-hb-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record Active(Long generationId, String tenantId, Long connectorId,
                          String ownerToken, String leaseToken) {
    }

    private record ProgressSnapshot(int doneTables, int gaveUpTables) {
    }

    public record Checkpoint(boolean lost, boolean connectionDeleted, boolean connectionDisabled) {
    }
}

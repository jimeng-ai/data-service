package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeAction;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeContext;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeDecision;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeHandlerRegistry;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeKind;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全平台串行的语义层 agent 编排器。数据库是队列与进度真相源，内存只保存当前 drain 的短暂 streak。
 */
@Slf4j
@Component
public class SemanticGenerationOrchestrator implements SemanticGenerationKick {

    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final String FINALIZING = "FINALIZING";
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String FAILED = "FAILED";
    private static final String FELL_BACK = "FELL_BACK";
    private static final String CANCELLED = "CANCELLED";
    private static final String DIRECT = "DIRECT";
    private static final String STAGED = "STAGED";
    private static final String READY = "READY";
    private static final String PENDING = "PENDING";
    private static final String DISPATCHED = "DISPATCHED";
    private static final String DONE = "DONE";
    private static final String SKIPPED = "SKIPPED";
    private static final String GAVE_UP = "GAVE_UP";
    private static final String REMOVED = "REMOVED";
    private static final long CLAIM_REQUEUE_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final long CLAIM_GIVE_UP_MILLIS = Duration.ofHours(1).toMillis();
    private static final long WAIT_CHECKPOINT_MILLIS = 30_000L;
    private static final long DEFAULT_MAX_TOKENS_PER_TABLE = 300_000L;

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaService schemaService;
    private final SemanticGenerationLease lease;
    private final SemanticConnectionClaim connectionClaim;
    private final SemanticGenerationHeartbeat heartbeat;
    private final SemanticSlicePlanner planner;
    private final SemanticSliceDispatcher dispatcher;
    private final SliceOutcomeHandlerRegistry outcomeHandlers;
    private final SemanticGenerationFinalizer finalizer;
    private final SingleCallSemanticGenerator singleCall;
    private final ConnectorProperties properties;
    private final SemanticGenerationNotes notes;
    private final TransactionTemplate transactionTemplate;
    /** 字段名用于在多个 ThreadPoolTaskExecutor bean 之间消歧。 */
    private final ThreadPoolTaskExecutor semanticGenerationExecutor;
    private final OrchestratorRuntime runtime;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    @Autowired
    public SemanticGenerationOrchestrator(ConnectorSemanticGenerationMapper generationMapper,
                                          ConnectorSemanticGenerationTableMapper tableMapper,
                                          ConnectorSemanticStagedMapper stagedMapper,
                                          ConnectionMapper connectionMapper,
                                          ConnectorSchemaService schemaService,
                                          SemanticGenerationLease lease,
                                          SemanticConnectionClaim connectionClaim,
                                          SemanticGenerationHeartbeat heartbeat,
                                          SemanticSlicePlanner planner,
                                          SemanticSliceDispatcher dispatcher,
                                          SliceOutcomeHandlerRegistry outcomeHandlers,
                                          SemanticGenerationFinalizer finalizer,
                                          SingleCallSemanticGenerator singleCall,
                                          ConnectorProperties properties,
                                          SemanticGenerationNotes notes,
                                          PlatformTransactionManager transactionManager,
                                          ThreadPoolTaskExecutor semanticGenerationExecutor) {
        this(generationMapper, tableMapper, stagedMapper, connectionMapper, schemaService, lease,
                connectionClaim, heartbeat, planner, dispatcher, outcomeHandlers, finalizer, singleCall,
                properties, notes, transactionManager, semanticGenerationExecutor,
                new SystemOrchestratorRuntime());
    }

    SemanticGenerationOrchestrator(ConnectorSemanticGenerationMapper generationMapper,
                                   ConnectorSemanticGenerationTableMapper tableMapper,
                                   ConnectorSemanticStagedMapper stagedMapper,
                                   ConnectionMapper connectionMapper,
                                   ConnectorSchemaService schemaService,
                                   SemanticGenerationLease lease,
                                   SemanticConnectionClaim connectionClaim,
                                   SemanticGenerationHeartbeat heartbeat,
                                   SemanticSlicePlanner planner,
                                   SemanticSliceDispatcher dispatcher,
                                   SliceOutcomeHandlerRegistry outcomeHandlers,
                                   SemanticGenerationFinalizer finalizer,
                                   SingleCallSemanticGenerator singleCall,
                                   ConnectorProperties properties,
                                   SemanticGenerationNotes notes,
                                   PlatformTransactionManager transactionManager,
                                   ThreadPoolTaskExecutor semanticGenerationExecutor,
                                   OrchestratorRuntime runtime) {
        this.generationMapper = generationMapper;
        this.tableMapper = tableMapper;
        this.stagedMapper = stagedMapper;
        this.connectionMapper = connectionMapper;
        this.schemaService = schemaService;
        this.lease = lease;
        this.connectionClaim = connectionClaim;
        this.heartbeat = heartbeat;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.outcomeHandlers = outcomeHandlers;
        this.finalizer = finalizer;
        this.singleCall = singleCall;
        this.properties = properties;
        this.notes = notes;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.semanticGenerationExecutor = semanticGenerationExecutor;
        this.runtime = runtime;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        kick();
    }

    @Override
    public void kick() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            semanticGenerationExecutor.execute(MdcAsyncSupport.wrap("semantic-generation-drain", this::drainLoop));
        } catch (TaskRejectedException rejected) {
            draining.set(false);
            log.warn("语义层生成排空信号被执行器拒绝，批次保留在数据库队列中");
        }
    }

    /** 包级可见，单测同步执行，不绕过生产 kick 的去重与拒绝处理。 */
    void drainLoop() {
        String previousTenant = TenantContext.get();
        String token = runtime.newToken();
        boolean acquired = false;
        List<GenerationRequest> fallbacks = new ArrayList<>(1);
        try {
            acquired = lease.acquire(token);
            if (!acquired) {
                return;
            }
            while (!Thread.currentThread().isInterrupted()) {
                ConnectorSemanticGeneration generation = oldestRunnableQueued();
                if (generation == null) {
                    break;
                }
                if (!claimQueued(generation, token)) {
                    continue;
                }
                try {
                    heartbeat.resetCheckpointForNextBatch();
                    setTenant(generation.getTenantId());
                    GenerationRequest fallback = runGeneration(generation);
                    if (fallback != null) {
                        fallbacks.add(fallback);
                        break;
                    }
                    if (generationLeaseLost()) {
                        break;
                    }
                } catch (Throwable failure) {
                    if (generationLeaseLost()) {
                        break;
                    }
                    log.error("语义层批次编排异常 generationId={} error={}", generation.getId(),
                            failure.getClass().getSimpleName(), failure);
                    interruptOwned(generation, GenerationReasonCode.INTERNAL_ERROR, "内部错误，已安全中断");
                } finally {
                    restoreTenant(previousTenant);
                }
            }
        } finally {
            if (acquired) {
                try {
                    lease.release(token);
                } catch (Throwable failure) {
                    log.warn("释放语义层全局租约失败，将继续本地清理与唤醒: {}",
                            failure.getClass().getSimpleName());
                }
            }
            draining.set(false);
            try {
                for (GenerationRequest fallback : fallbacks) {
                    String beforeFallback = TenantContext.get();
                    try {
                        setTenant(fallback.tenantId());
                        singleCall.submit(fallback);
                    } catch (Throwable failure) {
                        log.warn("语义层单次回落提交失败 connectorId={} error={}", fallback.connectorId(),
                                failure.getClass().getSimpleName());
                    } finally {
                        restoreTenant(beforeFallback);
                    }
                }
            } finally {
                restoreTenant(previousTenant);
                if (acquired && hasRunnableQueued()) {
                    kick();
                }
            }
        }
    }

    private boolean generationLeaseLost() {
        SemanticGenerationHeartbeat.Checkpoint checkpoint = heartbeat.checkpoint();
        return checkpoint == null ? heartbeat.lost() : checkpoint.lost();
    }

    private ConnectorSemanticGeneration oldestRunnableQueued() {
        Date now = new Date(runtime.nowMillis());
        List<ConnectorSemanticGeneration> rows = TenantContext.runAsSystem(() -> generationMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                        .eq(ConnectorSemanticGeneration::getStatus, QUEUED)
                        .and(w -> w.isNull(ConnectorSemanticGeneration::getNotBefore)
                                .or().le(ConnectorSemanticGeneration::getNotBefore, now))
                        .orderByAsc(ConnectorSemanticGeneration::getCreateTime)
                        .orderByAsc(ConnectorSemanticGeneration::getId)
                        .last("LIMIT 1")));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private boolean hasRunnableQueued() {
        try {
            Date now = new Date(runtime.nowMillis());
            Long count = TenantContext.runAsSystem(() -> generationMapper.selectCount(
                    new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getStatus, QUEUED)
                            .and(w -> w.isNull(ConnectorSemanticGeneration::getNotBefore)
                                    .or().le(ConnectorSemanticGeneration::getNotBefore, now))));
            return count != null && count > 0L;
        } catch (RuntimeException failure) {
            log.warn("检查语义层待排空队列失败，留待定时对账唤醒: {}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean claimQueued(ConnectorSemanticGeneration generation, String token) {
        Date now = new Date(runtime.nowMillis());
        int rows = TenantContext.runAsSystem(() -> generationMapper.update(null,
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .set(ConnectorSemanticGeneration::getStatus, RUNNING)
                        .set(ConnectorSemanticGeneration::getOwnerToken, token)
                        .set(ConnectorSemanticGeneration::getHeartbeatAt, now)
                        .set(ConnectorSemanticGeneration::getReasonCode, null)
                        .set(ConnectorSemanticGeneration::getNotBefore, null)
                        .eq(ConnectorSemanticGeneration::getId, generation.getId())
                        .eq(ConnectorSemanticGeneration::getStatus, QUEUED)));
        if (rows == 0) {
            return false;
        }
        generation.setStatus(RUNNING);
        generation.setOwnerToken(token);
        generation.setHeartbeatAt(now);
        generation.setReasonCode(null);
        generation.setNotBefore(null);
        return true;
    }

    GenerationRequest runGeneration(ConnectorSemanticGeneration generation) {
        Connection connection = connectionMapper.selectById(generation.getConnectorId());
        if (connection == null) {
            cancelDeleted(generation);
            return null;
        }
        if (!"ACTIVE".equals(connection.getStatus())) {
            interruptBeforeClaim(generation, GenerationReasonCode.CONNECTION_DISABLED, "连接已停用");
            return null;
        }

        SemanticConnectionClaim.Credential credential = connectionClaim.claim(generation.getConnectorId(),
                generation.getClaimAt(), "正在生成语义层（agent）……");
        if (credential == null) {
            handleClaimBusy(generation);
            return null;
        }
        if (!persistClaim(generation, credential)) {
            connectionClaim.release(generation.getConnectorId(), restoredStatus(generation),
                    "语义层生成认领已失效", false);
            return null;
        }

        heartbeat.start(generation);
        try {
            List<ConnectorSchema> snapshot = safeSnapshot(generation.getConnectorId());
            if (snapshot.isEmpty() && !bootstrapSnapshot(generation)) {
                return null;
            }

            int adaptiveCap = clampSliceCap(agent().getSliceMaxTables());
            int sliceFailStreak = 0;
            int noCallbackStreak = 0;
            while (true) {
                Boundary boundary = boundary();
                if (boundary == Boundary.LOST) {
                    return null;
                }
                if (boundary == Boundary.DELETED) {
                    cancelDeleted(generation);
                    return null;
                }
                if (boundary == Boundary.DISABLED) {
                    interruptOwned(generation, GenerationReasonCode.CONNECTION_DISABLED, "连接已停用");
                    return null;
                }

                ConnectorProperties.SemanticAgent liveConfig = agent();
                if (!liveConfig.isEnabled()) {
                    interruptOwned(generation, GenerationReasonCode.AGENT_DISABLED,
                            "agent 生成已关闭，再点重新生成将走单次推导");
                    return null;
                }
                if (!reconcile(generation)) {
                    return null;
                }
                ConnectorSemanticGeneration live = reloadOwned(generation);
                if (live == null) {
                    return null;
                }
                copyRuntimeState(generation, live);
                if (tokenCapReached(generation, normalizedTokenLimit(liveConfig.getMaxTokensPerTable()))) {
                    interruptOwned(generation, GenerationReasonCode.TOKEN_CAP,
                            "超出本轮 token 上限；确认后点重新生成继续（每次续跑重新计额）");
                    return null;
                }

                int effectiveCap = Math.min(adaptiveCap, clampSliceCap(liveConfig.getSliceMaxTables()));
                SemanticSlice slice = planner.plan(generation, effectiveCap, liveConfig.getSliceMaxChars());
                if (slice.isEmpty()) {
                    SemanticGenerationFinalizer.FinishResult finish = finalizer.finish(generation,
                            liveConfig.getMaxGaveUpRatio());
                    if (finish == SemanticGenerationFinalizer.FinishResult.CONTINUE) {
                        generation.setStatus(RUNNING);
                        continue;
                    }
                    return null;
                }

                int sliceNo = number(generation.getCurrentSliceNo()) + 1;
                if (!markSliceDispatched(generation, sliceNo, slice)) {
                    return null;
                }
                heartbeat.forceProgressNote();
                if (heartbeat.lost()) {
                    return null;
                }

                SemanticDispatchLimits dispatchLimits = SemanticDispatchLimits.from(liveConfig, slice.size());
                SliceRunResult result = dispatcher.runSlice(generation, sliceNo, slice, dispatchLimits);
                Boundary afterRun = boundary();
                if (afterRun == Boundary.LOST) {
                    return null;
                }
                if (afterRun == Boundary.DELETED) {
                    cancelDeleted(generation);
                    return null;
                }
                if (afterRun == Boundary.DISABLED) {
                    interruptOwned(generation, GenerationReasonCode.CONNECTION_DISABLED, "连接已停用");
                    return null;
                }
                int progress = progressBeforeSettle(generation, sliceNo);
                SliceOutcomeKind kind = SliceOutcomeClassifier.classify(result, progress);
                if (kind == SliceOutcomeKind.ABORTED) {
                    return null;
                }
                settleSlice(generation, slice, result,
                        clamp(liveConfig.getTableMaxDispatches(), 1, 10));
                adaptiveCap = nextSliceCap(effectiveCap, result.timedOut());

                Counts counts = counts(generation.getId());
                applyCounts(generation, counts);
                if (!persistCounts(generation, counts)) {
                    return null;
                }
                SliceOutcomeDecision decision = outcomeHandlers.decide(new SliceOutcomeContext(kind, result,
                        progress, counts.done(), sliceFailStreak, noCallbackStreak,
                        clamp(liveConfig.getSliceMaxRetries(), 1, 10),
                        clamp(liveConfig.getRetryBackoffSec(), 1, 300)));
                sliceFailStreak = decision.sliceFailStreak();
                noCallbackStreak = decision.noCallbackStreak();

                switch (decision.action()) {
                    case ABORT -> {
                        return null;
                    }
                    case CONTINUE -> heartbeat.forceProgressNote();
                    case RETRY -> {
                        WaitOutcome waited = sleepWithHeartbeat(decision.backoffSeconds());
                        if (waited == WaitOutcome.LOST) return null;
                        if (waited == WaitOutcome.DELETED) {
                            cancelDeleted(generation);
                            return null;
                        }
                        if (waited == WaitOutcome.DISABLED) {
                            interruptOwned(generation, GenerationReasonCode.CONNECTION_DISABLED, "连接已停用");
                            return null;
                        }
                        if (waited == WaitOutcome.SHUTDOWN) {
                            interruptOwned(generation, GenerationReasonCode.SHUTDOWN,
                                    "服务关停，语义层生成已中断");
                            return null;
                        }
                        heartbeat.forceProgressNote();
                    }
                    case INTERRUPT -> {
                        interruptOwned(generation, decision.reasonCode(), decision.note());
                        return null;
                    }
                    case FAIL -> {
                        failOwned(generation, decision.reasonCode(), decision.note(), restoredStatus(generation));
                        return null;
                    }
                    case FALLBACK -> {
                        return fallBack(generation, decision);
                    }
                }
            }
        } finally {
            heartbeat.stop();
        }
    }

    private ConnectorProperties.SemanticAgent agent() {
        return properties.getSemantic().getAgent();
    }

    private boolean persistClaim(ConnectorSemanticGeneration generation,
                                 SemanticConnectionClaim.Credential credential) {
        Date now = new Date(runtime.nowMillis());
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setClaimAt(credential.claimAt());
        if (generation.getPrevSemanticStatus() == null) {
            update.setPrevSemanticStatus(credential.previousSemanticStatus());
        }
        if (generation.getStartedAt() == null) {
            update.setStartedAt(now);
        }
        int rows = generationMapper.update(update,
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .eq(ConnectorSemanticGeneration::getId, generation.getId())
                        .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                        .eq(ConnectorSemanticGeneration::getStatus, RUNNING));
        if (rows == 0) {
            return false;
        }
        generation.setClaimAt(credential.claimAt());
        if (generation.getPrevSemanticStatus() == null) {
            generation.setPrevSemanticStatus(credential.previousSemanticStatus());
        }
        if (generation.getStartedAt() == null) {
            generation.setStartedAt(now);
        }
        return true;
    }

    private List<ConnectorSchema> safeSnapshot(Long connectorId) {
        List<ConnectorSchema> rows = schemaService.snapshotRowsByImportance(connectorId);
        return rows == null ? List.of() : rows;
    }

    private boolean bootstrapSnapshot(ConnectorSemanticGeneration generation) {
        try {
            schemaService.refresh(generation.getConnectorId());
            if (!safeSnapshot(generation.getConnectorId()).isEmpty()) {
                return true;
            }
            failOwned(generation, GenerationReasonCode.SNAPSHOT_REFRESH_FAILED,
                    "结构补拉后快照仍为空", restoredStatus(generation));
            return false;
        } catch (ServiceException failure) {
            if (ExceptionCode.OPERATION_UNSUPPORTED.getResultCode().equals(failure.getRespCode())) {
                failOwned(generation, GenerationReasonCode.SNAPSHOT_NOT_APPLICABLE,
                        "该连接器不提供结构自描述，语义层不适用",
                        ConnectorSemanticDeriveService.SEM_NOT_APPLICABLE);
            } else {
                failOwned(generation, GenerationReasonCode.SNAPSHOT_REFRESH_FAILED,
                        "结构补拉失败：" + failure.getClass().getSimpleName(), restoredStatus(generation));
            }
            return false;
        } catch (RuntimeException failure) {
            failOwned(generation, GenerationReasonCode.SNAPSHOT_REFRESH_FAILED,
                    "结构补拉失败：" + failure.getClass().getSimpleName(), restoredStatus(generation));
            return false;
        }
    }

    /** 每片前按当前结构快照修复批次表账本，并以 owner/status CAS 回写权威计数。 */
    boolean reconcile(ConnectorSemanticGeneration generation) {
        List<ConnectorSchema> snapshot = safeSnapshot(generation.getConnectorId());
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        Map<String, ConnectorSchema> snapshotByName = new LinkedHashMap<>();
        Map<String, String> stamps = new HashMap<>();
        ConnectorSemanticService.tableStamps(fields).forEach((name, stamp) ->
                stamps.put(fold(name), stamp));
        for (ConnectorSchema schema : snapshot) {
            if (schema != null && schema.getObjectName() != null) {
                snapshotByName.putIfAbsent(fold(schema.getObjectName()), schema);
            }
        }

        List<ConnectorSemanticGenerationTable> loaded = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId()));
        List<ConnectorSemanticGenerationTable> rows = new ArrayList<>(loaded == null ? List.of() : loaded);
        Map<String, ConnectorSemanticGenerationTable> existing = new HashMap<>();
        for (ConnectorSemanticGenerationTable row : rows) {
            if (row != null && row.getObjectName() != null) {
                existing.putIfAbsent(fold(row.getObjectName()), row);
            }
        }

        for (ConnectorSchema schema : snapshot) {
            if (schema == null || schema.getObjectName() == null
                    || existing.containsKey(fold(schema.getObjectName()))) {
                continue;
            }
            ConnectorSemanticGenerationTable added = new ConnectorSemanticGenerationTable();
            added.setTenantId(generation.getTenantId());
            added.setConnectorId(generation.getConnectorId());
            added.setGenerationId(generation.getId());
            added.setObjectName(schema.getObjectName());
            added.setObjectType(schema.getObjectType());
            added.setImportanceRank(schema.getImportanceRank());
            added.setDispatchCount(0);
            added.setSubmitCount(0);
            added.setStructureRetries(0);
            String invalid = invalidReason(schema, fields.get(schema.getObjectName()));
            added.setStatus(invalid == null ? PENDING : SKIPPED);
            added.setLastRejectReason(invalid);
            try {
                if (tableMapper.insert(added) > 0) {
                    rows.add(added);
                    existing.put(fold(schema.getObjectName()), added);
                }
            } catch (DuplicateKeyException ignored) {
                // 同一 owner 正常不会并发；唯一键赢者会在下一片重读进账本。
            }
        }

        for (ConnectorSemanticGenerationTable row : rows) {
            if (row == null || row.getObjectName() == null) continue;
            ConnectorSchema current = snapshotByName.get(fold(row.getObjectName()));
            String status = row.getStatus();
            if (current == null && List.of(PENDING, DISPATCHED, DONE).contains(status)) {
                if (updateTableStatus(generation, row, REMOVED, status, null, null, null)) {
                    deleteStagedObject(generation, row.getObjectName());
                }
                continue;
            }
            if (current == null) continue;
            String invalid = invalidReason(current, fields.get(current.getObjectName()));
            if (SKIPPED.equals(status) && invalid == null) {
                updateTableStatus(generation, row, PENDING, SKIPPED, null, null, null);
                continue;
            }
            if (DONE.equals(status) && !Objects.equals(row.getStructureStamp(), stamps.get(fold(row.getObjectName())))) {
                int retries = number(row.getStructureRetries()) + 1;
                String next = retries > 2 ? GAVE_UP : PENDING;
                TableReasonCode code = retries > 2
                        ? TableReasonCode.STRUCTURE_UNSTABLE : TableReasonCode.STRUCTURE_CHANGED;
                if (updateTableStatus(generation, row, next, DONE, retries, 0,
                        code.name() + ": " + (retries > 2 ? "生成期间结构反复变化" : "结构已变化，重新生成"))) {
                    row.setSubmitCount(0);
                    deleteStagedObject(generation, row.getObjectName());
                }
                continue;
            }
            if (DISPATCHED.equals(status)) {
                updateTableStatus(generation, row, PENDING, DISPATCHED, null, null, null);
            }
        }

        Counts counts = counts(rows);
        applyCounts(generation, counts);
        return persistCounts(generation, counts);
    }

    private boolean updateTableStatus(ConnectorSemanticGeneration generation,
                                      ConnectorSemanticGenerationTable row,
                                      String next,
                                      String expected,
                                      Integer structureRetries,
                                      Integer dispatchCount,
                                      String rejectReason) {
        ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
        update.setStatus(next);
        if (structureRetries != null) update.setStructureRetries(structureRetries);
        if (dispatchCount != null) {
            update.setDispatchCount(dispatchCount);
            update.setSubmitCount(0);
            update.setAcceptedRows(0);
            update.setDroppedRows(0);
        }
        if (rejectReason != null) update.setLastRejectReason(rejectReason);
        LambdaUpdateWrapper<ConnectorSemanticGenerationTable> where =
                new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getId, row.getId())
                        .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                        .eq(ConnectorSemanticGenerationTable::getStatus, expected);
        if (dispatchCount != null) {
            where.set(ConnectorSemanticGenerationTable::getStructureStamp, null)
                    .set(ConnectorSemanticGenerationTable::getDoneAt, null);
        }
        if (SKIPPED.equals(expected)) {
            where.set(ConnectorSemanticGenerationTable::getLastRejectReason, null);
        }
        if (tableMapper.update(update, where) == 0) {
            return false;
        }
        row.setStatus(next);
        if (structureRetries != null) row.setStructureRetries(structureRetries);
        if (dispatchCount != null) {
            row.setDispatchCount(dispatchCount);
            row.setSubmitCount(0);
            row.setStructureStamp(null);
            row.setDoneAt(null);
        }
        row.setLastRejectReason(rejectReason);
        return true;
    }

    private void deleteStagedObject(ConnectorSemanticGeneration generation, String objectName) {
        if (STAGED.equals(generation.getMode())) {
            stagedMapper.physicalDeleteOwnedRows(generation.getTenantId(), generation.getId(), objectName);
        }
    }

    private static String invalidReason(ConnectorSchema schema, Map<String, FieldDetail> fields) {
        if (schema.getObjectName().length() > SemanticRowAssembler.NAME_MAX) {
            return TableReasonCode.NAME_TOO_LONG.name() + ": 表名超过 191 字符";
        }
        if (fields == null || fields.isEmpty() || containsDescribeError(schema.getDetailJson())) {
            return TableReasonCode.NOT_DESCRIBED.name() + ": 结构未取到";
        }
        return null;
    }

    private static boolean containsDescribeError(String detailJson) {
        return detailJson != null && detailJson.contains("\"__error__\"");
    }

    private boolean markSliceDispatched(ConnectorSemanticGeneration generation,
                                        int sliceNo,
                                        SemanticSlice slice) {
        Boolean committed = transactionTemplate.execute(tx -> {
            Date now = new Date(runtime.nowMillis());
            for (ConnectorSemanticGenerationTable row : slice.tables()) {
                ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
                update.setStatus(DISPATCHED);
                update.setSliceNo(sliceNo);
                update.setDispatchedAt(now);
                int changed = tableMapper.update(update,
                        new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                                .eq(ConnectorSemanticGenerationTable::getId, row.getId())
                                .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                                .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                                .eq(ConnectorSemanticGenerationTable::getStatus, PENDING));
                if (changed == 0) {
                    tx.setRollbackOnly();
                    return false;
                }
                row.setStatus(DISPATCHED);
                row.setSliceNo(sliceNo);
                row.setDispatchedAt(now);
            }
            ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
            update.setCurrentSliceNo(sliceNo);
            update.setSliceCount(slice.estimatedTotalSlices());
            int changed = generationMapper.update(update,
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getId, generation.getId())
                            .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                            .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                            .eq(ConnectorSemanticGeneration::getStatus, RUNNING));
            if (changed == 0) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            generation.setCurrentSliceNo(sliceNo);
            generation.setSliceCount(slice.estimatedTotalSlices());
            return true;
        }
        return false;
    }

    int progressBeforeSettle(ConnectorSemanticGeneration generation, int sliceNo) {
        Long progress = tableMapper.selectCount(new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                .eq(ConnectorSemanticGenerationTable::getSliceNo, sliceNo)
                .in(ConnectorSemanticGenerationTable::getStatus, DONE, GAVE_UP));
        return progress == null ? 0 : Math.toIntExact(Math.max(0L, progress));
    }

    void settleSlice(ConnectorSemanticGeneration generation,
                     SemanticSlice slice,
                     SliceRunResult result,
                     int maxDispatches) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                        .eq(ConnectorSemanticGenerationTable::getSliceNo,
                                slice.tables().isEmpty() ? -1 : slice.tables().get(0).getSliceNo())
                        .eq(ConnectorSemanticGenerationTable::getStatus, DISPATCHED));
        int limit = clamp(maxDispatches, 1, 10);
        for (ConnectorSemanticGenerationTable row : rows == null
                ? List.<ConnectorSemanticGenerationTable>of() : rows) {
            if (row == null || !DISPATCHED.equals(row.getStatus())) continue;
            int oldDispatches = number(row.getDispatchCount());
            int nextDispatches = result.started() ? oldDispatches + 1 : oldDispatches;
            boolean gaveUp = result.started() && nextDispatches >= limit;
            ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
            update.setStatus(gaveUp ? GAVE_UP : PENDING);
            update.setDispatchCount(nextDispatches);
            if (gaveUp) {
                update.setLastRejectReason(TableReasonCode.DISPATCH_LIMIT.name() + ": 多次派发未提交");
            }
            int changed = tableMapper.update(update,
                    new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                            .eq(ConnectorSemanticGenerationTable::getId, row.getId())
                            .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                            .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                            .eq(ConnectorSemanticGenerationTable::getStatus, DISPATCHED)
                            .eq(ConnectorSemanticGenerationTable::getSliceNo, row.getSliceNo()));
            if (changed > 0) {
                row.setStatus(gaveUp ? GAVE_UP : PENDING);
                row.setDispatchCount(nextDispatches);
                if (gaveUp) row.setLastRejectReason(update.getLastRejectReason());
            }
        }
    }

    boolean tokenCapReached(ConnectorSemanticGeneration generation, long maxTokensPerTable) {
        long totalTables = Math.max(0, number(generation.getTotalTables()));
        if (totalTables == 0L) return false;
        long consumed = saturatedAdd(nonNegative(generation.getInputTokens()),
                nonNegative(generation.getOutputTokens()));
        consumed = saturatedAdd(consumed, nonNegative(generation.getCacheWriteTokens()));
        long baseline = nonNegative(generation.getTokensAtResume());
        long used = Math.max(0L, consumed - Math.min(consumed, baseline));
        long cap = saturatedMultiply(totalTables, normalizedTokenLimit(maxTokensPerTable));
        return used > 0L && used >= cap;
    }

    private WaitOutcome sleepWithHeartbeat(int seconds) {
        long remaining = Math.max(0L, (long) seconds * 1000L);
        try {
            while (remaining > 0L) {
                Boundary boundary = boundary();
                if (boundary == Boundary.LOST) return WaitOutcome.LOST;
                if (boundary == Boundary.DELETED) return WaitOutcome.DELETED;
                if (boundary == Boundary.DISABLED) return WaitOutcome.DISABLED;
                long chunk = Math.min(WAIT_CHECKPOINT_MILLIS, remaining);
                runtime.sleep(chunk);
                remaining -= chunk;
            }
            return Thread.currentThread().isInterrupted() ? WaitOutcome.SHUTDOWN : WaitOutcome.OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return WaitOutcome.SHUTDOWN;
        }
    }

    private Boundary boundary() {
        SemanticGenerationHeartbeat.Checkpoint checkpoint = heartbeat.checkpoint();
        if (checkpoint == null) return heartbeat.lost() ? Boundary.LOST : Boundary.OK;
        if (checkpoint.lost()) return Boundary.LOST;
        if (checkpoint.connectionDeleted()) return Boundary.DELETED;
        if (checkpoint.connectionDisabled()) return Boundary.DISABLED;
        return Boundary.OK;
    }

    private void handleClaimBusy(ConnectorSemanticGeneration generation) {
        Date created = generation.getCreateTime();
        boolean expired = created != null && runtime.nowMillis() - created.getTime() >= CLAIM_GIVE_UP_MILLIS;
        if (expired) {
            transactionTemplate.executeWithoutResult(tx -> {
                ConnectorSemanticGeneration update = terminalUpdate(FAILED, GenerationReasonCode.CLAIM_BUSY,
                        "连接一直被另一次推导占用，本次未开始");
                if (transitionOwned(generation, update, List.of(RUNNING)) == 0) {
                    tx.setRollbackOnly();
                    return;
                }
                if (STAGED.equals(generation.getMode())) {
                    stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
                }
            });
            return;
        }
        Date notBefore = new Date(runtime.nowMillis() + CLAIM_REQUEUE_MILLIS);
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(QUEUED);
        update.setReasonCode(GenerationReasonCode.CLAIM_BUSY.name());
        update.setNotBefore(notBefore);
        int changed = generationMapper.update(update,
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .set(ConnectorSemanticGeneration::getOwnerToken, null)
                        .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                        .eq(ConnectorSemanticGeneration::getId, generation.getId())
                        .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                        .eq(ConnectorSemanticGeneration::getStatus, RUNNING));
        if (changed > 0) {
            generation.setStatus(QUEUED);
            generation.setOwnerToken(null);
            generation.setNotBefore(notBefore);
            generation.setReasonCode(GenerationReasonCode.CLAIM_BUSY.name());
        }
    }

    private void interruptBeforeClaim(ConnectorSemanticGeneration generation,
                                      GenerationReasonCode code,
                                      String reason) {
        Counts counts = counts(generation.getId());
        applyCounts(generation, counts);
        String note = notes.interrupted(generation, reason);
        ConnectorSemanticGeneration update = terminalUpdate(INTERRUPTED, code, note);
        putCounts(update, counts);
        transitionOwned(generation, update, List.of(RUNNING));
    }

    private boolean interruptOwned(ConnectorSemanticGeneration generation,
                                   GenerationReasonCode code,
                                   String reason) {
        heartbeat.stop();
        Boolean committed = transactionTemplate.execute(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            Counts counts = counts(generation.getId());
            applyCounts(generation, counts);
            String note = notes.interrupted(generation, reason);
            ConnectorSemanticGeneration update = terminalUpdate(INTERRUPTED, code, note);
            putCounts(update, counts);
            if (transitionOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return false;
            }
            resetDispatched(generation);
            if (!connectionClaim.release(generation.getConnectorId(), restoredStatus(generation), note, false)) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private boolean failOwned(ConnectorSemanticGeneration generation,
                              GenerationReasonCode code,
                              String reason,
                              String connectionStatus) {
        heartbeat.stop();
        Boolean committed = transactionTemplate.execute(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            Counts counts = counts(generation.getId());
            applyCounts(generation, counts);
            String note = notes.failed(generation, reason);
            ConnectorSemanticGeneration update = terminalUpdate(FAILED, code, note);
            putCounts(update, counts);
            if (transitionOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return false;
            }
            if (STAGED.equals(generation.getMode())) {
                stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
            }
            if (!connectionClaim.release(generation.getConnectorId(), connectionStatus, note, false)) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private GenerationRequest fallBack(ConnectorSemanticGeneration generation,
                                       SliceOutcomeDecision decision) {
        heartbeat.stop();
        String humanReason = fallbackReason(decision.note());
        Boolean committed = transactionTemplate.execute(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            Counts counts = counts(generation.getId());
            applyCounts(generation, counts);
            String note = notes.fellBack(decision.note());
            ConnectorSemanticGeneration update = terminalUpdate(FELL_BACK, decision.reasonCode(), note);
            putCounts(update, counts);
            if (transitionOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return false;
            }
            if (STAGED.equals(generation.getMode())) {
                stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
            }
            if (!connectionClaim.release(generation.getConnectorId(), restoredStatus(generation), note, false)) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (!Boolean.TRUE.equals(committed)) return null;
        return new GenerationRequest(generation.getConnectorId(), generation.getTenantId(),
                generation.getTriggeredBy(), trigger(generation.getTriggerKind()), humanReason);
    }

    private void cancelDeleted(ConnectorSemanticGeneration generation) {
        heartbeat.stop();
        transactionTemplate.executeWithoutResult(tx -> {
            ConnectorSemanticGeneration update = terminalUpdate(CANCELLED,
                    GenerationReasonCode.CONNECTION_DELETED, "连接已删除，语义层生成已取消");
            if (transitionOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return;
            }
            if (STAGED.equals(generation.getMode())) {
                stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
            }
        });
    }

    private int transitionOwned(ConnectorSemanticGeneration generation,
                                ConnectorSemanticGeneration update,
                                List<String> statuses) {
        int changed = generationMapper.update(update,
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .set(ConnectorSemanticGeneration::getOwnerToken, null)
                        .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                        .set(ConnectorSemanticGeneration::getNotBefore, null)
                        .eq(ConnectorSemanticGeneration::getId, generation.getId())
                        .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                        .in(ConnectorSemanticGeneration::getStatus, statuses));
        if (changed > 0) {
            generation.setStatus(update.getStatus());
            generation.setReasonCode(update.getReasonCode());
            generation.setNote(update.getNote());
            generation.setOwnerToken(null);
            generation.setCurrentRunId(null);
            generation.setFinishedAt(update.getFinishedAt());
        }
        return changed;
    }

    private ConnectorSemanticGeneration terminalUpdate(String status,
                                                        GenerationReasonCode code,
                                                        String note) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(status);
        update.setReasonCode(code == null ? null : code.name());
        update.setNote(note);
        if (List.of(FAILED, FELL_BACK, CANCELLED).contains(status)) {
            update.setFinishedAt(new Date(runtime.nowMillis()));
        }
        return update;
    }

    private void resetDispatched(ConnectorSemanticGeneration generation) {
        ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
        update.setStatus(PENDING);
        tableMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                .eq(ConnectorSemanticGenerationTable::getStatus, DISPATCHED));
    }

    private Counts counts(Long generationId) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generationId));
        return counts(rows == null ? List.of() : rows);
    }

    private static Counts counts(List<ConnectorSemanticGenerationTable> rows) {
        int total = 0;
        int done = 0;
        int gaveUp = 0;
        int skipped = 0;
        int removed = 0;
        for (ConnectorSemanticGenerationTable row : rows) {
            if (row == null) continue;
            String status = row.getStatus();
            if (SKIPPED.equals(status)) skipped++;
            else if (REMOVED.equals(status)) removed++;
            else {
                total++;
                if (DONE.equals(status)) done++;
                else if (GAVE_UP.equals(status)) gaveUp++;
            }
        }
        return new Counts(total, done, gaveUp, skipped, removed);
    }

    private boolean persistCounts(ConnectorSemanticGeneration generation, Counts counts) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        putCounts(update, counts);
        return generationMapper.update(update,
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .eq(ConnectorSemanticGeneration::getId, generation.getId())
                        .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                        .eq(ConnectorSemanticGeneration::getStatus, RUNNING)) > 0;
    }

    private ConnectorSemanticGeneration reloadOwned(ConnectorSemanticGeneration generation) {
        ConnectorSemanticGeneration live = generationMapper.selectById(generation.getId());
        return live != null && RUNNING.equals(live.getStatus())
                && Objects.equals(generation.getOwnerToken(), live.getOwnerToken()) ? live : null;
    }

    private static void copyRuntimeState(ConnectorSemanticGeneration target,
                                         ConnectorSemanticGeneration live) {
        target.setTotalTables(live.getTotalTables());
        target.setDoneTables(live.getDoneTables());
        target.setSkippedTables(live.getSkippedTables());
        target.setGaveUpTables(live.getGaveUpTables());
        target.setRemovedTables(live.getRemovedTables());
        target.setCurrentSliceNo(live.getCurrentSliceNo());
        target.setSliceCount(live.getSliceCount());
        target.setInputTokens(live.getInputTokens());
        target.setOutputTokens(live.getOutputTokens());
        target.setCacheReadTokens(live.getCacheReadTokens());
        target.setCacheWriteTokens(live.getCacheWriteTokens());
        target.setTokensAtResume(live.getTokensAtResume());
    }

    private static void applyCounts(ConnectorSemanticGeneration generation, Counts counts) {
        generation.setTotalTables(counts.total());
        generation.setDoneTables(counts.done());
        generation.setGaveUpTables(counts.gaveUp());
        generation.setSkippedTables(counts.skipped());
        generation.setRemovedTables(counts.removed());
    }

    private static void putCounts(ConnectorSemanticGeneration update, Counts counts) {
        update.setTotalTables(counts.total());
        update.setDoneTables(counts.done());
        update.setGaveUpTables(counts.gaveUp());
        update.setSkippedTables(counts.skipped());
        update.setRemovedTables(counts.removed());
    }

    static int clampSliceCap(int value) {
        return clamp(value, 1, 20);
    }

    static int nextSliceCap(int current, boolean timedOut) {
        int safe = clampSliceCap(current);
        return timedOut ? Math.max(1, safe / 2) : safe;
    }

    private static long normalizedTokenLimit(long value) {
        return value > 0L ? value : DEFAULT_MAX_TOKENS_PER_TABLE;
    }

    private static long saturatedAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static long saturatedMultiply(long a, long b) {
        return a != 0L && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    private static long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static int number(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String fold(String value) {
        return ConnectorSemanticService.ciFold(value);
    }

    private static String restoredStatus(ConnectorSemanticGeneration generation) {
        return STAGED.equals(generation.getMode()) && READY.equals(generation.getPrevSemanticStatus())
                ? READY : FAILED;
    }

    private static GenerationTrigger trigger(String value) {
        try {
            return GenerationTrigger.valueOf(value);
        } catch (RuntimeException ignored) {
            return GenerationTrigger.MANUAL_REGENERATE;
        }
    }

    private static String fallbackReason(String note) {
        String value = note == null || note.isBlank() ? "agent 路径不可用" : note.trim();
        if (value.startsWith("降级原因：")) value = value.substring("降级原因：".length()).trim();
        while (value.endsWith("。") || value.endsWith(".") || value.endsWith("！") || value.endsWith("!")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static void setTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) TenantContext.clear();
        else TenantContext.set(tenantId);
    }

    private static void restoreTenant(String previous) {
        if (previous == null || previous.isEmpty()) TenantContext.clear();
        else TenantContext.set(previous);
    }

    boolean isDraining() {
        return draining.get();
    }

    interface OrchestratorRuntime {
        String newToken();

        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    private static final class SystemOrchestratorRuntime implements OrchestratorRuntime {
        @Override
        public String newToken() {
            return UUID.randomUUID().toString();
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    }

    private enum Boundary { OK, LOST, DELETED, DISABLED }

    private enum WaitOutcome { OK, LOST, DELETED, DISABLED, SHUTDOWN }

    private record Counts(int total, int done, int gaveUp, int skipped, int removed) {
    }
}

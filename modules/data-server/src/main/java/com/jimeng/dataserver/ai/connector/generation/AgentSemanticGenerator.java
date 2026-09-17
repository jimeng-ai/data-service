package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 路径的请求线程受理器：只合并、续跑或创建库内批次，并唤醒排空器。
 *
 * <p>本类不访问客户库、不调用模型。真正的长时执行由实现 {@link SemanticGenerationKick} 的 O5 编排器完成；
 * 因而 O7 不需要、也不应提供一个空壳编排器。
 */
@Slf4j
@Service
public class AgentSemanticGenerator implements SemanticGenerator {

    static final Duration RUN_HEARTBEAT_FRESH_FOR = Duration.ofSeconds(180);
    static final Duration JAVA_CLAIM_FRESH_FOR = Duration.ofMinutes(30);

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FINALIZING = "FINALIZING";
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String TABLE_GAVE_UP = "GAVE_UP";
    private static final String TABLE_PENDING = "PENDING";
    private static final String MODE_DIRECT = "DIRECT";
    private static final String MODE_STAGED = "STAGED";
    private static final String MERGED_NOTE = "已有一次生成在排队或进行中，本次合并";
    private static final String RESUMED_NOTE = "继续上次未完成的生成，只补未覆盖的表";
    private static final String MISSING_TRIGGER_REASON = "缺少触发人，无法签发回调凭据";

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectionMapper connectionMapper;
    private final SingleCallSemanticGenerator singleCall;
    private final SemanticGenerationKick kick;
    private final SemanticGenerationNotes notes;
    private final ConnectorProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public AgentSemanticGenerator(ConnectorSemanticGenerationMapper generationMapper,
                                  ConnectorSemanticGenerationTableMapper tableMapper,
                                  ConnectionMapper connectionMapper,
                                  SingleCallSemanticGenerator singleCall,
                                  SemanticGenerationKick kick,
                                  SemanticGenerationNotes notes,
                                  ConnectorProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this(generationMapper, tableMapper, connectionMapper, singleCall, kick, notes, properties,
                transactionManager,
                Clock.systemDefaultZone());
    }

    AgentSemanticGenerator(ConnectorSemanticGenerationMapper generationMapper,
                           ConnectorSemanticGenerationTableMapper tableMapper,
                           ConnectionMapper connectionMapper,
                           SingleCallSemanticGenerator singleCall,
                           SemanticGenerationKick kick,
                           SemanticGenerationNotes notes,
                           ConnectorProperties properties,
                           PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.generationMapper = generationMapper;
        this.tableMapper = tableMapper;
        this.connectionMapper = connectionMapper;
        this.singleCall = singleCall;
        this.kick = kick;
        this.notes = notes;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public GeneratorKind kind() {
        return GeneratorKind.AGENT;
    }

    @Override
    public GenerationAck submit(GenerationRequest request) {
        try {
            if (request == null) {
                return rejected("agent 生成请求不能为空");
            }
            if (request.triggeredBy() == null) {
                return singleCall.submit(new GenerationRequest(request.connectorId(), request.tenantId(), null,
                        request.trigger(), MISSING_TRIGGER_REASON));
            }

            ConnectorSemanticGeneration active = findActive(request.connectorId());
            if (active != null) {
                return handleActive(active);
            }

            Connection connection = connectionMapper.selectById(request.connectorId());
            if (connection == null) {
                return rejected("连接不存在");
            }
            if (freshJavaClaim(connection)) {
                return new GenerationAck(kind(), false, null, "同一条连接上已有一次推导在进行中，本次跳过");
            }

            ConnectorSemanticGeneration created = newBatch(request, connection);
            try {
                generationMapper.insert(created);
            } catch (DuplicateKeyException duplicate) {
                ConnectorSemanticGeneration winner = findActive(request.connectorId());
                return new GenerationAck(kind(), true, winner == null ? null : winner.getId(), MERGED_NOTE);
            }

            String note = writeQueuedNote(created);
            kickQuietly(created.getId());
            return new GenerationAck(kind(), true, created.getId(), note);
        } catch (RuntimeException e) {
            log.warn("agent 语义层生成受理失败 connectorId={}: {}",
                    request == null ? null : request.connectorId(), safeMessage(e), e);
            return rejected("agent 生成受理失败");
        }
    }

    private GenerationAck handleActive(ConnectorSemanticGeneration active) {
        if (STATUS_QUEUED.equals(active.getStatus()) || freshRunning(active)) {
            return new GenerationAck(kind(), true, active.getId(), MERGED_NOTE);
        }

        Date staleBefore = new Date(clock.millis() - RUN_HEARTBEAT_FRESH_FOR.toMillis());
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(STATUS_QUEUED);
        update.setTokensAtResume(tokenTotal(active));
        LambdaUpdateWrapper<ConnectorSemanticGeneration> where =
                new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                        .set(ConnectorSemanticGeneration::getOwnerToken, null)
                        .set(ConnectorSemanticGeneration::getReasonCode, null)
                        .set(ConnectorSemanticGeneration::getNotBefore, null)
                        .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                        .setSql("resume_count = COALESCE(resume_count, 0) + 1")
                        .eq(ConnectorSemanticGeneration::getId, active.getId())
                        .eq(ConnectorSemanticGeneration::getStatus, active.getStatus());
        if (!STATUS_INTERRUPTED.equals(active.getStatus())) {
            where.and(w -> w.isNull(ConnectorSemanticGeneration::getHeartbeatAt)
                    .or().lt(ConnectorSemanticGeneration::getHeartbeatAt, staleBefore));
        }
        Boolean resumed = transactionTemplate.execute(status -> resumeBatch(active, update, where));
        if (!Boolean.TRUE.equals(resumed)) {
            return new GenerationAck(kind(), true, active.getId(), MERGED_NOTE);
        }

        active.setStatus(STATUS_QUEUED);
        String queued = writeQueuedNote(active);
        kickQuietly(active.getId());
        log.info("续跑语义层 agent 批次 generationId={} connectorId={} queueNote={}",
                active.getId(), active.getConnectorId(), queued);
        return new GenerationAck(kind(), true, active.getId(), RESUMED_NOTE);
    }

    /** 批次 CAS 与 GAVE_UP 重置必须同生共死；排队说明与 kick 刻意放在提交之后。 */
    private boolean resumeBatch(ConnectorSemanticGeneration active,
                                ConnectorSemanticGeneration update,
                                LambdaUpdateWrapper<ConnectorSemanticGeneration> where) {
        if (generationMapper.update(update, where) == 0) {
            return false;
        }

        ConnectorSemanticGenerationTable tableUpdate = new ConnectorSemanticGenerationTable();
        tableUpdate.setStatus(TABLE_PENDING);
        tableUpdate.setDispatchCount(0);
        tableUpdate.setSubmitCount(0);
        tableUpdate.setStructureRetries(0);
        tableMapper.update(tableUpdate, new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                .set(ConnectorSemanticGenerationTable::getSliceNo, null)
                .set(ConnectorSemanticGenerationTable::getLastRejectReason, null)
                .eq(ConnectorSemanticGenerationTable::getGenerationId, active.getId())
                .eq(ConnectorSemanticGenerationTable::getStatus, TABLE_GAVE_UP));
        return true;
    }

    private ConnectorSemanticGeneration findActive(Long connectorId) {
        return generationMapper.selectOne(new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getConnectorId, connectorId)
                .in(ConnectorSemanticGeneration::getStatus,
                        List.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_FINALIZING, STATUS_INTERRUPTED))
                .orderByDesc(ConnectorSemanticGeneration::getCreateTime)
                .last("LIMIT 1"));
    }

    private boolean freshRunning(ConnectorSemanticGeneration active) {
        if (!STATUS_RUNNING.equals(active.getStatus()) && !STATUS_FINALIZING.equals(active.getStatus())) {
            return false;
        }
        Date heartbeat = active.getHeartbeatAt();
        return heartbeat != null
                && !heartbeat.before(new Date(clock.millis() - RUN_HEARTBEAT_FRESH_FOR.toMillis()));
    }

    private boolean freshJavaClaim(Connection connection) {
        Date claimAt = connection.getSemanticClaimAt();
        return STATUS_RUNNING.equals(connection.getSemanticStatus()) && claimAt != null
                && !claimAt.before(new Date(clock.millis() - JAVA_CLAIM_FRESH_FOR.toMillis()));
    }

    private ConnectorSemanticGeneration newBatch(GenerationRequest request, Connection connection) {
        ConnectorProperties.SemanticAgent agent = properties.getSemantic().getAgent();
        ConnectorSemanticGeneration row = new ConnectorSemanticGeneration();
        row.setTenantId(request.tenantId());
        row.setConnectorId(request.connectorId());
        row.setMode(connection.getSemanticSyncedAt() == null ? MODE_DIRECT : MODE_STAGED);
        row.setTriggerKind(request.trigger().name());
        row.setTriggeredBy(request.triggeredBy());
        row.setStatus(STATUS_QUEUED);
        row.setModel(agent.getLlm().getModel());
        row.setConfigJson(configSnapshot(agent));
        return row;
    }

    private String writeQueuedNote(ConnectorSemanticGeneration generation) {
        long ahead = 0L;
        try {
            LambdaQueryWrapper<ConnectorSemanticGeneration> query =
                    new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                            .in(ConnectorSemanticGeneration::getStatus,
                                    List.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_FINALIZING));
            if (generation.getCreateTime() != null) {
                query.lt(ConnectorSemanticGeneration::getCreateTime, generation.getCreateTime());
            }
            Long count = generationMapper.selectCount(query);
            ahead = count == null ? 0L : Math.max(0L, count);
        } catch (RuntimeException e) {
            log.warn("统计租户内语义层排队数失败 generationId={}: {}", generation.getId(), safeMessage(e));
        }
        String note = notes.queued(ahead);
        try {
            Connection update = new Connection();
            update.setSemanticNote(note);
            connectionMapper.update(update, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, generation.getConnectorId())
                    .and(w -> w.isNull(Connection::getSemanticStatus)
                            .or().ne(Connection::getSemanticStatus, STATUS_RUNNING)));
        } catch (RuntimeException e) {
            log.warn("写入语义层排队说明失败 generationId={} connectorId={}: {}",
                    generation.getId(), generation.getConnectorId(), safeMessage(e));
        }
        return note;
    }

    private void kickQuietly(Long generationId) {
        try {
            kick.kick();
        } catch (RuntimeException e) {
            // 批次已经可靠落在库内；O6 对账器每 60 秒还会唤醒到期队列。
            log.warn("唤醒语义层生成排空器失败，批次留在队列 generationId={}: {}",
                    generationId, safeMessage(e));
        }
    }

    private String configSnapshot(ConnectorProperties.SemanticAgent agent) {
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("baseUrl", agent.getLlm().getBaseUrl());
        llm.put("model", agent.getLlm().getModel());
        llm.put("authScheme", agent.getLlm().getAuthScheme());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", agent.isEnabled());
        snapshot.put("callbackBaseUrl", agent.getCallbackBaseUrl());
        snapshot.put("llm", llm);
        snapshot.put("sliceMaxTables", agent.getSliceMaxTables());
        snapshot.put("sliceMaxChars", agent.getSliceMaxChars());
        snapshot.put("sliceWallClockSec", agent.getSliceWallClockSec());
        snapshot.put("sliceMaxTurnsPerTable", agent.getSliceMaxTurnsPerTable());
        snapshot.put("sliceMaxBudgetUsd", agent.getSliceMaxBudgetUsd());
        snapshot.put("sliceMaxRetries", agent.getSliceMaxRetries());
        snapshot.put("busyMaxRetries", agent.getBusyMaxRetries());
        snapshot.put("retryBackoffSec", agent.getRetryBackoffSec());
        snapshot.put("tableMaxSubmits", agent.getTableMaxSubmits());
        snapshot.put("tableMaxDispatches", agent.getTableMaxDispatches());
        snapshot.put("maxTokensPerTable", agent.getMaxTokensPerTable());
        snapshot.put("maxGaveUpRatio", agent.getMaxGaveUpRatio());
        snapshot.put("healthTimeoutMs", agent.getHealthTimeoutMs());
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("agent 配置快照序列化失败", e);
        }
    }

    private static long tokenTotal(ConnectorSemanticGeneration generation) {
        return value(generation.getInputTokens()) + value(generation.getOutputTokens())
                + value(generation.getCacheWriteTokens());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private GenerationAck rejected(String note) {
        return new GenerationAck(kind(), false, null, note);
    }

    private static String safeMessage(RuntimeException e) {
        return e.getClass().getSimpleName();
    }
}

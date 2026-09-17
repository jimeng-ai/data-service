package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Completes one owned semantic generation without ever dispatching sampling validation. */
@Component
public class SemanticGenerationFinalizer {

    private static final String RUNNING = "RUNNING";
    private static final String FINALIZING = "FINALIZING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String STAGED = "STAGED";
    private static final int PRECHECK_PAGE_SIZE = 50;
    private static final int MAX_PROMOTE_ATTEMPTS = 3;

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final SemanticConnectionClaim connectionClaim;
    private final SemanticGenerationHeartbeat heartbeat;
    private final SemanticGenerationNotes notes;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SemanticGenerationFinalizer(ConnectorSemanticGenerationMapper generationMapper,
                                       ConnectorSemanticGenerationTableMapper tableMapper,
                                       ConnectorSchemaMapper schemaMapper,
                                       ConnectorSemanticMapper semanticMapper,
                                       ConnectorSemanticStagedMapper stagedMapper,
                                       SemanticConnectionClaim connectionClaim,
                                       SemanticGenerationHeartbeat heartbeat,
                                       SemanticGenerationNotes notes,
                                       PlatformTransactionManager transactionManager) {
        this(generationMapper, tableMapper, schemaMapper, semanticMapper, stagedMapper, connectionClaim,
                heartbeat, notes, transactionManager, Clock.systemDefaultZone());
    }

    SemanticGenerationFinalizer(ConnectorSemanticGenerationMapper generationMapper,
                                ConnectorSemanticGenerationTableMapper tableMapper,
                                ConnectorSchemaMapper schemaMapper,
                                ConnectorSemanticMapper semanticMapper,
                                ConnectorSemanticStagedMapper stagedMapper,
                                SemanticConnectionClaim connectionClaim,
                                SemanticGenerationHeartbeat heartbeat,
                                SemanticGenerationNotes notes,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.generationMapper = generationMapper;
        this.tableMapper = tableMapper;
        this.schemaMapper = schemaMapper;
        this.semanticMapper = semanticMapper;
        this.stagedMapper = stagedMapper;
        this.connectionClaim = connectionClaim;
        this.heartbeat = heartbeat;
        this.notes = notes;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public FinishResult finish(ConnectorSemanticGeneration generation, double maxGaveUpRatio) {
        requireOwned(generation);
        Counts counts = counts(generation.getId());
        applyCounts(generation, counts);
        if (counts.done() == 0) {
            failNoOutput(generation);
            return FinishResult.DONE;
        }
        double ratio = clampRatio(maxGaveUpRatio);
        if (STAGED.equals(generation.getMode()) && counts.gaveUp() > ratio * counts.total()) {
            interruptForGaveUp(generation, counts.gaveUp());
            return FinishResult.DONE;
        }
        if (!enterFinalizing(generation)) {
            return FinishResult.DONE;
        }
        if (STAGED.equals(generation.getMode())) {
            return finishStaged(generation);
        }
        finishDirect(generation);
        return FinishResult.DONE;
    }

    private void failNoOutput(ConnectorSemanticGeneration generation) {
        heartbeat.stop();
        transactionTemplate.executeWithoutResult(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            String reason = "没有任何表生成成功";
            String note = notes.failed(generation, reason);
            ConnectorSemanticGeneration update = terminalUpdate(FAILED, GenerationReasonCode.NO_OUTPUT, note);
            if (updateOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return;
            }
            if (STAGED.equals(generation.getMode())) {
                stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
            }
            if (!connectionClaim.release(generation.getConnectorId(), restoredStatus(generation), note, false)) {
                tx.setRollbackOnly();
            }
        });
    }

    private void interruptForGaveUp(ConnectorSemanticGeneration generation, int gaveUp) {
        interruptOwned(generation, GenerationReasonCode.GAVE_UP_RATIO,
                "新一轮有 " + gaveUp + " 张表没能生成，未替换，上一版原样保留；点「重新生成」只重试这些表");
    }

    private boolean enterFinalizing(ConnectorSemanticGeneration generation) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(FINALIZING);
        int changed = generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getId, generation.getId())
                .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                .eq(ConnectorSemanticGeneration::getStatus, RUNNING));
        if (changed == 0) {
            return false;
        }
        generation.setStatus(FINALIZING);
        return true;
    }

    private void finishDirect(ConnectorSemanticGeneration generation) {
        heartbeat.stop();
        transactionTemplate.executeWithoutResult(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            String note = notes.ready(generation, readyStats(generation));
            ConnectorSemanticGeneration update = terminalUpdate(READY, null, note);
            if (updateOwned(generation, update, List.of(FINALIZING)) == 0
                    || !connectionClaim.release(generation.getConnectorId(), READY, note, true)) {
                tx.setRollbackOnly();
            }
        });
    }

    private FinishResult finishStaged(ConnectorSemanticGeneration generation) {
        boolean stopped = false;
        for (int attempt = 1; attempt <= MAX_PROMOTE_ATTEMPTS; attempt++) {
            Precheck precheck = precheck(generation);
            if (precheck.requeued()) {
                return returnToRunning(generation) ? FinishResult.CONTINUE : FinishResult.DONE;
            }
            applyCounts(generation, counts(generation.getId()));
            if (!stopped) {
                heartbeat.stop();
                stopped = true;
            }
            PromoteOutcome outcome = promote(generation, precheck.version());
            if (outcome == PromoteOutcome.READY || outcome == PromoteOutcome.LOST) {
                return FinishResult.DONE;
            }
            if (outcome == PromoteOutcome.EMPTY_REPLACE) {
                failOwned(generation, GenerationReasonCode.EMPTY_REPLACE,
                        "新一轮没有产出任何可写入的行，未替换，上一版原样保留", true,
                        restoredStatus(generation));
                return FinishResult.DONE;
            }
        }
        interruptOwned(generation, GenerationReasonCode.SNAPSHOT_CHURN, "收尾时结构反复刷新，未替换");
        return FinishResult.DONE;
    }

    /** Outside the promote transaction: page DONE rows in batches of 50 and invalidate only changed objects. */
    private Precheck precheck(ConnectorSemanticGeneration generation) {
        List<ConnectorSchema> snapshot = snapshotRows(generation);
        SnapshotVersion version = versionOf(snapshot);
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        Map<String, String> stamps = ConnectorSemanticService.tableStamps(fields);
        Map<String, ConnectorSchema> schemas = new HashMap<>();
        Map<String, String> foldedStamps = new HashMap<>();
        for (ConnectorSchema row : snapshot) {
            schemas.put(ConnectorSemanticService.ciFold(row.getObjectName()), row);
        }
        stamps.forEach((name, stamp) -> foldedStamps.put(ConnectorSemanticService.ciFold(name), stamp));

        boolean requeued = false;
        for (ConnectorSemanticGenerationTable table : doneTablesPaged(generation)) {
            String key = ConnectorSemanticService.ciFold(table.getObjectName());
            if (!schemas.containsKey(key)) {
                invalidateDone(generation, table, "REMOVED", table.getStructureRetries(), null);
                continue;
            }
            if (!Objects.equals(table.getStructureStamp(), foldedStamps.get(key))) {
                int retries = number(table.getStructureRetries()) + 1;
                String status = retries > 2 ? "GAVE_UP" : "PENDING";
                TableReasonCode reason = retries > 2
                        ? TableReasonCode.STRUCTURE_UNSTABLE : TableReasonCode.STRUCTURE_CHANGED;
                if (invalidateDone(generation, table, status, retries, reason)) {
                    requeued = true;
                }
            }
        }
        markInvalidJoinsStale(generation, fields);
        return new Precheck(version, requeued);
    }

    private List<ConnectorSemanticGenerationTable> doneTablesPaged(ConnectorSemanticGeneration generation) {
        List<ConnectorSemanticGenerationTable> result = new ArrayList<>();
        long pageNo = 1L;
        while (true) {
            Page<ConnectorSemanticGenerationTable> page = new Page<>(pageNo, PRECHECK_PAGE_SIZE, false);
            Page<ConnectorSemanticGenerationTable> loaded = tableMapper.selectPage(page,
                    new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                            .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                            .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                            .eq(ConnectorSemanticGenerationTable::getStatus, "DONE")
                            .orderByAsc(ConnectorSemanticGenerationTable::getId));
            List<ConnectorSemanticGenerationTable> records = loaded == null || loaded.getRecords() == null
                    ? List.of() : loaded.getRecords();
            result.addAll(records);
            if (records.size() < PRECHECK_PAGE_SIZE) {
                return result;
            }
            pageNo++;
        }
    }

    private boolean invalidateDone(ConnectorSemanticGeneration generation,
                                   ConnectorSemanticGenerationTable table,
                                   String status,
                                   Integer retries,
                                   TableReasonCode reason) {
        Boolean changed = transactionTemplate.execute(tx -> {
            ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
            update.setStatus(status);
            update.setStructureRetries(retries);
            update.setDispatchCount(0);
            update.setSubmitCount(0);
            update.setAcceptedRows(0);
            update.setDroppedRows(0);
            update.setLastRejectReason(reason == null ? null : reason.name() + ": "
                    + (reason == TableReasonCode.STRUCTURE_UNSTABLE ? "生成期间结构反复变化" : "结构已变化，重新生成"));
            int rows = tableMapper.update(update,
                    new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                            .set(ConnectorSemanticGenerationTable::getStructureStamp, null)
                            .set(ConnectorSemanticGenerationTable::getDoneAt, null)
                            .eq(ConnectorSemanticGenerationTable::getId, table.getId())
                            .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                            .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                            .eq(ConnectorSemanticGenerationTable::getStatus, "DONE"));
            if (rows == 0) {
                tx.setRollbackOnly();
                return false;
            }
            stagedMapper.physicalDeleteOwnedRows(generation.getTenantId(), generation.getId(),
                    table.getObjectName());
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private void markInvalidJoinsStale(ConnectorSemanticGeneration generation,
                                       Map<String, Map<String, FieldDetail>> fields) {
        List<ConnectorSemanticStaged> joins = stagedMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticStaged>()
                        .eq(ConnectorSemanticStaged::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticStaged::getGenerationId, generation.getId())
                        .eq(ConnectorSemanticStaged::getScope, "JOIN"));
        for (ConnectorSemanticStaged join : joins == null ? List.<ConnectorSemanticStaged>of() : joins) {
            Map<String, Object> detail = parseDetail(join.getDetailJson());
            String rightObject = text(detail.get("to_object"));
            String rightColumn = text(detail.get("to_column"));
            FieldDetail left = column(fields, join.getObjectName(), join.getFieldName());
            FieldDetail right = column(fields, rightObject, rightColumn);
            String anchor = left == null || right == null ? null : ConnectorSemanticService.joinAnchor(left, right);
            if (anchor != null && anchor.equals(join.getAnchorHash())) {
                continue;
            }
            ConnectorSemanticStaged update = new ConnectorSemanticStaged();
            update.setStatus("STALE");
            stagedMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticStaged>()
                    .eq(ConnectorSemanticStaged::getId, join.getId())
                    .eq(ConnectorSemanticStaged::getTenantId, generation.getTenantId())
                    .eq(ConnectorSemanticStaged::getGenerationId, generation.getId())
                    .eq(ConnectorSemanticStaged::getScope, "JOIN"));
        }
    }

    private boolean returnToRunning(ConnectorSemanticGeneration generation) {
        Counts counts = counts(generation.getId());
        applyCounts(generation, counts);
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(RUNNING);
        update.setTotalTables(counts.total());
        update.setDoneTables(counts.done());
        update.setGaveUpTables(counts.gaveUp());
        update.setSkippedTables(counts.skipped());
        update.setRemovedTables(counts.removed());
        int rows = generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getId, generation.getId())
                .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                .eq(ConnectorSemanticGeneration::getStatus, FINALIZING));
        if (rows > 0) {
            generation.setStatus(RUNNING);
        }
        return rows > 0;
    }

    private PromoteOutcome promote(ConnectorSemanticGeneration generation, SnapshotVersion expectedVersion) {
        PromoteOutcome outcome = transactionTemplate.execute(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            if (!expectedVersion.equals(versionOf(snapshotRows(generation)))) {
                tx.setRollbackOnly();
                return PromoteOutcome.VERSION_CONFLICT;
            }
            ConnectorSemanticGeneration touch = new ConnectorSemanticGeneration();
            touch.setHeartbeatAt(Date.from(clock.instant()));
            int owned = generationMapper.update(touch,
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getId, generation.getId())
                            .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                            .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                            .eq(ConnectorSemanticGeneration::getStatus, FINALIZING));
            if (owned == 0) {
                tx.setRollbackOnly();
                return PromoteOutcome.LOST;
            }

            long staged = count(stagedMapper.selectCount(new LambdaQueryWrapper<ConnectorSemanticStaged>()
                    .eq(ConnectorSemanticStaged::getTenantId, generation.getTenantId())
                    .eq(ConnectorSemanticStaged::getGenerationId, generation.getId())));
            long oldInferred = count(semanticMapper.selectCount(new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getTenantId, generation.getTenantId())
                    .eq(ConnectorSemantic::getConnectorId, generation.getConnectorId())
                    .eq(ConnectorSemantic::getSource, "INFERRED")));
            if (staged == 0 && oldInferred > 0) {
                tx.setRollbackOnly();
                return PromoteOutcome.EMPTY_REPLACE;
            }

            semanticMapper.physicalDeleteInferredForPromote(generation.getTenantId(),
                    generation.getConnectorId(), generation.getId());
            semanticMapper.moveStagedIn(generation.getTenantId(), generation.getConnectorId(), generation.getId());
            stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());

            Counts counts = counts(generation.getId());
            applyCounts(generation, counts);
            String note = notes.ready(generation, readyStats(generation, expectedVersion.count() >= 200));
            ConnectorSemanticGeneration ready = terminalUpdate(READY, null, note);
            ready.setTotalTables(counts.total());
            ready.setDoneTables(counts.done());
            ready.setGaveUpTables(counts.gaveUp());
            ready.setSkippedTables(counts.skipped());
            ready.setRemovedTables(counts.removed());
            if (updateOwned(generation, ready, List.of(FINALIZING)) == 0
                    || !connectionClaim.release(generation.getConnectorId(), READY, note, true)) {
                tx.setRollbackOnly();
                return PromoteOutcome.LOST;
            }
            return PromoteOutcome.READY;
        });
        return outcome == null ? PromoteOutcome.LOST : outcome;
    }

    private void failOwned(ConnectorSemanticGeneration generation,
                           GenerationReasonCode code,
                           String reason,
                           boolean clearStaged,
                           String connectionStatus) {
        heartbeat.stop();
        transactionTemplate.executeWithoutResult(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            String note = notes.failed(generation, reason);
            ConnectorSemanticGeneration update = terminalUpdate(FAILED, code, note);
            if (updateOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0) {
                tx.setRollbackOnly();
                return;
            }
            if (clearStaged) {
                stagedMapper.physicalDeleteByGeneration(generation.getTenantId(), generation.getId());
            }
            if (!connectionClaim.release(generation.getConnectorId(), connectionStatus, note, false)) {
                tx.setRollbackOnly();
            }
        });
    }

    private void interruptOwned(ConnectorSemanticGeneration generation,
                                GenerationReasonCode code,
                                String reason) {
        heartbeat.stop();
        transactionTemplate.executeWithoutResult(tx -> {
            connectionClaim.lockRow(generation.getConnectorId());
            Counts counts = counts(generation.getId());
            applyCounts(generation, counts);
            String note = notes.interrupted(generation, reason);
            ConnectorSemanticGeneration update = terminalUpdate(INTERRUPTED, code, note);
            update.setTotalTables(counts.total());
            update.setDoneTables(counts.done());
            update.setGaveUpTables(counts.gaveUp());
            update.setSkippedTables(counts.skipped());
            update.setRemovedTables(counts.removed());
            if (updateOwned(generation, update, List.of(RUNNING, FINALIZING)) == 0
                    || !connectionClaim.release(generation.getConnectorId(), restoredStatus(generation), note, false)) {
                tx.setRollbackOnly();
            }
        });
    }

    private SemanticGenerationNotes.ReadyStats readyStats(ConnectorSemanticGeneration generation) {
        return readyStats(generation, snapshotRows(generation).size() >= 200);
    }

    private SemanticGenerationNotes.ReadyStats readyStats(ConnectorSemanticGeneration generation,
                                                           boolean snapshotTruncated) {
        List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getTenantId, generation.getTenantId())
                .eq(ConnectorSemantic::getConnectorId, generation.getConnectorId())
                .eq(ConnectorSemantic::getSource, "INFERRED"));
        int objects = 0;
        int fields = 0;
        int joins = 0;
        int caveats = 0;
        for (ConnectorSemantic row : rows == null ? List.<ConnectorSemantic>of() : rows) {
            if ("OBJECT".equals(row.getScope())) objects++;
            else if ("FIELD".equals(row.getScope())) fields++;
            else if ("JOIN".equals(row.getScope())) joins++;
            else if ("CAVEAT".equals(row.getScope())) caveats++;
        }
        return new SemanticGenerationNotes.ReadyStats(objects, fields, joins, caveats, snapshotTruncated);
    }

    private List<ConnectorSchema> snapshotRows(ConnectorSemanticGeneration generation) {
        List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getTenantId, generation.getTenantId())
                .eq(ConnectorSchema::getConnectorId, generation.getConnectorId()));
        return rows == null ? List.of() : rows;
    }

    private static SnapshotVersion versionOf(List<ConnectorSchema> rows) {
        Date latest = null;
        for (ConnectorSchema row : rows) {
            Date syncedAt = row.getSyncedAt();
            if (syncedAt != null && (latest == null || syncedAt.after(latest))) {
                latest = syncedAt;
            }
        }
        return new SnapshotVersion(rows.size(), latest == null ? null : new Date(latest.getTime()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = CommonUtil.getObjectMapper().readValue(json, LinkedHashMap.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static FieldDetail column(Map<String, Map<String, FieldDetail>> fields,
                                      String objectName,
                                      String fieldName) {
        if (objectName == null || fieldName == null) return null;
        Map<String, FieldDetail> columns = fields.get(objectName);
        if (columns == null) {
            String objectKey = ConnectorSemanticService.ciFold(objectName);
            columns = fields.entrySet().stream()
                    .filter(entry -> ConnectorSemanticService.ciFold(entry.getKey()).equals(objectKey))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (columns == null) return null;
        FieldDetail exact = columns.get(fieldName);
        if (exact != null) return exact;
        String fieldKey = ConnectorSemanticService.ciFold(fieldName);
        return columns.entrySet().stream()
                .filter(entry -> ConnectorSemanticService.ciFold(entry.getKey()).equals(fieldKey))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static int number(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long count(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private int updateOwned(ConnectorSemanticGeneration generation,
                            ConnectorSemanticGeneration update,
                            List<String> statuses) {
        return generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getOwnerToken, null)
                .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                .eq(ConnectorSemanticGeneration::getId, generation.getId())
                .eq(ConnectorSemanticGeneration::getTenantId, generation.getTenantId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                .in(ConnectorSemanticGeneration::getStatus, statuses));
    }

    private ConnectorSemanticGeneration terminalUpdate(String status,
                                                        GenerationReasonCode reason,
                                                        String note) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(status);
        update.setReasonCode(reason == null ? null : reason.name());
        update.setNote(note);
        update.setFinishedAt(READY.equals(status) || FAILED.equals(status) ? Date.from(clock.instant()) : null);
        return update;
    }

    private Counts counts(Long generationId) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generationId));
        int total = 0;
        int done = 0;
        int gaveUp = 0;
        int skipped = 0;
        int removed = 0;
        for (ConnectorSemanticGenerationTable row : rows == null
                ? List.<ConnectorSemanticGenerationTable>of() : rows) {
            switch (row.getStatus() == null ? "" : row.getStatus()) {
                case "SKIPPED" -> skipped++;
                case "REMOVED" -> removed++;
                default -> {
                    total++;
                    if ("DONE".equals(row.getStatus())) done++;
                    else if ("GAVE_UP".equals(row.getStatus())) gaveUp++;
                }
            }
        }
        return new Counts(total, done, gaveUp, skipped, removed);
    }

    private static void applyCounts(ConnectorSemanticGeneration generation, Counts counts) {
        generation.setTotalTables(counts.total());
        generation.setDoneTables(counts.done());
        generation.setGaveUpTables(counts.gaveUp());
        generation.setSkippedTables(counts.skipped());
        generation.setRemovedTables(counts.removed());
    }

    private static double clampRatio(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String restoredStatus(ConnectorSemanticGeneration generation) {
        return STAGED.equals(generation.getMode()) && READY.equals(generation.getPrevSemanticStatus())
                ? READY : FAILED;
    }

    private static void requireOwned(ConnectorSemanticGeneration generation) {
        if (generation == null || generation.getId() == null || generation.getConnectorId() == null
                || generation.getTenantId() == null || generation.getOwnerToken() == null) {
            throw new IllegalArgumentException("owned generation is required");
        }
    }

    public enum FinishResult {
        DONE,
        CONTINUE
    }

    private record Counts(int total, int done, int gaveUp, int skipped, int removed) {
    }

    private record SnapshotVersion(int count, Date maxSyncedAt) {
        private SnapshotVersion {
            maxSyncedAt = maxSyncedAt == null ? null : new Date(maxSyncedAt.getTime());
        }

        @Override
        public Date maxSyncedAt() {
            return maxSyncedAt == null ? null : new Date(maxSyncedAt.getTime());
        }
    }

    private record Precheck(SnapshotVersion version, boolean requeued) {
    }

    private enum PromoteOutcome {
        READY,
        VERSION_CONFLICT,
        EMPTY_REPLACE,
        LOST
    }
}

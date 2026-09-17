package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.generation.SemanticConnectionClaim;
import com.jimeng.dataserver.ai.connector.generation.TableReasonCode;
import com.jimeng.dataserver.ai.connector.generation.consistency.ProposedEntry;
import com.jimeng.dataserver.ai.connector.generation.consistency.RuleContext;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.AssemblyReport;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.EntryOutcome;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.EntryRef;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语义层 agent 的逐表提交事务。形状校验和独立运行确认由 callback service 先完成；本类把
 * 范围复核、确定性过滤、按模式替换与 generation_table CAS 放在同一个事务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSubmissionWriter {

    public static final String CODE_HUMAN_ROW_EXISTS = "HUMAN_ROW_EXISTS";
    public static final String CODE_WRITE_CONFLICT = "WRITE_CONFLICT";

    private static final String DIRECT = "DIRECT";
    private static final String STAGED = "STAGED";
    private static final String DISPATCHED = "DISPATCHED";
    private static final String DONE = "DONE";
    private static final String GAVE_UP = "GAVE_UP";
    private static final int LAST_REASON_MAX = 500;
    private static final Set<String> SUBMITTABLE = Set.of(DISPATCHED, DONE);
    private static final Comparator<ConnectorSemanticGenerationTable> BATCH_ORDER = Comparator
            .comparing(ConnectorSemanticGenerationTable::getImportanceRank, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(ConnectorSemanticGenerationTable::getObjectName, Comparator.nullsLast(String::compareTo));

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final ConnectorProperties properties;
    private final SemanticConsistencyRuleRegistry ruleRegistry;
    private final ConnectorSemanticService semanticService;
    private final SemanticConnectionClaim connectionClaim;

    @Transactional(rollbackFor = Exception.class)
    public SubmitResultView submit(SemanticAgentPrincipal principal, SubmitRequest request) {
        if (principal == null) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
        boolean direct = DIRECT.equals(principal.mode());
        if (!direct && !STAGED.equals(principal.mode())) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED, "语义层生成模式无效");
        }

        // DIRECT 与所有同时写连接、批次的事务统一采用 connection → generation 的锁序。
        // 锁前不能做普通 SELECT：MySQL RR 会固定旧 read view，等待刷新事务后仍可能读到旧结构快照。
        if (direct) {
            connectionClaim.lockRow(principal.connectorId());
        }
        Connection connection = semanticService.requireOwned(principal.connectorId());
        if (!Objects.equals(connection.getTenantId(), principal.tenantId())) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        ConnectorSemanticGeneration generation = lockRun(principal);
        List<ConnectorSchema> snapshot = snapshot(principal);
        String table = SemanticRowAssembler.str(request.getSubmission(), "table");
        ConnectorSchema snapshotTable = snapshot.stream()
                .filter(row -> Objects.equals(table, row.getObjectName()))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ExceptionCode.NOT_FOUND, "快照里没有表 " + table));

        List<ConnectorSemanticGenerationTable> batchRows = batch(principal);
        ConnectorSemanticGenerationTable batch = batchRows.stream()
                .filter(row -> Objects.equals(table, row.getObjectName()))
                .findFirst()
                .orElseThrow(() -> invalid("表 " + table + " 不在本批次"));
        int maxSubmits = maxSubmits();
        validateRange(principal, request, snapshot, snapshotTable, batch, maxSubmits);

        AssembledSubmission assembled = assemble(table, request.getSubmission());
        List<ConnectorSemantic> existing = existingSemantics(principal, table);
        Set<String> answeredTerms = SemanticRowAssembler.answeredTerms(existing);
        List<EntryOutcomeView> outcomes = new ArrayList<>();
        applyRules(table, assembled.parsed(), snapshot, answeredTerms, outcomes);

        AssemblyReport report = new AssemblyReport();
        Map<String, Map<String, FieldDetail>> fieldsByObject = SemanticRowAssembler.parseFields(snapshot);
        List<ConnectorSemantic> rows = SemanticRowAssembler.toRows(assembled.parsed(), fieldsByObject,
                answeredTerms, report, List.of());
        report.outcomes().stream().map(SemanticSubmissionWriter::viewOf).forEach(outcomes::add);
        rows = dropHumanConflicts(rows, existing, report, outcomes);

        boolean rejectedBeforeWrite = hasRejected(outcomes);
        boolean replace = assembled.skipped() || !rejectedBeforeWrite || !rows.isEmpty();
        TransactionStatus transaction = null;
        Object writeSavepoint = null;
        if (replace && rejectedBeforeWrite && !rows.isEmpty()) {
            // 只有「有 rejected 但仍有可写候选」才可能在 G 步全撞冲突后从 PARTIAL 退成 REJECTED。
            // 保存点让这种最终 REJECTED 仍满足“不写不删”，同时保留外层 H 的 submit_count 登记。
            transaction = TransactionAspectSupport.currentTransactionStatus();
            writeSavepoint = transaction.createSavepoint();
        }
        WriteCounts written = new WriteCounts();
        if (replace) {
            if (direct) {
                writeDirect(principal, connection, table, rows, report, outcomes, written);
            } else {
                writeStaged(principal, connection, table, rows, report, outcomes, written);
            }
        }

        int acceptedRows = written.total();
        if (writeSavepoint != null) {
            if (acceptedRows == 0) {
                transaction.rollbackToSavepoint(writeSavepoint);
            }
            transaction.releaseSavepoint(writeSavepoint);
        }
        String responseStatus;
        if (assembled.skipped()) {
            responseStatus = "SKIPPED";
        } else if (hasRejected(outcomes)) {
            responseStatus = acceptedRows > 0 ? "PARTIAL" : "REJECTED";
        } else {
            responseStatus = "ACCEPTED";
        }

        int nextSubmitCount = nz(batch.getSubmitCount()) + 1;
        String databaseStatus = databaseStatus(batch, responseStatus, nextSubmitCount, maxSubmits);
        String reason = lastReason(responseStatus, databaseStatus, assembled.skipReason(), outcomes);
        int droppedRows = distinctOutcomeCount(outcomes);
        updateBatch(principal, batch, responseStatus, databaseStatus, request.getStructureStamp(),
                acceptedRows, droppedRows, reason);

        return response(table, responseStatus, nextSubmitCount, maxSubmits, written, outcomes,
                generation, batchRows, batch, databaseStatus, principal.sliceNo());
    }

    /**
     * callback 计数已由独立事务留下；主提交事务仍须用同一组可信条件锁住批次行，
     * 让同批提交（尤其暂存 CAVEAT 的读改写）彼此串行，且不重复增加 callback 计数。
     */
    private ConnectorSemanticGeneration lockRun(SemanticAgentPrincipal principal) {
        List<ConnectorSemanticGeneration> rows = generationMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getId, principal.generationId())
                .eq(ConnectorSemanticGeneration::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticGeneration::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticGeneration::getStatus, "RUNNING")
                .eq(ConnectorSemanticGeneration::getCurrentRunId, principal.runId())
                .last("FOR UPDATE"));
        if (rows == null || rows.size() != 1) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
        return rows.get(0);
    }

    private List<ConnectorSchema> snapshot(SemanticAgentPrincipal principal) {
        List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getTenantId, principal.tenantId())
                .eq(ConnectorSchema::getConnectorId, principal.connectorId())
                .orderByAsc(ConnectorSchema::getId));
        return rows == null ? List.of() : rows;
    }

    private List<ConnectorSemanticGenerationTable> batch(SemanticAgentPrincipal principal) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getTenantId, principal.tenantId())
                        .eq(ConnectorSemanticGenerationTable::getConnectorId, principal.connectorId())
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, principal.generationId()));
        return rows == null ? List.of() : rows;
    }

    private void validateRange(SemanticAgentPrincipal principal, SubmitRequest request,
                               List<ConnectorSchema> snapshot, ConnectorSchema snapshotTable,
                               ConnectorSemanticGenerationTable batch, int maxSubmits) {
        if (!Objects.equals(batch.getSliceNo(), principal.sliceNo()) || !SUBMITTABLE.contains(batch.getStatus())) {
            throw invalid("表 " + batch.getObjectName() + " 不在当前片，或已经放弃");
        }
        if (nz(batch.getSubmitCount()) >= maxSubmits) {
            throw invalid("表 " + batch.getObjectName() + " 已达到提交次数上限 " + maxSubmits);
        }
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        String currentStamp = ConnectorSemanticService.tableStamp(snapshotTable.getObjectName(),
                fields.getOrDefault(snapshotTable.getObjectName(), Map.of()));
        if (!Objects.equals(currentStamp, request.getStructureStamp())) {
            throw invalid("本表结构在你读取之后已刷新，请重新调用 get_table_metadata 再提交");
        }
    }

    @SuppressWarnings("unchecked")
    private static AssembledSubmission assemble(String table, Map<String, Object> submission) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        Object object = submission.get("object");
        List<Object> objects = new ArrayList<>();
        if (object instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) raw);
            copy.put("name", table);
            objects.add(copy);
        }
        parsed.put(SemanticRowAssembler.KIND_OBJECTS, objects);
        parsed.put(SemanticRowAssembler.KIND_FIELDS,
                ownedArray(submission.get("fields"), table, false));
        parsed.put(SemanticRowAssembler.KIND_JOINS,
                ownedArray(submission.get("joins"), table, false));
        parsed.put(SemanticRowAssembler.KIND_AMBIGUITIES,
                ownedArray(submission.get("ambiguities"), table, true));

        boolean skipped = object == null
                && ((List<?>) parsed.get(SemanticRowAssembler.KIND_FIELDS)).isEmpty()
                && ((List<?>) parsed.get(SemanticRowAssembler.KIND_JOINS)).isEmpty()
                && ((List<?>) parsed.get(SemanticRowAssembler.KIND_AMBIGUITIES)).isEmpty();
        return new AssembledSubmission(parsed, skipped,
                skipped ? SemanticRowAssembler.str(submission, "skip_reason") : null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ownedArray(Object raw, String table, boolean ambiguity) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                out.add(value);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            if (!ambiguity) {
                copy.put("object", table);
            } else {
                List<String> applies = new ArrayList<>(SemanticRowAssembler.strList(copy, "applies_to"));
                if (applies.size() > SemanticRowAssembler.DETAIL_LIST_MAX) {
                    applies = new ArrayList<>(applies.subList(0, SemanticRowAssembler.DETAIL_LIST_MAX));
                }
                if (!applies.contains(table)) {
                    if (applies.size() == SemanticRowAssembler.DETAIL_LIST_MAX) {
                        applies.remove(applies.size() - 1);
                    }
                    applies.add(table);
                }
                copy.put("applies_to", applies);
            }
            out.add(copy);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyRules(String table, Map<String, Object> parsed, List<ConnectorSchema> snapshot,
                            Set<String> answeredTerms, List<EntryOutcomeView> outcomes) {
        List<ProposedEntry> proposed = ProposedEntry.listOf(parsed);
        RuleContext context = RuleContext.of(table, snapshot, answeredTerms, proposed);
        for (ProposedEntry entry : proposed) {
            List<SemanticConsistencyRuleRegistry.Finding> findings = ruleRegistry.check(entry, context);
            if (findings.isEmpty()) {
                continue;
            }
            for (SemanticConsistencyRuleRegistry.Finding finding : findings) {
                outcomes.add(view(entry.path(), entry.key(), finding.status(), finding.code(), finding.message()));
            }
            Object raw = parsed.get(entry.kind());
            if (raw instanceof List<?> list && entry.index() >= 0 && entry.index() < list.size()) {
                ((List<Object>) list).set(entry.index(), null);
            }
        }
    }

    private List<ConnectorSemantic> existingSemantics(SemanticAgentPrincipal principal, String table) {
        List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getTenantId, principal.tenantId())
                .eq(ConnectorSemantic::getConnectorId, principal.connectorId())
                .and(w -> w.eq(ConnectorSemantic::getObjectName, table)
                        .or().in(ConnectorSemantic::getScope,
                                List.of(ConnectorSemanticService.SCOPE_CAVEAT,
                                        ConnectorSemanticService.SCOPE_METRIC)))
                .orderByAsc(ConnectorSemantic::getId));
        return rows == null ? List.of() : rows;
    }

    private static List<ConnectorSemantic> dropHumanConflicts(List<ConnectorSemantic> rows,
                                                               List<ConnectorSemantic> existing,
                                                               AssemblyReport report,
                                                               List<EntryOutcomeView> outcomes) {
        Set<String> protectedKeys = new LinkedHashSet<>();
        for (ConnectorSemantic row : existing) {
            if (!ConnectorSemanticService.SOURCE_INFERRED.equals(row.getSource())) {
                protectedKeys.add(ConnectorSemanticService.uniqueKey(row));
            }
        }
        List<ConnectorSemantic> accepted = new ArrayList<>();
        for (ConnectorSemantic row : rows) {
            if (!protectedKeys.contains(ConnectorSemanticService.uniqueKey(row))) {
                accepted.add(row);
                continue;
            }
            EntryRef ref = report.originOf(row);
            outcomes.add(view(ref, SemanticRowAssembler.STATUS_DROPPED, CODE_HUMAN_ROW_EXISTS,
                    "同一位置已有人工确认或导入的说明，机器说明不入库，不需要重提"));
        }
        return accepted;
    }

    private void writeDirect(SemanticAgentPrincipal principal, Connection connection, String table,
                             List<ConnectorSemantic> rows, AssemblyReport report,
                             List<EntryOutcomeView> outcomes, WriteCounts counts) {
        semanticMapper.physicalDeleteInferredOfObject(connection.getTenantId(), principal.connectorId(), table);
        for (ConnectorSemantic row : rows) {
            if (ConnectorSemanticService.SCOPE_CAVEAT.equals(row.getScope())) {
                ConnectorSemanticService.CaveatMergeResult merged =
                        semanticService.mergeInferredCaveat(principal.connectorId(), row);
                if (merged == ConnectorSemanticService.CaveatMergeResult.INSERTED
                        || merged == ConnectorSemanticService.CaveatMergeResult.MERGED) {
                    counts.accept(row);
                } else if (merged == ConnectorSemanticService.CaveatMergeResult.SKIPPED_NOT_INFERRED) {
                    outcomes.add(view(report.originOf(row), SemanticRowAssembler.STATUS_DROPPED,
                            CODE_HUMAN_ROW_EXISTS,
                            "这个口径已有人工确认或导入的说明，机器问题不入库，不需要重提"));
                } else {
                    outcomes.add(writeConflict(report.originOf(row)));
                }
                continue;
            }
            prepareMain(row, connection.getTenantId(), principal.connectorId());
            try {
                semanticMapper.insert(row);
                counts.accept(row);
            } catch (DuplicateKeyException conflict) {
                log.info("语义层 agent 写主表撞唯一键，本条跳过 connectorId={} table={} key={}",
                        principal.connectorId(), table, ConnectorSemanticService.uniqueKey(row));
                outcomes.add(writeConflict(report.originOf(row)));
            }
        }
    }

    private void writeStaged(SemanticAgentPrincipal principal, Connection connection, String table,
                             List<ConnectorSemantic> rows, AssemblyReport report,
                             List<EntryOutcomeView> outcomes, WriteCounts counts) {
        stagedMapper.physicalDeleteOwnedRows(connection.getTenantId(), principal.generationId(), table);
        Map<String, ConnectorSemanticStaged> caveats = stagedCaveats(principal);
        for (ConnectorSemantic row : rows) {
            if (ConnectorSemanticService.SCOPE_CAVEAT.equals(row.getScope())) {
                mergeStagedCaveat(principal, connection, table, row, report.originOf(row),
                        caveats, outcomes, counts);
                continue;
            }
            ConnectorSemanticStaged staged = toStaged(principal, connection, table, row);
            try {
                stagedMapper.insert(staged);
                counts.accept(row);
            } catch (DuplicateKeyException conflict) {
                log.info("语义层 agent 写暂存表撞唯一键，本条跳过 generationId={} table={} key={}",
                        principal.generationId(), table, ConnectorSemanticService.uniqueKey(row));
                outcomes.add(writeConflict(report.originOf(row)));
            }
        }
    }

    private Map<String, ConnectorSemanticStaged> stagedCaveats(SemanticAgentPrincipal principal) {
        List<ConnectorSemanticStaged> rows = stagedMapper.selectList(new LambdaQueryWrapper<ConnectorSemanticStaged>()
                .eq(ConnectorSemanticStaged::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticStaged::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticStaged::getGenerationId, principal.generationId())
                .eq(ConnectorSemanticStaged::getScope, ConnectorSemanticService.SCOPE_CAVEAT)
                .orderByAsc(ConnectorSemanticStaged::getId));
        Map<String, ConnectorSemanticStaged> out = new LinkedHashMap<>();
        for (ConnectorSemanticStaged row : rows == null ? List.<ConnectorSemanticStaged>of() : rows) {
            out.putIfAbsent(ConnectorSemanticService.ciFold(row.getTerm()), row);
        }
        return out;
    }

    private void mergeStagedCaveat(SemanticAgentPrincipal principal, Connection connection, String table,
                                   ConnectorSemantic row, EntryRef origin,
                                   Map<String, ConnectorSemanticStaged> caveats,
                                   List<EntryOutcomeView> outcomes, WriteCounts counts) {
        String key = ConnectorSemanticService.ciFold(row.getTerm());
        ConnectorSemanticStaged existing = caveats.get(key);
        if (existing == null) {
            ConnectorSemanticStaged staged = toStaged(principal, connection, table, row);
            try {
                stagedMapper.insert(staged);
                caveats.put(key, staged);
                counts.accept(row);
            } catch (DuplicateKeyException conflict) {
                outcomes.add(writeConflict(origin));
            }
            return;
        }

        String detail = mergeAppliesTo(existing.getDetailJson(), row.getDetailJson());
        ConnectorSemanticStaged update = new ConnectorSemanticStaged();
        update.setDetailJson(detail);
        int changed = stagedMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticStaged>()
                .eq(ConnectorSemanticStaged::getId, existing.getId())
                .eq(ConnectorSemanticStaged::getTenantId, connection.getTenantId())
                .eq(ConnectorSemanticStaged::getGenerationId, principal.generationId()));
        if (changed == 1) {
            existing.setDetailJson(detail);
            counts.accept(row);
        } else {
            outcomes.add(writeConflict(origin));
        }
    }

    private static void prepareMain(ConnectorSemantic row, String tenantId, long connectorId) {
        row.setId(null);
        row.setTenantId(tenantId);
        row.setConnectorId(connectorId);
        row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        if (row.getStatus() == null) row.setStatus(ConnectorSemanticService.ST_DRAFT);
        if (row.getVerified() == null) row.setVerified(ConnectorSemanticService.V_NONE);
        if (row.getAnchorKind() == null) row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
    }

    private static ConnectorSemanticStaged toStaged(SemanticAgentPrincipal principal, Connection connection,
                                                     String table, ConnectorSemantic row) {
        ConnectorSemanticStaged staged = new ConnectorSemanticStaged();
        staged.setTenantId(connection.getTenantId());
        staged.setConnectorId(principal.connectorId());
        staged.setGenerationId(principal.generationId());
        staged.setOwnerObject(table);
        staged.setScope(row.getScope());
        staged.setObjectName(row.getObjectName());
        staged.setFieldName(row.getFieldName());
        staged.setTerm(row.getTerm());
        staged.setGloss(row.getGloss());
        staged.setDetailJson(row.getDetailJson());
        staged.setEvidence(row.getEvidence());
        staged.setConfidence(row.getConfidence());
        staged.setVerified(row.getVerified() == null ? ConnectorSemanticService.V_NONE : row.getVerified());
        staged.setStatus(row.getStatus() == null ? ConnectorSemanticService.ST_DRAFT : row.getStatus());
        staged.setAnchorKind(row.getAnchorKind() == null ? ConnectorSemanticService.ANCHOR_NONE : row.getAnchorKind());
        staged.setAnchorHash(row.getAnchorHash());
        return staged;
    }

    private void updateBatch(SemanticAgentPrincipal principal, ConnectorSemanticGenerationTable observed,
                             String responseStatus, String databaseStatus, String structureStamp,
                             int acceptedRows, int droppedRows, String reason) {
        ConnectorSemanticGenerationTable update = new ConnectorSemanticGenerationTable();
        update.setStatus(databaseStatus);
        update.setAcceptedRows(acceptedRows);
        update.setDroppedRows(droppedRows);
        if (!"REJECTED".equals(responseStatus)) {
            update.setStructureStamp(structureStamp);
            update.setDoneAt(new Date());
        }
        int changed = tableMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGenerationTable>()
                .setSql("submit_count = submit_count + 1")
                // null 也必须明确写回，成功重交要清掉上一轮拒绝原因。
                .set(ConnectorSemanticGenerationTable::getLastRejectReason, reason)
                .eq(ConnectorSemanticGenerationTable::getId, observed.getId())
                .eq(ConnectorSemanticGenerationTable::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticGenerationTable::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticGenerationTable::getGenerationId, principal.generationId())
                .eq(ConnectorSemanticGenerationTable::getObjectName, observed.getObjectName())
                .eq(ConnectorSemanticGenerationTable::getStatus, observed.getStatus())
                .eq(ConnectorSemanticGenerationTable::getSliceNo, principal.sliceNo()));
        if (changed != 1) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
    }

    private static String databaseStatus(ConnectorSemanticGenerationTable batch, String responseStatus,
                                         int nextSubmitCount, int maxSubmits) {
        if (!"REJECTED".equals(responseStatus)) {
            return DONE;
        }
        if (DONE.equals(batch.getStatus())) {
            return DONE;
        }
        return nextSubmitCount >= maxSubmits ? GAVE_UP : batch.getStatus();
    }

    private static String lastReason(String responseStatus, String databaseStatus, String skipReason,
                                     List<EntryOutcomeView> outcomes) {
        if ("SKIPPED".equals(responseStatus)) {
            return clip(TableReasonCode.AGENT_SKIPPED.name() + ": agent 跳过：" + skipReason, LAST_REASON_MAX);
        }
        if (GAVE_UP.equals(databaseStatus)) {
            return TableReasonCode.SUBMIT_LIMIT.name() + ": 已达到本表提交次数上限";
        }
        return outcomes.stream()
                .filter(outcome -> SemanticRowAssembler.STATUS_REJECTED.equals(outcome.getStatus()))
                .findFirst()
                .map(outcome -> clip(outcome.getCode() + ": " + outcome.getMessage(), LAST_REASON_MAX))
                .orElse(null);
    }

    private static SubmitResultView response(String table, String status, int submitCount, int maxSubmits,
                                             WriteCounts counts, List<EntryOutcomeView> outcomes,
                                             ConnectorSemanticGeneration generation,
                                             List<ConnectorSemanticGenerationTable> batchRows,
                                             ConnectorSemanticGenerationTable current, String currentDatabaseStatus,
                                             int sliceNo) {
        SubmitResultView out = new SubmitResultView();
        out.setTable(table);
        out.setStatus(status);
        out.setSubmitCount(submitCount);
        out.setMaxSubmits(maxSubmits);
        SubmitResultView.Written written = new SubmitResultView.Written();
        written.setObject(counts.objects);
        written.setFields(counts.fields);
        written.setJoins(counts.joins);
        written.setAmbiguities(counts.caveats);
        out.setWritten(written);
        out.setEntries(List.copyOf(outcomes));

        int done = 0;
        List<ConnectorSemanticGenerationTable> remaining = new ArrayList<>();
        for (ConnectorSemanticGenerationTable row : batchRows) {
            String rowStatus = Objects.equals(row.getId(), current.getId()) ? currentDatabaseStatus : row.getStatus();
            if (DONE.equals(rowStatus)) {
                done++;
            }
            if (DISPATCHED.equals(rowStatus) && Objects.equals(row.getSliceNo(), sliceNo)) {
                remaining.add(row);
            }
        }
        remaining.sort(BATCH_ORDER);
        SubmitResultView.Progress progress = new SubmitResultView.Progress();
        progress.setDoneTables(done);
        progress.setTotalTables(generation.getTotalTables() == null ? batchRows.size() : generation.getTotalTables());
        out.setProgress(progress);
        out.setSliceRemaining(remaining.stream().map(ConnectorSemanticGenerationTable::getObjectName).toList());
        return out;
    }

    private static int distinctOutcomeCount(List<EntryOutcomeView> outcomes) {
        Set<String> paths = new LinkedHashSet<>();
        for (EntryOutcomeView outcome : outcomes) {
            paths.add(outcome.getPath() + '\u0001' + outcome.getKey());
        }
        return paths.size();
    }

    private static boolean hasRejected(List<EntryOutcomeView> outcomes) {
        return outcomes.stream().anyMatch(
                outcome -> SemanticRowAssembler.STATUS_REJECTED.equals(outcome.getStatus()));
    }

    private int maxSubmits() {
        return Math.max(1, Math.min(10, properties.getSemantic().getAgent().getTableMaxSubmits()));
    }

    private static EntryOutcomeView writeConflict(EntryRef ref) {
        return view(ref, SemanticRowAssembler.STATUS_DROPPED, CODE_WRITE_CONFLICT,
                "写入时遇到并发唯一键冲突，本条未写入，不需要重提");
    }

    private static EntryOutcomeView viewOf(EntryOutcome outcome) {
        return view(outcome.path(), outcome.key(), outcome.status(), outcome.code(), outcome.message());
    }

    private static EntryOutcomeView view(EntryRef ref, String status, String code, String message) {
        return view(ref == null ? "" : ref.path(), ref == null ? "" : ref.key(), status, code, message);
    }

    private static EntryOutcomeView view(String path, String key, String status, String code, String message) {
        EntryOutcomeView out = new EntryOutcomeView();
        out.setPath(path);
        out.setKey(key);
        out.setStatus(status);
        out.setCode(code);
        out.setMessage(message);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static String mergeAppliesTo(String firstJson, String secondJson) {
        Map<String, Object> first = detail(firstJson);
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        for (String table : strings(first.get("applies_to"))) {
            merged.putIfAbsent(ConnectorSemanticService.ciFold(table), table);
            if (merged.size() == SemanticRowAssembler.DETAIL_LIST_MAX) break;
        }
        for (String table : strings(detail(secondJson).get("applies_to"))) {
            merged.putIfAbsent(ConnectorSemanticService.ciFold(table), table);
            if (merged.size() == SemanticRowAssembler.DETAIL_LIST_MAX) break;
        }
        first.put("applies_to", List.copyOf(merged.values()));
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(first);
        } catch (Exception impossible) {
            throw new IllegalStateException("序列化暂存 CAVEAT 失败", impossible);
        }
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object value : list) {
            if (value != null && !String.valueOf(value).isBlank()) out.add(String.valueOf(value).trim());
        }
        return out;
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(ExceptionCode.INVALID_REQUEST, message);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record AssembledSubmission(Map<String, Object> parsed, boolean skipped, String skipReason) {
    }

    private static final class WriteCounts {
        private int objects;
        private int fields;
        private int joins;
        private int caveats;

        private void accept(ConnectorSemantic row) {
            switch (row.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT -> objects++;
                case ConnectorSemanticService.SCOPE_FIELD -> fields++;
                case ConnectorSemanticService.SCOPE_JOIN -> joins++;
                case ConnectorSemanticService.SCOPE_CAVEAT -> caveats++;
                default -> throw new IllegalArgumentException("agent 不支持写入 scope=" + row.getScope());
            }
        }

        private int total() {
            return objects + fields + joins + caveats;
        }
    }
}

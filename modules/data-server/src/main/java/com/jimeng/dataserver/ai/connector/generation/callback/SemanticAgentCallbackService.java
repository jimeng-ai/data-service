package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语义层 agent 的三个只读回调（设计文档 7.5-7.7）。
 *
 * <p>本类只注入平台库 mapper、配置和纯函数，刻意不注入 {@code ConnectorSchemaService} / {@code ConnectorGateway}：
 * 回调发生在模型运行中，每次读都必须只读已落库的结构快照，绝不能顺手 refresh、open 或访问客户库。
 * 所有资源 ID 都来自已经过过滤器核验的 {@link SemanticAgentPrincipal}；请求 DTO 没有资源 ID 字段。
 */
@Service
@RequiredArgsConstructor
public class SemanticAgentCallbackService {

    public static final int METADATA_MAX_TABLES = 5;
    public static final int LIST_MAX_LIMIT = 100;
    public static final int METADATA_TEXT_MAX = 16_000;
    public static final int SUBMIT_MAX_FIELDS = 200;
    public static final int SUBMIT_MAX_JOINS = 50;
    public static final int SUBMIT_MAX_AMBIGUITIES = 20;
    private static final int TERM_MAX = 100;
    private static final int QUERY_MAX = 100;
    private static final int COMMENT_MAX = 60;
    private static final int SNAPSHOT_MAX_OBJECTS = 200;
    private static final String ORDERING = "按重要性（估算行数的数量级降序、被外键引用数降序、表名升序）";

    private static final Comparator<ConnectorSchema> SNAPSHOT_ORDER = Comparator
            .comparing(ConnectorSchema::getImportanceRank, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(ConnectorSchema::getObjectName, Comparator.nullsLast(String::compareTo));
    private static final Comparator<ConnectorSemanticGenerationTable> BATCH_ORDER = Comparator
            .comparing(ConnectorSemanticGenerationTable::getImportanceRank, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(ConnectorSemanticGenerationTable::getObjectName, Comparator.nullsLast(String::compareTo));

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final ConnectorProperties properties;
    private final SemanticConsistencyRuleRegistry ruleRegistry;
    private final SemanticTableRenderer tableRenderer;

    /** 每个端点的第一步都是原子运行确认；确认成功之后才读任何资源。 */
    public RunScopeView runScope(SemanticAgentPrincipal principal) {
        confirmRun(principal);
        ConnectorSemanticGeneration generation = generation(principal);
        Connection connection = connection(principal);
        List<ConnectorSchema> snapshot = snapshot(principal);
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        List<ConnectorSemanticGenerationTable> batch = batch(principal);

        RunScopeView out = new RunScopeView();
        out.setConnectorName(displayName(connection));
        out.setConnectorKind(connection.getKind());
        out.setMode(principal.mode());
        out.setSliceNo(principal.sliceNo());
        out.setSliceCountEstimate(nz(generation.getSliceCount()));

        RunScopeView.Progress progress = new RunScopeView.Progress();
        progress.setDoneTables(nz(generation.getDoneTables()));
        progress.setTotalTables(nz(generation.getTotalTables()));
        progress.setGaveUpTables(nz(generation.getGaveUpTables()));
        out.setProgress(progress);

        List<ConnectorSemanticGenerationTable> currentSlice = batch.stream()
                .filter(t -> Objects.equals(t.getSliceNo(), principal.sliceNo()))
                .sorted(BATCH_ORDER)
                .toList();
        List<RunScopeView.TableItem> tableItems = new ArrayList<>(currentSlice.size());
        for (ConnectorSemanticGenerationTable t : currentSlice) {
            Map<String, FieldDetail> cols = fields.getOrDefault(t.getObjectName(), Map.of());
            RunScopeView.TableItem item = new RunScopeView.TableItem();
            item.setName(t.getObjectName());
            item.setImportanceRank(t.getImportanceRank());
            item.setStatus(t.getStatus());
            item.setSubmitCount(nz(t.getSubmitCount()));
            item.setLastRejectReason(t.getLastRejectReason());
            item.setColumnCount(cols.size());
            item.setDescribed(!cols.isEmpty());
            tableItems.add(item);
        }
        out.setTables(tableItems);

        List<ConnectorSemantic> mainSemantics = mainTerms(principal);
        out.setAnsweredTerms(answeredTerms(mainSemantics));
        out.setOpenTerms("STAGED".equals(principal.mode())
                ? stagedOpenTerms(principal)
                : directOpenTerms(mainSemantics));
        out.setLimits(limits());
        out.setRules(ruleRegistry.summaries().stream().map(summary -> {
            RunScopeView.Rule rule = new RunScopeView.Rule();
            rule.setCode(summary.getCode());
            rule.setSummary(summary.getSummary());
            return rule;
        }).toList());
        out.setNotes(notes(snapshot));
        return out;
    }

    /** 表目录只读结构快照；筛选和分页都在内存做（子项目 1 的快照硬上限 200）。 */
    public TableListView listTables(SemanticAgentPrincipal principal, TableListRequest request) {
        confirmRun(principal);
        ListArgs args = validateList(request);
        List<ConnectorSchema> snapshot = snapshot(principal);
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        List<ConnectorSemanticGenerationTable> batch = batch(principal);
        Map<String, ConnectorSemanticGenerationTable> batchByName = byObject(batch);

        String foldedQuery = args.query() == null ? null : args.query().toLowerCase(Locale.ROOT);
        List<ConnectorSchema> filtered = snapshot.stream()
                .filter(row -> "all".equals(args.scope()) || inCurrentSlice(batchByName.get(row.getObjectName()), principal))
                .filter(row -> foldedQuery == null || containsFolded(row.getObjectName(), foldedQuery)
                        || containsFolded(row.getObjectComment(), foldedQuery))
                .sorted(SNAPSHOT_ORDER)
                .toList();

        int from = Math.min(args.offset(), filtered.size());
        int to = Math.min(from + args.limit(), filtered.size());
        List<TableListView.Item> items = new ArrayList<>(to - from);
        for (ConnectorSchema row : filtered.subList(from, to)) {
            ConnectorSemanticGenerationTable bt = batchByName.get(row.getObjectName());
            Map<String, FieldDetail> cols = fields.getOrDefault(row.getObjectName(), Map.of());
            TableListView.Item item = new TableListView.Item();
            item.setName(row.getObjectName());
            item.setType(row.getObjectType());
            item.setComment(SemanticRowAssembler.clip(row.getObjectComment(), COMMENT_MAX));
            item.setColumnCount(cols.size());
            item.setDescribed(!cols.isEmpty());
            item.setImportanceRank(row.getImportanceRank());
            item.setInCurrentSlice(inCurrentSlice(bt, principal));
            item.setBatchStatus(bt == null ? null : bt.getStatus());
            items.add(item);
        }

        TableListView out = new TableListView();
        out.setTotal(filtered.size());
        out.setOffset(args.offset());
        out.setLimit(args.limit());
        out.setNextOffset(to < filtered.size() ? to : null);
        out.setOrdering(ORDERING);
        out.setItems(items);
        return out;
    }

    /** 按请求顺序返回最多 5 张表；精确匹配大小写敏感，第一张超宽表按列前缀截到 16k。 */
    public TableMetadataView tableMetadata(SemanticAgentPrincipal principal, TableMetadataRequest request) {
        confirmRun(principal);
        List<String> requested = validateMetadata(request);
        List<ConnectorSchema> snapshot = snapshot(principal);
        Map<String, ConnectorSchema> snapshotByName = new LinkedHashMap<>();
        for (ConnectorSchema row : snapshot) {
            snapshotByName.putIfAbsent(row.getObjectName(), row);
        }
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(snapshot);
        Map<String, ConnectorSemanticGenerationTable> batchByName = byObject(batch(principal));

        LinkedHashSet<String> distinctRequested = new LinkedHashSet<>(requested);
        List<String> foundNames = distinctRequested.stream().filter(snapshotByName::containsKey).toList();
        Map<String, List<TableMetadataView.HumanKey>> humanKeys = humanKeys(principal, foundNames);

        TableMetadataView out = new TableMetadataView();
        int textChars = 0;
        for (String name : distinctRequested) {
            ConnectorSchema row = snapshotByName.get(name);
            if (row == null) {
                TableMetadataView.NotFound missing = new TableMetadataView.NotFound();
                missing.setName(name);
                missing.setSuggestions(suggestions(name, snapshot));
                out.getNotFound().add(missing);
                continue;
            }

            SemanticTableRenderer.RenderedTable rendered;
            if (out.getTables().isEmpty()) {
                rendered = tableRenderer.renderWithin(row, METADATA_TEXT_MAX);
            } else {
                String full = tableRenderer.render(row);
                if (textChars + full.length() > METADATA_TEXT_MAX) {
                    out.getDeferred().add(name);
                    continue;
                }
                rendered = new SemanticTableRenderer.RenderedTable(full, false,
                        fields.getOrDefault(name, Map.of()).size(), fields.getOrDefault(name, Map.of()).size());
            }

            ConnectorSemanticGenerationTable bt = batchByName.get(name);
            Map<String, FieldDetail> cols = fields.getOrDefault(name, Map.of());
            TableMetadataView.TableItem item = new TableMetadataView.TableItem();
            item.setName(name);
            item.setType(row.getObjectType());
            item.setDescribed(!cols.isEmpty());
            item.setInCurrentSlice(inCurrentSlice(bt, principal));
            item.setBatchStatus(bt == null ? null : bt.getStatus());
            item.setColumnCount(cols.size());
            item.setStructureStamp(ConnectorSemanticService.tableStamp(name, cols));
            item.setUniqueKeysKnown(SemanticTableRenderer.uniqueKeysKnown(row));
            item.setHumanKeys(humanKeys.getOrDefault(name, List.of()));
            item.setColumnsTruncated(rendered.columnsTruncated());
            item.setText(rendered.text());
            out.getTables().add(item);
            textChars += rendered.text().length();
        }
        return out;
    }

    /**
     * 原子运行确认同时给 NO_CALLBACK 计数。刻意不把三个读方法包在同一事务里：请求后续即使因参数错误抛异常，
     * 这个已经到达服务端的回调也必须留下计数；否则模型连续打来非法请求会被编排器误判成 NO_CALLBACK。
     */
    private void confirmRun(SemanticAgentPrincipal principal) {
        if (principal == null) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
        int changed = generationMapper.update(null, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .setSql("current_run_callbacks = current_run_callbacks + 1")
                .eq(ConnectorSemanticGeneration::getId, principal.generationId())
                .eq(ConnectorSemanticGeneration::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticGeneration::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticGeneration::getStatus, "RUNNING")
                .eq(ConnectorSemanticGeneration::getCurrentRunId, principal.runId()));
        if (changed != 1) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
    }

    private ConnectorSemanticGeneration generation(SemanticAgentPrincipal principal) {
        ConnectorSemanticGeneration row = generationMapper.selectOne(new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getId, principal.generationId())
                .eq(ConnectorSemanticGeneration::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticGeneration::getConnectorId, principal.connectorId()));
        if (row == null) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
        return row;
    }

    private Connection connection(SemanticAgentPrincipal principal) {
        Connection row = connectionMapper.selectOne(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getId, principal.connectorId())
                .eq(Connection::getTenantId, principal.tenantId()));
        if (row == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return row;
    }

    private List<ConnectorSchema> snapshot(SemanticAgentPrincipal principal) {
        List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getTenantId, principal.tenantId())
                .eq(ConnectorSchema::getConnectorId, principal.connectorId())
                .last("ORDER BY importance_rank IS NULL, importance_rank ASC, id ASC"));
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

    /** 一次读主表即可同时得到「已答口径」和 DIRECT 的开放问题；只挑必要列，不读回答人/说明/detail。 */
    private List<ConnectorSemantic> mainTerms(SemanticAgentPrincipal principal) {
        List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .select(ConnectorSemantic::getScope, ConnectorSemantic::getTerm,
                        ConnectorSemantic::getSource, ConnectorSemantic::getStatus)
                .eq(ConnectorSemantic::getTenantId, principal.tenantId())
                .eq(ConnectorSemantic::getConnectorId, principal.connectorId())
                .in(ConnectorSemantic::getScope,
                        List.of(ConnectorSemanticService.SCOPE_METRIC, ConnectorSemanticService.SCOPE_CAVEAT))
                .orderByAsc(ConnectorSemantic::getId));
        return rows == null ? List.of() : rows;
    }

    private List<String> answeredTerms(List<ConnectorSemantic> rows) {
        Set<String> answered = SemanticRowAssembler.answeredTerms(rows);
        LinkedHashMap<String, String> terms = new LinkedHashMap<>();
        for (ConnectorSemantic row : rows) {
            String folded = SemanticRowAssembler.fold(row.getTerm());
            if (answered.contains(folded) && !folded.isEmpty()) {
                terms.putIfAbsent(folded, row.getTerm());
                if (terms.size() == TERM_MAX) break;
            }
        }
        return List.copyOf(terms.values());
    }

    private List<String> directOpenTerms(List<ConnectorSemantic> rows) {
        return distinctTerms(rows.stream()
                .filter(r -> ConnectorSemanticService.SCOPE_CAVEAT.equals(r.getScope()))
                .filter(r -> ConnectorSemanticService.SOURCE_INFERRED.equals(r.getSource()))
                .map(ConnectorSemantic::getTerm).toList());
    }

    private List<String> stagedOpenTerms(SemanticAgentPrincipal principal) {
        List<ConnectorSemanticStaged> rows = stagedMapper.selectList(new LambdaQueryWrapper<ConnectorSemanticStaged>()
                .select(ConnectorSemanticStaged::getTerm)
                .eq(ConnectorSemanticStaged::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticStaged::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticStaged::getGenerationId, principal.generationId())
                .eq(ConnectorSemanticStaged::getScope, ConnectorSemanticService.SCOPE_CAVEAT)
                .orderByAsc(ConnectorSemanticStaged::getId));
        return distinctTerms((rows == null ? List.<ConnectorSemanticStaged>of() : rows).stream()
                .map(ConnectorSemanticStaged::getTerm).toList());
    }

    private static List<String> distinctTerms(List<String> source) {
        LinkedHashMap<String, String> terms = new LinkedHashMap<>();
        for (String term : source) {
            String folded = SemanticRowAssembler.fold(term);
            if (folded.isEmpty()) continue;
            terms.putIfAbsent(folded, term);
            if (terms.size() == TERM_MAX) break;
        }
        return List.copyOf(terms.values());
    }

    private RunScopeView.Limits limits() {
        RunScopeView.Limits limits = new RunScopeView.Limits();
        limits.setMetadataMaxTables(METADATA_MAX_TABLES);
        limits.setListMaxLimit(LIST_MAX_LIMIT);
        limits.setSubmitMaxFields(SUBMIT_MAX_FIELDS);
        limits.setSubmitMaxJoins(SUBMIT_MAX_JOINS);
        limits.setSubmitMaxAmbiguities(SUBMIT_MAX_AMBIGUITIES);
        int configured = properties.getSemantic().getAgent().getTableMaxSubmits();
        limits.setMaxSubmitsPerTable(Math.max(1, Math.min(10, configured)));
        return limits;
    }

    private static List<String> notes(List<ConnectorSchema> snapshot) {
        List<String> notes = new ArrayList<>();
        if (snapshot.size() >= SNAPSHOT_MAX_OBJECTS) {
            notes.add("快照只描述了按重要性排前 200 张表");
        }
        if (snapshot.stream().anyMatch(r -> r.getImportanceRank() == null)) {
            notes.add("快照缺少重要性排名，刷新结构后生效");
        }
        return notes;
    }

    private static ListArgs validateList(TableListRequest request) {
        String query = request == null ? null : request.getQuery();
        if (query != null && query.length() > QUERY_MAX) {
            throw invalid("query 最长 100 字符");
        }
        query = query == null || query.trim().isEmpty() ? null : query.trim();
        String scope = request == null || request.getScope() == null ? "all" : request.getScope();
        int offset = request == null || request.getOffset() == null ? 0 : request.getOffset();
        int limit = request == null || request.getLimit() == null ? 50 : request.getLimit();
        if (!("all".equals(scope) || "slice".equals(scope))) {
            throw invalid("scope 只能是 all 或 slice");
        }
        if (offset < 0) {
            throw invalid("offset 不能小于 0");
        }
        if (limit < 1 || limit > LIST_MAX_LIMIT) {
            throw invalid("limit 必须在 1 到 100 之间");
        }
        return new ListArgs(query, scope, offset, limit);
    }

    private static List<String> validateMetadata(TableMetadataRequest request) {
        List<String> tables = request == null ? null : request.getTables();
        if (tables == null || tables.isEmpty() || tables.size() > METADATA_MAX_TABLES) {
            throw invalid("tables 一次必须包含 1 到 5 张表");
        }
        for (String table : tables) {
            if (table == null || table.isEmpty() || table.length() > SemanticRowAssembler.NAME_MAX) {
                throw invalid("表名长度必须在 1 到 191 字符之间");
            }
        }
        return tables;
    }

    private Map<String, List<TableMetadataView.HumanKey>> humanKeys(SemanticAgentPrincipal principal,
                                                                     List<String> foundNames) {
        if (foundNames.isEmpty()) {
            return Map.of();
        }
        List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .select(ConnectorSemantic::getObjectName, ConnectorSemantic::getScope,
                        ConnectorSemantic::getFieldName, ConnectorSemantic::getSource)
                .eq(ConnectorSemantic::getTenantId, principal.tenantId())
                .eq(ConnectorSemantic::getConnectorId, principal.connectorId())
                .in(ConnectorSemantic::getObjectName, foundNames)
                .ne(ConnectorSemantic::getSource, ConnectorSemanticService.SOURCE_INFERRED)
                .orderByAsc(ConnectorSemantic::getId));
        Map<String, List<TableMetadataView.HumanKey>> out = new LinkedHashMap<>();
        for (ConnectorSemantic row : rows == null ? List.<ConnectorSemantic>of() : rows) {
            if (ConnectorSemanticService.SOURCE_INFERRED.equals(row.getSource())
                    || !foundNames.contains(row.getObjectName())) {
                continue;
            }
            TableMetadataView.HumanKey key = new TableMetadataView.HumanKey();
            key.setScope(row.getScope());
            key.setField(blankToNull(row.getFieldName()));
            out.computeIfAbsent(row.getObjectName(), ignored -> new ArrayList<>()).add(key);
        }
        return out;
    }

    private static List<String> suggestions(String requested, List<ConnectorSchema> snapshot) {
        String folded = ConnectorSemanticService.ciFold(requested);
        return snapshot.stream()
                .map(ConnectorSchema::getObjectName)
                .filter(Objects::nonNull)
                .map(name -> {
                    String candidate = ConnectorSemanticService.ciFold(name);
                    return new Suggestion(name, candidate.equals(folded), levenshtein(folded, candidate));
                })
                .filter(s -> s.caseInsensitiveEqual() || s.distance() <= 2)
                .sorted(Comparator.comparing(Suggestion::caseInsensitiveEqual).reversed()
                        .thenComparingInt(Suggestion::distance)
                        .thenComparing(Suggestion::name))
                .limit(3)
                .map(Suggestion::name)
                .toList();
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private static Map<String, ConnectorSemanticGenerationTable> byObject(
            List<ConnectorSemanticGenerationTable> rows) {
        Map<String, ConnectorSemanticGenerationTable> out = new LinkedHashMap<>();
        for (ConnectorSemanticGenerationTable row : rows) {
            if (row.getObjectName() != null) out.put(row.getObjectName(), row);
        }
        return out;
    }

    private static boolean inCurrentSlice(ConnectorSemanticGenerationTable row, SemanticAgentPrincipal principal) {
        return row != null && Objects.equals(row.getSliceNo(), principal.sliceNo());
    }

    private static boolean containsFolded(String value, String foldedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(foldedQuery);
    }

    private static String displayName(Connection row) {
        return row.getDisplayName() == null || row.getDisplayName().isBlank() ? row.getName() : row.getDisplayName();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(ExceptionCode.INVALID_REQUEST, message);
    }

    private record ListArgs(String query, String scope, int offset, int limit) {
    }

    private record Suggestion(String name, boolean caseInsensitiveEqual, int distance) {
    }
}

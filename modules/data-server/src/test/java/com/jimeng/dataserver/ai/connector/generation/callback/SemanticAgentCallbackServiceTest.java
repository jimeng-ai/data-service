package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRule;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry;
import com.jimeng.dataserver.ai.connector.generation.consistency.ProposedEntry;
import com.jimeng.dataserver.ai.connector.generation.consistency.RuleContext;
import com.jimeng.dataserver.ai.connector.generation.consistency.RuleViolation;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设计文档 7.2、7.5-7.7：回调的三个只读端点。 */
class SemanticAgentCallbackServiceTest {

    private static final long GEN_ID = 9101L;
    private static final long CONNECTOR_ID = 9201L;
    private static final SemanticAgentPrincipal PRINCIPAL =
            new SemanticAgentPrincipal("tenant-a", "42", GEN_ID, CONNECTOR_ID, 3, "run-3", "DIRECT");

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticStagedMapper stagedMapper;
    private ConnectorProperties properties;
    private SemanticAgentCallbackService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticStaged.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGeneration.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        properties = new ConnectorProperties();
        SemanticConsistencyRule rule = new SemanticConsistencyRule() {
            @Override public String code() { return "R_ONE"; }
            @Override public Set<String> kinds() { return Set.of("fields"); }
            @Override public String summary() { return "字段说明必须有依据"; }
            @Override public List<RuleViolation> check(ProposedEntry entry, RuleContext context) { return List.of(); }
        };
        service = new SemanticAgentCallbackService(generationMapper, tableMapper, connectionMapper, schemaMapper,
                semanticMapper, stagedMapper, properties, new SemanticConsistencyRuleRegistry(List.of(rule)),
                new SemanticTableRenderer());

        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectOne(any())).thenReturn(generation("DIRECT"));
        when(connectionMapper.selectOne(any())).thenReturn(connection());
        when(tableMapper.selectList(any())).thenReturn(List.of());
        when(schemaMapper.selectList(any())).thenReturn(List.of());
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        when(stagedMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("run_scope 只列本片表，规则/进度/口径只来自平台快照且不返回回答人")
    void run_scope只列本片表() {
        ConnectorSchema current = schema("t_ord", "订单", 2, fields("id", "bigint", false, "主键", "自增"), keys());
        ConnectorSchema other = schema("t_other", "其它", 1, fields("id", "bigint", false, "", ""), keys());
        when(schemaMapper.selectList(any())).thenReturn(List.of(other, current));
        when(tableMapper.selectList(any())).thenReturn(List.of(
                batch("t_ord", 3, 2, "DISPATCHED"),
                batch("t_other", 2, 1, "DONE")));

        ConnectorSemantic answered = semantic(ConnectorSemanticService.SCOPE_METRIC, "", "", "销售额",
                ConnectorSemanticService.SOURCE_HUMAN, "不得出网的回答", "{\"answered_by_name\":\"张三\"}");
        answered.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        answered.setAnsweredName("张三");
        ConnectorSemantic open = semantic(ConnectorSemanticService.SCOPE_CAVEAT, "", "", "活跃用户",
                ConnectorSemanticService.SOURCE_INFERRED, "不得出网的问题", "{\"secret\":\"x\"}");
        when(semanticMapper.selectList(any())).thenReturn(List.of(answered, open));

        RunScopeView view = service.runScope(PRINCIPAL);

        assertEquals("订单库", view.getConnectorName());
        assertEquals("MYSQL", view.getConnectorKind());
        assertEquals("DIRECT", view.getMode());
        assertEquals(3, view.getSliceNo());
        assertEquals(12, view.getSliceCountEstimate());
        assertEquals(40, view.getProgress().getDoneTables());
        assertEquals(List.of("t_ord"), view.getTables().stream().map(RunScopeView.TableItem::getName).toList());
        assertEquals(1, view.getTables().get(0).getColumnCount());
        assertTrue(view.getTables().get(0).isDescribed());
        assertEquals(List.of("销售额"), view.getAnsweredTerms());
        assertEquals(List.of("活跃用户"), view.getOpenTerms());
        assertEquals(List.of("R_ONE"), view.getRules().stream().map(RunScopeView.Rule::getCode).toList());
        assertEquals(5, view.getLimits().getMetadataMaxTables());
        assertEquals(3, view.getLimits().getMaxSubmitsPerTable());
        assertFalse(view.toString().contains("张三"));
        assertFalse(view.toString().contains("不得出网"));
        verify(generationMapper).update(any(), any());
    }

    @Test
    @DisplayName("run_scope 即使库里有同片脏状态，也只返回 DISPATCHED、DONE、GAVE_UP")
    void run_scope只返回协议允许的表状态() {
        List<ConnectorSchema> schemas = List.of(
                schema("dispatched", "", 1, fields("id", "int", false, "", ""), keys()),
                schema("done", "", 2, fields("id", "int", false, "", ""), keys()),
                schema("gave_up", "", 3, fields("id", "int", false, "", ""), keys()),
                schema("pending", "", 4, fields("id", "int", false, "", ""), keys()),
                schema("skipped", "", 5, fields("id", "int", false, "", ""), keys()),
                schema("removed", "", 6, fields("id", "int", false, "", ""), keys()));
        when(schemaMapper.selectList(any())).thenReturn(schemas);
        when(tableMapper.selectList(any())).thenReturn(List.of(
                batch("dispatched", 3, 1, "DISPATCHED"),
                batch("done", 3, 2, "DONE"),
                batch("gave_up", 3, 3, "GAVE_UP"),
                batch("pending", 3, 4, "PENDING"),
                batch("skipped", 3, 5, "SKIPPED"),
                batch("removed", 3, 6, "REMOVED")));

        RunScopeView view = service.runScope(PRINCIPAL);

        assertEquals(List.of("dispatched", "done", "gave_up"),
                view.getTables().stream().map(RunScopeView.TableItem::getName).toList());
    }

    @Test
    @DisplayName("STAGED 的 openTerms 只读本批暂存行并各自截到 100 条")
    void staged_openTerms读本批暂存行且截断() {
        SemanticAgentPrincipal stagedPrincipal = new SemanticAgentPrincipal(
                "tenant-a", "42", GEN_ID, CONNECTOR_ID, 3, "run-3", "STAGED");
        when(generationMapper.selectOne(any())).thenReturn(generation("STAGED"));
        List<ConnectorSemanticStaged> rows = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            ConnectorSemanticStaged r = new ConnectorSemanticStaged();
            r.setScope(ConnectorSemanticService.SCOPE_CAVEAT);
            r.setTerm("问题" + i);
            r.setGloss("不得出网" + i);
            rows.add(r);
        }
        when(stagedMapper.selectList(any())).thenReturn(rows);

        RunScopeView view = service.runScope(stagedPrincipal);

        assertEquals(100, view.getOpenTerms().size());
        assertFalse(view.toString().contains("不得出网"));
        verify(stagedMapper).selectList(any());
    }

    @Test
    @DisplayName("运行确认影响 0 行抛 4090，且不再读取任何资源")
    void 运行确认0行抛4090() {
        when(generationMapper.update(any(), any())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.runScope(PRINCIPAL));

        assertEquals(ExceptionCode.SEMANTIC_GENERATION_CLOSED.getResultCode(), ex.getRespCode());
        verify(connectionMapper, never()).selectOne(any());
        verify(schemaMapper, never()).selectList(any());
        verify(tableMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("运行确认是单条原子自增，并同时绑定可信主体的 tenant/gen/cid/RUNNING/runId")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void 运行确认条件完整() {
        service.listTables(PRINCIPAL, null);

        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(generationMapper).update(any(), captor.capture());
        LambdaUpdateWrapper<ConnectorSemanticGeneration> wrapper = captor.getValue();
        assertEquals("current_run_callbacks = current_run_callbacks + 1", wrapper.getSqlSet());
        String where = wrapper.getSqlSegment();
        assertTrue(where.contains("tenant_id"), where);
        assertTrue(where.contains("connector_id"), where);
        assertTrue(where.contains("status"), where);
        assertTrue(where.contains("current_run_id"), where);
        assertTrue(where.contains("id"), where);
    }

    @Nested
    @DisplayName("tables")
    class Tables {

        @Test
        @DisplayName("query/scope 后分页，NULL 排名最后，同档按表名，注释最多 60 字且带 batchStatus")
        void tables分页排序与投影() {
            String longComment = "注".repeat(80);
            ConnectorSchema b = schema("t_b", longComment, 2, fields("id", "bigint", false, "", ""), keys());
            ConnectorSchema a = schema("t_a", "A", 2, fields("id", "bigint", false, "", ""), keys());
            ConnectorSchema z = schema("t_z", "Z", null, "[]", keys());
            ConnectorSchema noMatch = schema("x_table", "X", 1, fields("id", "bigint", false, "", ""), keys());
            when(schemaMapper.selectList(any())).thenReturn(List.of(z, b, noMatch, a));
            when(tableMapper.selectList(any())).thenReturn(List.of(
                    batch("t_a", 3, 2, "DISPATCHED"), batch("t_b", 1, 2, "DONE")));

            TableListRequest request = new TableListRequest();
            request.setQuery("T_");
            request.setScope("all");
            request.setOffset(1);
            request.setLimit(2);
            TableListView view = service.listTables(PRINCIPAL, request);

            assertEquals(3, view.getTotal());
            assertEquals(List.of("t_b", "t_z"), view.getItems().stream().map(TableListView.Item::getName).toList());
            assertEquals(60, view.getItems().get(0).getComment().length());
            assertEquals("DONE", view.getItems().get(0).getBatchStatus());
            assertFalse(view.getItems().get(1).isDescribed());
            assertNull(view.getNextOffset());
        }

        @Test
        @DisplayName("scope=slice 只列当前片，默认 limit=50；越界参数在运行确认后回 5007")
        void scope与limit边界() {
            when(schemaMapper.selectList(any())).thenReturn(List.of(
                    schema("t_current", "", 1, fields("id", "int", false, "", ""), keys()),
                    schema("t_old", "", 2, fields("id", "int", false, "", ""), keys())));
            when(tableMapper.selectList(any())).thenReturn(List.of(
                    batch("t_current", 3, 1, "DISPATCHED"), batch("t_old", 2, 2, "DONE")));
            TableListRequest slice = new TableListRequest();
            slice.setScope("slice");
            TableListView view = service.listTables(PRINCIPAL, slice);
            assertEquals(1, view.getTotal());
            assertEquals(50, view.getLimit());
            assertEquals("t_current", view.getItems().get(0).getName());

            for (TableListRequest invalid : List.of(listRequest(null, "all", 0, 101),
                    listRequest("q".repeat(101), "all", 0, 50), listRequest(null, "other", 0, 50),
                    listRequest(null, "", 0, 50), listRequest(null, "all", -1, 50),
                    listRequest(null, "all", 0, 0))) {
                ServiceException ex = assertThrows(ServiceException.class,
                        () -> service.listTables(PRINCIPAL, invalid));
                assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), ex.getRespCode());
            }
            verify(generationMapper, times(7)).update(any(), any());
        }
    }

    @Nested
    @DisplayName("table-metadata")
    class Metadata {

        @Test
        @DisplayName("一次 1..5 张、名字 1..191；范围错误在运行确认之后回 5007")
        void 数量与名字边界() {
            List<List<String>> invalidNames = List.of(List.of(), List.of("a", "b", "c", "d", "e", "f"),
                    List.of(""), List.of("x".repeat(192)));
            for (List<String> names : invalidNames) {
                TableMetadataRequest request = new TableMetadataRequest();
                request.setTables(names);
                ServiceException ex = assertThrows(ServiceException.class,
                        () -> service.tableMetadata(PRINCIPAL, request));
                assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), ex.getRespCode());
            }
            verify(generationMapper, times(4)).update(any(), any());
            verify(schemaMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("文本总长不超过 16000：第一张超宽表截列并标注，后续放不下的整张进 deferred")
        void 文本总长不超过16000_放不下进deferred_超宽表截列() {
            ConnectorSchema wide = schema("wide", "宽表", 1, manyFields(500, 80), keys());
            ConnectorSchema later = schema("later", "后表", 2, manyFields(50, 80), keys());
            when(schemaMapper.selectList(any())).thenReturn(List.of(wide, later));
            when(tableMapper.selectList(any())).thenReturn(List.of(batch("wide", 3, 1, "DISPATCHED")));

            TableMetadataRequest request = metadataRequest("wide", "later");
            TableMetadataView view = service.tableMetadata(PRINCIPAL, request);

            assertEquals(1, view.getTables().size());
            TableMetadataView.TableItem first = view.getTables().get(0);
            assertEquals("wide", first.getName());
            assertTrue(first.isColumnsTruncated());
            assertTrue(first.getText().contains("未列出的列不要写说明"));
            assertTrue(first.getText().length() <= 16_000);
            assertEquals(List.of("later"), view.getDeferred());
            assertTrue(view.getTables().stream().mapToInt(t -> t.getText().length()).sum() <= 16_000);
        }

        @Test
        @DisplayName("唯一键未知/空/有键三态分开，主键在前，structureStamp 为 64 位")
        void 唯一键三态文案() {
            ConnectorSchema unknown = schema("unknown", "", 1, fields("id", "int", false, "", ""), null);
            ConnectorSchema none = schema("none", "", 2, fields("id", "int", false, "", ""), "[]");
            String withKeys = "[{\"name\":\"uk_code\",\"primary\":false,\"columns\":[\"code\"]},"
                    + "{\"name\":\"PRIMARY\",\"primary\":true,\"columns\":[\"id\"]}]";
            ConnectorSchema some = schema("some", "", 3,
                    fields("id", "int", false, "", "", "code", "varchar(20)", true, "", ""), withKeys);
            when(schemaMapper.selectList(any())).thenReturn(List.of(unknown, none, some));

            TableMetadataView view = service.tableMetadata(PRINCIPAL, metadataRequest("unknown", "none", "some"));

            assertFalse(view.getTables().get(0).isUniqueKeysKnown());
            assertTrue(view.getTables().get(0).getText().contains(SemanticTableRenderer.UNIQUE_KEYS_UNKNOWN));
            assertTrue(view.getTables().get(1).isUniqueKeysKnown());
            assertTrue(view.getTables().get(1).getText().contains(SemanticTableRenderer.UNIQUE_KEYS_NONE));
            assertTrue(view.getTables().get(2).isUniqueKeysKnown());
            assertTrue(view.getTables().get(2).getText().contains("PRIMARY(id)；uk_code(code)"));
            assertEquals(64, view.getTables().get(2).getStructureStamp().length());
        }

        @Test
        @DisplayName("只返回非 INFERRED 行的键，不返回任何已有语义内容")
        void 不返回INFERRED行内容_只给人工键() {
            ConnectorSchema table = schema("t_ord", "订单", 1, fields("amt", "decimal", false, "金额", ""), keys());
            when(schemaMapper.selectList(any())).thenReturn(List.of(table));
            ConnectorSemantic human = semantic("FIELD", "t_ord", "amt", "", "HUMAN",
                    "人工机密说明", "{\"value_domain\":[\"机密值\"]}");
            when(semanticMapper.selectList(any())).thenReturn(List.of(human));

            TableMetadataView view = service.tableMetadata(PRINCIPAL, metadataRequest("t_ord"));

            assertEquals(1, view.getTables().get(0).getHumanKeys().size());
            assertEquals("FIELD", view.getTables().get(0).getHumanKeys().get(0).getScope());
            assertEquals("amt", view.getTables().get(0).getHumanKeys().get(0).getField());
            assertFalse(view.toString().contains("人工机密说明"));
            assertFalse(view.toString().contains("机密值"));
        }

        @Test
        @DisplayName("humanKeys 只暴露 OBJECT、FIELD、JOIN 的键，不把 METRIC/CAVEAT 或脏 scope 当表内键")
        void humanKeys只返回表内语义键() {
            ConnectorSchema table = schema("t_ord", "订单", 1, fields("amt", "decimal", false, "金额", ""), keys());
            when(schemaMapper.selectList(any())).thenReturn(List.of(table));
            ConnectorSemantic field = semantic(ConnectorSemanticService.SCOPE_FIELD, "t_ord", "amt", "", "HUMAN",
                    "字段说明", null);
            ConnectorSemantic metric = semantic(ConnectorSemanticService.SCOPE_METRIC, "t_ord", "", "销售额", "HUMAN",
                    "口径答案", null);
            ConnectorSemantic caveat = semantic(ConnectorSemanticService.SCOPE_CAVEAT, "t_ord", "", "销售额", "IMPORTED",
                    "口径问题", null);
            ConnectorSemantic dirty = semantic("UNKNOWN", "t_ord", "x", "", "HUMAN", "脏数据", null);
            when(semanticMapper.selectList(any())).thenReturn(List.of(field, metric, caveat, dirty));

            TableMetadataView view = service.tableMetadata(PRINCIPAL, metadataRequest("t_ord"));

            assertEquals(1, view.getTables().get(0).getHumanKeys().size());
            assertEquals(ConnectorSemanticService.SCOPE_FIELD,
                    view.getTables().get(0).getHumanKeys().get(0).getScope());
        }

        @Test
        @DisplayName("名字大小写不符或编辑距离不超过 2 时给最多 3 个建议，精确匹配严格区分大小写")
        void 名字大小写不符给出建议() {
            when(schemaMapper.selectList(any())).thenReturn(List.of(
                    schema("t_cust", "", 1, fields("id", "int", false, "", ""), keys()),
                    schema("t_cast", "", 2, fields("id", "int", false, "", ""), keys()),
                    schema("t_cost", "", 3, fields("id", "int", false, "", ""), keys()),
                    schema("t_cut", "", 4, fields("id", "int", false, "", ""), keys())));

            TableMetadataView view = service.tableMetadata(PRINCIPAL, metadataRequest("T_CUST"));

            assertTrue(view.getTables().isEmpty());
            assertEquals(1, view.getNotFound().size());
            assertEquals("T_CUST", view.getNotFound().get(0).getName());
            assertEquals("t_cust", view.getNotFound().get(0).getSuggestions().get(0));
            assertTrue(view.getNotFound().get(0).getSuggestions().size() <= 3);
        }
    }

    @Test
    @DisplayName("服务只注入平台库 mapper/配置/纯函数，不注入 ConnectorGateway 或客户库 session")
    void 无客户库调用依赖() {
        Set<Class<?>> fieldTypes = Set.of(SemanticAgentCallbackService.class.getDeclaredFields()).stream()
                .map(java.lang.reflect.Field::getType).collect(java.util.stream.Collectors.toSet());
        assertFalse(fieldTypes.stream().anyMatch(t -> t.getName().contains("ConnectorGateway")
                || t.getName().contains("ConnectorSession") || t.getName().contains("ConnectorSchemaService")));
    }

    private static ConnectorSemanticGeneration generation(String mode) {
        ConnectorSemanticGeneration g = new ConnectorSemanticGeneration();
        g.setId(GEN_ID);
        g.setTenantId("tenant-a");
        g.setConnectorId(CONNECTOR_ID);
        g.setMode(mode);
        g.setStatus("RUNNING");
        g.setCurrentRunId("run-3");
        g.setCurrentSliceNo(3);
        g.setSliceCount(12);
        g.setDoneTables(40);
        g.setTotalTables(180);
        g.setGaveUpTables(0);
        return g;
    }

    private static Connection connection() {
        Connection c = new Connection();
        c.setId(CONNECTOR_ID);
        c.setTenantId("tenant-a");
        c.setName("orders");
        c.setDisplayName("订单库");
        c.setKind("MYSQL");
        return c;
    }

    private static ConnectorSemanticGenerationTable batch(String name, int slice, Integer rank, String status) {
        ConnectorSemanticGenerationTable t = new ConnectorSemanticGenerationTable();
        t.setTenantId("tenant-a");
        t.setGenerationId(GEN_ID);
        t.setConnectorId(CONNECTOR_ID);
        t.setObjectName(name);
        t.setSliceNo(slice);
        t.setImportanceRank(rank);
        t.setStatus(status);
        t.setSubmitCount(0);
        return t;
    }

    private static ConnectorSchema schema(String name, String comment, Integer rank, String fieldsJson, String keysJson) {
        ConnectorSchema s = new ConnectorSchema();
        s.setTenantId("tenant-a");
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType("TABLE");
        s.setObjectComment(comment);
        s.setImportanceRank(rank);
        String extra = keysJson == null ? "{}" : "{\"unique_keys\":" + keysJson + "}";
        s.setDetailJson("{\"fields\":" + fieldsJson + ",\"extra\":" + extra + "}");
        return s;
    }

    private static String fields(Object... values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.length; i += 5) {
            if (i > 0) b.append(',');
            b.append("{\"name\":\"").append(values[i]).append("\",\"type\":\"").append(values[i + 1])
                    .append("\",\"nullable\":").append(values[i + 2]).append(",\"comment\":\"")
                    .append(values[i + 3]).append("\",\"extra\":\"").append(values[i + 4]).append("\"}");
        }
        return b.append(']').toString();
    }

    private static String manyFields(int count, int commentChars) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append(',');
            b.append("{\"name\":\"column_").append(i).append("\",\"type\":\"varchar(255)\",\"nullable\":true,")
                    .append("\"comment\":\"").append("说".repeat(commentChars)).append("\",\"extra\":\"\"}");
        }
        return b.append(']').toString();
    }

    private static String keys() {
        return "[{\"name\":\"PRIMARY\",\"primary\":true,\"columns\":[\"id\"]}]";
    }

    private static ConnectorSemantic semantic(String scope, String object, String field, String term,
                                               String source, String gloss, String detail) {
        ConnectorSemantic s = new ConnectorSemantic();
        s.setTenantId("tenant-a");
        s.setConnectorId(CONNECTOR_ID);
        s.setScope(scope);
        s.setObjectName(object);
        s.setFieldName(field);
        s.setTerm(term);
        s.setSource(source);
        s.setGloss(gloss);
        s.setDetailJson(detail);
        return s;
    }

    private static TableListRequest listRequest(String query, String scope, Integer offset, Integer limit) {
        TableListRequest r = new TableListRequest();
        r.setQuery(query);
        r.setScope(scope);
        r.setOffset(offset);
        r.setLimit(limit);
        return r;
    }

    private static TableMetadataRequest metadataRequest(String... names) {
        TableMetadataRequest r = new TableMetadataRequest();
        r.setTables(List.of(names));
        return r;
    }
}

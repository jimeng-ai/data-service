package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticSlicePlannerTest {

    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectorSchemaMapper schemaMapper;
    private SemanticTableRenderer renderer;
    private SemanticSlicePlanner planner;
    private ConnectorSemanticGeneration generation;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGenerationTable.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ConnectorSchema.class);
    }

    @BeforeEach
    void setUp() {
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        renderer = mock(SemanticTableRenderer.class);
        planner = new SemanticSlicePlanner(tableMapper, schemaMapper, renderer);
        generation = generation(2);
    }

    @Test
    @DisplayName("按 importance_rank 升序装片")
    void 按rank升序() {
        stubRows(List.of(table("later", 9, "PENDING"), table("first", 1, "PENDING"),
                table("middle", 4, "PENDING")), 100);

        SemanticSlice slice = planner.plan(generation, 20, 40_000);

        assertEquals(List.of("first", "middle", "later"), names(slice));
    }

    @Test
    @DisplayName("NULL rank 排最后，再按表名排序")
    void null最后再按名称() {
        stubRows(List.of(table("z_null", null, "PENDING"), table("ranked", 3, "PENDING"),
                table("a_null", null, "PENDING")), 100);

        assertEquals(List.of("ranked", "a_null", "z_null"),
                names(planner.plan(generation, 20, 40_000)));
    }

    @Test
    @DisplayName("单片硬上限 20 张，传入更大 cap 也会夹紧")
    void 单片不超过20() {
        List<ConnectorSemanticGenerationTable> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) rows.add(table("t" + String.format("%02d", i), i, "PENDING"));
        stubRows(rows, 10);

        assertEquals(20, planner.plan(generation, 99, 40_000).size());
    }

    @Test
    @DisplayName("字符装不下即停止，不跳过大表去捡后面小表")
    void 字符上限不回填() {
        List<ConnectorSemanticGenerationTable> rows = List.of(
                table("first", 1, "PENDING"), table("large", 2, "PENDING"), table("small", 3, "PENDING"));
        when(tableMapper.selectList(any())).thenReturn(rows);
        when(schemaMapper.selectList(any())).thenReturn(rows.stream().map(SemanticSlicePlannerTest::schema).toList());
        when(renderer.render(any())).thenAnswer(invocation -> switch (((ConnectorSchema) invocation.getArgument(0)).getObjectName()) {
            case "first" -> "a".repeat(2_500);
            case "large" -> "b".repeat(2_000);
            default -> "c".repeat(10);
        });

        SemanticSlice slice = planner.plan(generation, 20, 4_000);

        assertEquals(List.of("first"), names(slice));
        assertEquals(2_500, slice.renderedChars());
    }

    @Test
    @DisplayName("超过字符上限的首表仍独占一片")
    void 超大首表独占() {
        stubRows(List.of(table("huge", 1, "PENDING"), table("next", 2, "PENDING")), 5_000);

        SemanticSlice slice = planner.plan(generation, 20, 4_000);

        assertEquals(List.of("huge"), names(slice));
        assertEquals(5_000, slice.renderedChars());
    }

    @Test
    @DisplayName("只取 PENDING，查询条件同时带 tenant 与 generation")
    @SuppressWarnings("unchecked")
    void 只取pending() {
        stubRows(List.of(table("pending", 1, "PENDING"), table("done", 0, "DONE"),
                table("removed", 0, "REMOVED")), 100);

        assertEquals(List.of("pending"), names(planner.plan(generation, 20, 40_000)));

        ArgumentCaptor<LambdaQueryWrapper<ConnectorSemanticGenerationTable>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tableMapper).selectList(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("PENDING"),
                query.getValue().getParamNameValuePairs().toString());
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("tenant-a"),
                query.getValue().getParamNameValuePairs().toString());
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(10L),
                query.getValue().getParamNameValuePairs().toString());
    }

    @Test
    @DisplayName("减半后的 cap 生效，预计总片数按当前 cap 动态计算")
    void 减半cap与估算片数() {
        List<ConnectorSemanticGenerationTable> rows = new ArrayList<>();
        for (int i = 1; i <= 9; i++) rows.add(table("t" + i, i, "PENDING"));
        stubRows(rows, 10);

        SemanticSlice slice = planner.plan(generation, 4, 40_000);

        assertEquals(4, slice.size());
        assertEquals(5, slice.estimatedTotalSlices()); // 已派 2 片 + ceil(9/4)
    }

    @Test
    @DisplayName("真实 SemanticTableRenderer.render(ConnectorSchema) 与 planner 计长完全同源")
    void 集成真实renderer() {
        SemanticTableRenderer realRenderer = new SemanticTableRenderer();
        planner = new SemanticSlicePlanner(tableMapper, schemaMapper, realRenderer);
        ConnectorSemanticGenerationTable orders = table("orders", 1, "PENDING");
        ConnectorSemanticGenerationTable users = table("users", 2, "PENDING");
        ConnectorSchema orderSchema = schema(orders);
        orderSchema.setObjectComment("订单事实表");
        orderSchema.setDetailJson("{\"fields\":[{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false,"
                + "\"comment\":\"订单编号\",\"extra\":\"\"}],\"extra\":{\"unique_keys\":[{\"name\":\"PRIMARY\","
                + "\"primary\":true,\"columns\":[\"id\"]}]}}");
        ConnectorSchema userSchema = schema(users);
        userSchema.setDetailJson("{\"fields\":[{\"name\":\"name\",\"type\":\"varchar(64)\","
                + "\"nullable\":true,\"comment\":\"姓名\",\"extra\":\"\"}],\"extra\":{\"unique_keys\":[]}}");
        when(tableMapper.selectList(any())).thenReturn(List.of(users, orders));
        when(schemaMapper.selectList(any())).thenReturn(List.of(userSchema, orderSchema));

        SemanticSlice slice = planner.plan(generation, 20, 40_000);

        assertEquals(List.of("orders", "users"), names(slice));
        assertEquals(realRenderer.render(orderSchema).length() + realRenderer.render(userSchema).length(),
                slice.renderedChars());
    }

    private void stubRows(List<ConnectorSemanticGenerationTable> rows, int chars) {
        when(tableMapper.selectList(any())).thenReturn(rows);
        when(schemaMapper.selectList(any())).thenReturn(rows.stream()
                .filter(row -> "PENDING".equals(row.getStatus()))
                .map(SemanticSlicePlannerTest::schema).toList());
        when(renderer.render(any())).thenReturn("x".repeat(chars));
    }

    private static List<String> names(SemanticSlice slice) {
        return slice.tables().stream().map(ConnectorSemanticGenerationTable::getObjectName).toList();
    }

    private static ConnectorSemanticGeneration generation(int currentSlice) {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(10L);
        generation.setTenantId("tenant-a");
        generation.setConnectorId(20L);
        generation.setCurrentSliceNo(currentSlice);
        return generation;
    }

    private static ConnectorSemanticGenerationTable table(String name, Integer rank, String status) {
        ConnectorSemanticGenerationTable table = new ConnectorSemanticGenerationTable();
        table.setTenantId("tenant-a");
        table.setGenerationId(10L);
        table.setConnectorId(20L);
        table.setObjectName(name);
        table.setObjectType("TABLE");
        table.setImportanceRank(rank);
        table.setStatus(status);
        return table;
    }

    private static ConnectorSchema schema(ConnectorSemanticGenerationTable table) {
        ConnectorSchema schema = new ConnectorSchema();
        schema.setTenantId(table.getTenantId());
        schema.setConnectorId(table.getConnectorId());
        schema.setObjectName(table.getObjectName());
        schema.setObjectType(table.getObjectType());
        schema.setImportanceRank(table.getImportanceRank());
        schema.setDetailJson("{\"fields\":[],\"extra\":{\"unique_keys\":[]}}");
        return schema;
    }
}

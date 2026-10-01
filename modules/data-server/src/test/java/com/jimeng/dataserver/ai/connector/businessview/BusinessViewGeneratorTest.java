package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticModelCall;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessViewGeneratorTest {

    private static final Long CONN_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorBusinessViewMapper viewMapper;
    private SemanticModelCall modelCall;
    private BusinessViewGenerator generator;

    /** 假的业务视图表：insert 像 MyBatis-Plus 一样回填 id，selectList 返回当前的全部行。 */
    private final List<ConnectorBusinessView> table = new ArrayList<>();
    /** 每一步（「业务领域」「业务名称」「关系角色名」）收到的用户提示词，按调用顺序。 */
    private final Map<String, List<String>> prompts = new HashMap<>();
    /** 每一步的回复：拿到第几次调用、这次的提示词，返回模型的 JSON。 */
    private final Map<String, Function<Integer, Map<String, Object>>> replies = new HashMap<>();

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorBusinessView.class);
    }

    @BeforeEach
    void setUp() {
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        viewMapper = mock(ConnectorBusinessViewMapper.class);
        modelCall = mock(SemanticModelCall.class);
        ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
        generator = new BusinessViewGenerator(schemaMapper, semanticMapper, viewMapper, semanticService, modelCall);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId("t1");
        when(semanticService.requireOwned(CONN_ID)).thenReturn(c);
        when(modelCall.modelCode()).thenReturn("claude-x");

        long[] ids = {100};
        when(viewMapper.insert(any(ConnectorBusinessView.class))).thenAnswer(inv -> {
            ConnectorBusinessView v = inv.getArgument(0);
            v.setId(ids[0]++);
            table.add(v);
            return 1;
        });
        when(viewMapper.update(any(), any())).thenReturn(1);
        when(viewMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(table));
        when(viewMapper.physicalDeleteRow(anyString(), any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(2);
            table.removeIf(v -> id.equals(v.getId()));
            return 1;
        });
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000))).thenAnswer(inv -> {
            String what = inv.getArgument(0);
            prompts.computeIfAbsent(what, k -> new ArrayList<>()).add(inv.getArgument(3));
            return replies.get(what).apply(prompts.get(what).size());
        });

        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schema("M_VENDOR", "供应商表", "OID", "NAME"),
                schema("T_PO_HEAD", "", "OID", "VENDORID"),
                schema("T_PO_DTL", "采购订单明细", "OID", "SOID", "QTY")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(
                object("M_VENDOR", "供应商主数据。"),
                object("T_PO_HEAD", "采购订单表头，一行一张订单。"),
                join("T_PO_HEAD", "VENDORID", "M_VENDOR", "OID", "NONE"),
                join("T_PO_DTL", "SOID", "T_PO_HEAD", "OID", "UNDECIDABLE"),
                join("T_PO_DTL", "QTY", "M_VENDOR", "OID", "REJECTED")));

        replies.put("业务领域", n -> Map.of("domains", List.of("采购", "主数据"), "assign", List.of(
                Map.of("name", "M_VENDOR", "domain", "主数据"),
                Map.of("name", "T_PO_HEAD", "domain", "采购"),
                Map.of("name", "T_PO_DTL", "domain", "采购"))));
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商，记着名称和联系方式。"),
                named("T_PO_HEAD", "采购订单", "一条记录是一张采购订单，记着供应商和下单日期。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品，记着数量。"))));
        replies.put("关系角色名", n -> Map.of("relations", List.of(
                Map.of("object", "T_PO_HEAD", "column", "VENDORID", "role", "供应商"),
                Map.of("object", "T_PO_DTL", "column", "SOID", "role", "所属订单"))));
    }

    // ------------------------------------------------------------------ 夹具

    private static ConnectorSchema schema(String name, String comment, String... columns) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String c : columns) {
            fields.add(Map.of("name", c, "type", "bigint", "nullable", true, "comment", ""));
        }
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(CONN_ID);
        row.setObjectType("TABLE");
        row.setObjectName(name);
        row.setObjectComment(comment);
        row.setDetailJson(json(Map.of("name", name, "type", "TABLE", "fields", fields,
                "extra", Map.of("unique_keys", List.of(Map.of("name", "PRIMARY", "primary", true, "columns", List.of("OID")))))));
        return row;
    }

    private static ConnectorSemantic object(String name, String gloss) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope("OBJECT");
        row.setObjectName(name);
        row.setFieldName("");
        row.setGloss(gloss);
        return row;
    }

    private static ConnectorSemantic join(String from, String column, String to, String toColumn, String verified) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope("JOIN");
        row.setObjectName(from);
        row.setFieldName(column);
        row.setDetailJson(json(Map.of("to_object", to, "to_column", toColumn)));
        row.setVerified(verified);
        row.setStatus("DRAFT");
        return row;
    }

    private static Map<String, Object> named(String name, String display, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("display_name", display);
        m.put("summary", summary);
        return m;
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConnectorBusinessView row(String kind, String object, String field) {
        return table.stream().filter(v -> v.getKind().equals(kind) && v.getObjectName().equals(object)
                && v.getFieldName().equals(field)).findFirst().orElse(null);
    }

    private BusinessViewGenerator.Result run() {
        return generator.generate(CONN_ID, () -> { });
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("★ 首次生成：三步各调一次模型；对象有业务名、说明、领域，关系有角色名；采样否掉的关系不起名")
    void 首次生成() {
        BusinessViewGenerator.Result result = run();

        assertEquals(1, prompts.get("业务领域").size());
        assertEquals(1, prompts.get("业务名称").size());
        assertEquals(1, prompts.get("关系角色名").size());
        ConnectorBusinessView vendor = row("OBJECT", "M_VENDOR", "");
        assertEquals("供应商", vendor.getDisplayName());
        assertEquals("主数据", vendor.getDomain());
        assertNotNull(vendor.getSummary());
        assertNotNull(vendor.getInputHash());
        assertEquals("claude-x", vendor.getModelCode());
        assertEquals(BusinessViewGenerator.PROMPT_VERSION, vendor.getPromptVersion());
        assertEquals("t1", vendor.getTenantId());
        assertEquals("MODEL", vendor.getSource());
        assertEquals("所属订单", row("RELATION", "T_PO_DTL", "SOID").getDisplayName());
        assertNull(row("RELATION", "T_PO_DTL", "QTY"), "采样否掉的关系不起名");
        assertFalse(prompts.get("关系角色名").get(0).contains("QTY"));
        assertEquals(3, result.namesWritten());
        assertEquals(2, result.rolesWritten());
        assertEquals(3, result.domainsAssigned());
        assertEquals(0, result.rejected());
    }

    @Test
    @DisplayName("角色名的提示词里用对象的业务名，而不是表名")
    void 角色用业务名() {
        run();
        String prompt = prompts.get("关系角色名").get(0);
        assertTrue(prompt.contains("T_PO_HEAD | VENDORID |  | 采购订单 | 供应商"), prompt);
    }

    @Test
    @DisplayName("★ 不合格的带着原因重试一次，第二次合格就写下")
    void 重试一次() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", n == 1 ? "T_PO_HEAD 订单" : "采购订单", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品。"))));

        BusinessViewGenerator.Result result = run();

        List<String> asked = prompts.get("业务名称");
        assertEquals(2, asked.size());
        assertTrue(asked.get(1).contains("T_PO_HEAD：业务名含代码「T_PO_HEAD」"), asked.get(1));
        assertFalse(asked.get(1).contains("M_VENDOR |"), "合格的不再问");
        assertEquals("采购订单", row("OBJECT", "T_PO_HEAD", "").getDisplayName());
        assertEquals(0, result.rejected());
    }

    @Test
    @DisplayName("两次都不合格：不写，页面按兜底显示")
    void 两次都不合格() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "疑似供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", "采购订单", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品。"))));

        BusinessViewGenerator.Result result = run();

        assertNull(row("OBJECT", "M_VENDOR", "").getDisplayName());
        assertEquals(1, result.rejected());
    }

    @Test
    @DisplayName("业务名和别的表重名：退回重起")
    void 重名() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", n == 1 ? "采购订单" : "采购订单抬头", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单", "一条记录是订单里的一行商品。"))));

        run();

        assertEquals("采购订单", row("OBJECT", "T_PO_DTL", "").getDisplayName());
        assertEquals("采购订单抬头", row("OBJECT", "T_PO_HEAD", "").getDisplayName());
        assertTrue(prompts.get("业务名称").get(1).contains("重名"));
    }

    @Test
    @DisplayName("★ 只补缺的和输入变了的：原样再跑一遍不调模型；改了表注释只重起那一张的名字")
    void 只补缺的() {
        run();
        prompts.clear();

        run();
        assertTrue(prompts.isEmpty(), "输入没变，一次模型都不调：" + prompts.keySet());

        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schema("M_VENDOR", "供应商表", "OID", "NAME"),
                schema("T_PO_HEAD", "采购订单头", "OID", "VENDORID"),
                schema("T_PO_DTL", "采购订单明细", "OID", "SOID", "QTY")));
        run();
        assertEquals(1, prompts.get("业务名称").size());
        assertTrue(prompts.get("业务名称").get(0).contains("T_PO_HEAD | 采购订单头"));
        assertFalse(prompts.get("业务名称").get(0).contains("M_VENDOR |"));
        assertNull(prompts.get("业务领域"), "领域只在缺的时候分配");
    }

    @Test
    @DisplayName("★ HUMAN 行一个字不碰：不问它的领域和名字，也不更新它")
    void HUMAN行不动() {
        ConnectorBusinessView human = new ConnectorBusinessView();
        human.setId(1L);
        human.setKind("OBJECT");
        human.setObjectName("M_VENDOR");
        human.setFieldName("");
        human.setDisplayName("我们的供应商");
        human.setSource("HUMAN");
        table.add(human);

        run();

        assertFalse(prompts.get("业务领域").get(0).contains("M_VENDOR |"));
        assertFalse(prompts.get("业务名称").get(0).contains("M_VENDOR |"));
        assertTrue(prompts.get("业务名称").get(0).contains("我们的供应商"), "人起的名字算已用过的名字");
        assertEquals("我们的供应商", human.getDisplayName());
        assertNull(human.getDomain());
    }

    @Test
    @DisplayName("对象从快照里消失：MODEL 行物理删除，HUMAN 行留着")
    void 消失的对象() {
        ConnectorBusinessView goneModel = new ConnectorBusinessView();
        goneModel.setId(1L);
        goneModel.setKind("OBJECT");
        goneModel.setObjectName("T_GONE");
        goneModel.setFieldName("");
        goneModel.setSource("MODEL");
        ConnectorBusinessView goneHuman = new ConnectorBusinessView();
        goneHuman.setId(2L);
        goneHuman.setKind("OBJECT");
        goneHuman.setObjectName("T_GONE_TOO");
        goneHuman.setFieldName("");
        goneHuman.setSource("HUMAN");
        ConnectorBusinessView goneRelation = new ConnectorBusinessView();
        goneRelation.setId(3L);
        goneRelation.setKind("RELATION");
        goneRelation.setObjectName("T_PO_DTL");
        goneRelation.setFieldName("QTY");
        goneRelation.setSource("MODEL");
        table.addAll(List.of(goneModel, goneHuman, goneRelation));

        BusinessViewGenerator.Result result = run();

        verify(viewMapper).physicalDeleteRow("t1", CONN_ID, 1L);
        verify(viewMapper).physicalDeleteRow("t1", CONN_ID, 3L);
        verify(viewMapper, never()).physicalDeleteRow("t1", CONN_ID, 2L);
        assertEquals(2, result.deleted());
    }

    @Test
    @DisplayName("一个系统最多 8 个领域：多出来的领域不收，归进去的表两次都不合格就留空（兜底「未分类」）")
    void 领域上限() {
        List<ConnectorSchema> many = new ArrayList<>();
        List<Map<String, Object>> assign = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(schema("T_" + i, "", "OID"));
            assign.add(Map.of("name", "T_" + i, "domain", "领域" + i));
            domains.add("领域" + i);
        }
        when(schemaMapper.selectList(any())).thenReturn(many);
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        replies.put("业务领域", n -> Map.of("domains", domains, "assign", assign));
        replies.put("业务名称", n -> Map.of("objects", List.of()));

        BusinessViewGenerator.Result result = run();

        assertEquals(8, result.domainsAssigned());
        assertEquals("领域8", row("OBJECT", "T_8", "").getDomain());
        assertNull(row("OBJECT", "T_9", ""));
        assertTrue(prompts.get("业务领域").get(1).contains("还能新增 0 个领域"), prompts.get("业务领域").get(1));
    }
}

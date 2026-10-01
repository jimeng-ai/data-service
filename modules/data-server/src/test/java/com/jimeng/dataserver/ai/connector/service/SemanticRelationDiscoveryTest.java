package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticRelationDiscoveryTest {

    private static final Long CONN_ID = 7L;

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService semanticService;
    private SemanticModelCall modelCall;
    private SemanticRelationDiscovery discovery;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        semanticService = mock(ConnectorSemanticService.class);
        modelCall = mock(SemanticModelCall.class);
        discovery = new SemanticRelationDiscovery(schemaMapper, semanticMapper, semanticService, modelCall);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setName("erp");
        when(semanticService.requireOwned(CONN_ID)).thenReturn(c);
        when(semanticService.mergeDiscoveredJoins(eq(CONN_ID), any(), any()))
                .thenReturn(new ConnectorSemanticService.DiscoveryWrite(0, 0, 0, 0, 0, List.of()));
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_ORDER_HEAD", "OID", List.of("OID", "VENDORID", "BILLNO")),
                RelationRuleFixtures.schema("T_ORDER_DTL", "OID", List.of("OID", "SOID", "POID", "MEMO", "ITEMCODE"))));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
    }

    private static Map<String, Object> join(String object, String column, String to, String toColumn, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object", object);
        m.put("column", column);
        m.put("to_object", to);
        m.put("to_column", toColumn);
        m.put("evidence", evidence);
        m.put("confidence", 70);
        return m;
    }

    @SafeVarargs
    private void modelReplies(Map<String, Object>... joins) {
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenReturn(Map.of("joins", List.of(joins)));
    }

    @SuppressWarnings("unchecked")
    private List<ConnectorSemantic> written() {
        ArgumentCaptor<List<ConnectorSemantic>> rows = ArgumentCaptor.forClass(List.class);
        verify(semanticService).mergeDiscoveredJoins(eq(CONN_ID), rows.capture(), any());
        return rows.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(ConnectorSemantic row) throws Exception {
        return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
    }

    private List<String> userPrompts(int times) {
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(modelCall, times(times)).askJson(anyString(), eq(CONN_ID), anyString(), user.capture(), eq(8000));
        return user.getAllValues();
    }

    @Test
    @DisplayName("★ 规则候选与模型产出一起写：来源各自标好，模型越界、GUESS、终点不存在的都丢掉")
    void 规则与模型() throws Exception {
        modelReplies(
                join("T_ORDER_DTL", "SOID", "T_ORDER_HEAD", "OID", "NAME"),
                join("T_ORDER_HEAD", "VENDORID", "T_ORDER_DTL", "OID", "NAME"),
                join("T_ORDER_DTL", "POID", "T_ORDER_HEAD", "OID", "GUESS"),
                join("T_ORDER_DTL", "ITEMCODE", "M_ITEM", "CODE", "COMMENT"));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        List<ConnectorSemantic> rows = written();
        assertEquals(2, rows.size());
        ConnectorSemantic rule = rows.stream().filter(r -> r.getFieldName().equals("VENDORID")).findFirst().orElseThrow();
        assertEquals("M_VENDOR", detail(rule).get("to_object"));
        assertEquals(RelationCandidates.ORIGIN_NAME_RULE, detail(rule).get("origin"));
        assertEquals(ConnectorSemanticService.EV_NAME, rule.getEvidence());
        ConnectorSemantic model = rows.stream().filter(r -> r.getFieldName().equals("SOID")).findFirst().orElseThrow();
        assertEquals("T_ORDER_HEAD", detail(model).get("to_object"));
        assertEquals(RelationCandidates.ORIGIN_RELATION_PASS, detail(model).get("origin"));
        assertEquals(ConnectorSemanticService.V_NONE, model.getVerified());
        assertEquals(1, result.nameRuleCandidates());
        assertEquals(3, result.modelProposed(), "越界的那一条在进组装之前就丢了");
        assertEquals(1, result.modelAccepted());
        assertTrue(result.modelAsked());
        assertNotNull(result.passFingerprint());
    }

    @Test
    @DisplayName("★ 模型自己都没把握（低于 60 或没报）的关联不收：真实库上它会把找不到对象的列硬指向公司表的主键")
    void 没把握的不收() {
        Map<String, Object> weak = join("T_ORDER_DTL", "SOID", "M_VENDOR", "OID", "NAME");
        weak.put("confidence", 40);
        weak.put("note", "库中无对应主数据表，宁缺毋滥");
        Map<String, Object> silent = join("T_ORDER_DTL", "ITEMCODE", "M_VENDOR", "OID", "NAME");
        silent.remove("confidence");
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenReturn(Map.of("joins", List.of(weak, silent), "unresolved", List.of("T_ORDER_DTL.POID")));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        assertEquals(1, written().size(), "只剩规则推出的那一条");
        assertEquals(0, result.modelProposed());
    }

    @Test
    @DisplayName("待判列：像引用、不是本表单列唯一键、还没有关系也没有规则候选；每片都带全部的表")
    void 待判列() {
        modelReplies();
        ConnectorSemantic existing = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, "T_ORDER_DTL", "POID", "");
        existing.setDetailJson("{\"to_object\":\"T_ORDER_HEAD\",\"to_column\":\"OID\"}");
        when(semanticMapper.selectList(any())).thenReturn(List.of(existing));

        discovery.discover(CONN_ID, null);

        String user = userPrompts(1).get(0);
        assertTrue(user.contains("T_ORDER_DTL.SOID |"), user);
        assertTrue(user.contains("T_ORDER_DTL.ITEMCODE |"), user);
        assertTrue(user.contains("T_ORDER_HEAD.BILLNO |"), user);
        assertFalse(user.contains("T_ORDER_DTL.POID |"), "已经有关系的列不再问");
        assertFalse(user.contains("T_ORDER_HEAD.VENDORID |"), "规则已经推出来的列不再问");
        assertFalse(user.contains("T_ORDER_DTL.OID |"), "本表自己的主键不是引用");
        assertFalse(user.contains("T_ORDER_DTL.MEMO |"), "不像引用的列不问");
        assertTrue(user.contains("T_ORDER_DTL.POID → T_ORDER_HEAD.OID"), "已知关联作为上下文给出");
        for (String table : List.of("M_VENDOR", "T_ORDER_HEAD", "T_ORDER_DTL")) {
            assertTrue(user.contains(table + " | "), "目标索引缺 " + table);
        }
    }

    @Test
    @DisplayName("一片最多问 200 列：450 列分 3 片，每一片都带完整的目标索引")
    void 按列数分片() {
        modelReplies();
        List<String> wide = new ArrayList<>();
        wide.add("OID");
        for (int i = 1; i <= 450; i++) {
            wide.add(String.format("C%04d_CODE", i));
        }
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_WIDE", "OID", wide)));

        discovery.discover(CONN_ID, null);

        List<String> prompts = userPrompts(3);
        for (String user : prompts) {
            assertTrue(user.contains("M_VENDOR | ") && user.contains("T_WIDE | "), "有一片看不见全部的表");
        }
        assertTrue(prompts.get(2).contains("T_WIDE.C0450_CODE |"));
    }

    @Test
    @DisplayName("待判列装不下一片（6 万字符）：分片，每一片都带完整的目标索引")
    void 分片() {
        modelReplies();
        List<String> wide = new ArrayList<>();
        wide.add("OID");
        for (int i = 1; i <= 180; i++) {
            wide.add(String.format("C%04d_CODE", i));
        }
        ConnectorSchema wideTable = RelationRuleFixtures.schema("T_WIDE", "OID", wide);
        // 注释在待判列里截到 60 个字，一行约 80 字符；再把目标索引撑长，180 列就装不进一片。
        wideTable.setDetailJson(wideTable.getDetailJson().replace("\"comment\":\"\"",
                "\"comment\":\"" + "这一列的注释写得很长".repeat(10) + "\""));
        wideTable.setObjectComment("宽表");
        List<ConnectorSchema> schemas = new ArrayList<>();
        schemas.add(RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")));
        schemas.add(wideTable);
        // 1200 张只有主键的表：目标索引约 6 万字符，留给待判列的只剩最低保底的 4000 字符，180 列要分好几片。
        for (int i = 0; i < 1200; i++) {
            ConnectorSchema t = RelationRuleFixtures.schema(String.format("T_PAD_%04d", i), "OID", List.of("OID"));
            t.setObjectComment("填充用的一张表，只为把目标索引撑长到几万字符那么长");
            schemas.add(t);
        }
        when(schemaMapper.selectList(any())).thenReturn(schemas);

        discovery.discover(CONN_ID, null);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(modelCall, atLeast(2)).askJson(anyString(), eq(CONN_ID), anyString(), user.capture(), eq(8000));
        List<String> prompts = user.getAllValues();
        for (String p : prompts) {
            assertTrue(p.contains("M_VENDOR | ") && p.contains("T_WIDE | ") && p.contains("T_PAD_1199 | "),
                    "有一片看不见全部的表");
        }
        assertTrue(prompts.get(0).contains("T_WIDE.C0001_CODE |")
                && prompts.get(prompts.size() - 1).contains("T_WIDE.C0180_CODE |"));
    }

    @Test
    @DisplayName("模型的输入没变：不再问，规则候选照写")
    void 输入没变不再问() {
        modelReplies();
        String fingerprint = discovery.discover(CONN_ID, null).passFingerprint();

        SemanticRelationDiscovery.Result again = discovery.discover(CONN_ID, fingerprint);

        verify(modelCall, times(1)).askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000));
        assertFalse(again.modelAsked());
        assertEquals(fingerprint, again.passFingerprint());
        verify(semanticService, times(2)).mergeDiscoveredJoins(eq(CONN_ID), any(), any());
    }

    @Test
    @DisplayName("★ 上一轮自己写下的关系不算输入变化：规则候选和模型产出落库之后因为别的原因重跑，不再问模型")
    void 自己写下的不算变化() {
        // 上一个用例的假库两次都是空的，等于假设第一轮什么都没写；这里让写进去的行下一轮读得到，和真库一样。
        List<ConnectorSemantic> db = new ArrayList<>();
        when(semanticMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(db));
        when(semanticService.mergeDiscoveredJoins(eq(CONN_ID), any(), any())).thenAnswer(inv -> {
            List<ConnectorSemantic> rows = inv.getArgument(1);
            for (ConnectorSemantic r : rows) {
                db.removeIf(d -> d.getObjectName().equals(r.getObjectName())
                        && d.getFieldName().equals(r.getFieldName()));
                db.add(r);
            }
            return new ConnectorSemanticService.DiscoveryWrite(rows.size(), 0, 0, 0, 0, List.of());
        });
        modelReplies(join("T_ORDER_DTL", "SOID", "T_ORDER_HEAD", "OID", "NAME"));
        String fingerprint = discovery.discover(CONN_ID, null).passFingerprint();

        SemanticRelationDiscovery.Result again = discovery.discover(CONN_ID, fingerprint);

        verify(modelCall, times(1)).askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000));
        assertFalse(again.modelAsked(), "答过的列和没把握不答的列都不该再问一遍");
        assertEquals(fingerprint, again.passFingerprint());
    }

    @Test
    @DisplayName("★ 模型失败：规则候选照样落库；指纹不前移，下次照样再问")
    void 模型失败() {
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenThrow(new IllegalStateException("模型回复解析失败"));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, "old");

        assertEquals(1, written().size());
        assertEquals("模型回复解析失败", result.modelError());
        assertEquals("old", result.passFingerprint());
        assertTrue(result.note().contains("模型那一遍失败"), result.note());
    }

    @Test
    @DisplayName("没有待判列：不调模型")
    void 没有待判列() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_ORDER", "OID", List.of("OID", "VENDORID"))));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        verify(modelCall, never()).askJson(anyString(), any(), anyString(), anyString(), eq(8000));
        assertFalse(result.modelAsked());
        assertNull(result.modelError());
        assertEquals(1, written().size());
    }

    @Test
    @DisplayName("声明的外键：依据标 COMMENT，来源标 FK")
    void 外键依据() throws Exception {
        modelReplies();
        List<ConnectorSchema> schemas = new ArrayList<>(List.of(
                RelationRuleFixtures.schema("M_PARTNER", "OID", List.of("OID")),
                RelationRuleFixtures.schema("T_ORDER", "OID", List.of("OID", "BUYER"))));
        schemas.get(1).setDetailJson(schemas.get(1).getDetailJson().replace("\"unique_keys\":",
                "\"foreign_keys\":[{\"name\":\"fk\",\"columns\":[\"BUYER\"],\"ref_table\":\"M_PARTNER\","
                        + "\"ref_columns\":[\"OID\"]}],\"unique_keys\":"));
        when(schemaMapper.selectList(any())).thenReturn(schemas);

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        ConnectorSemantic row = written().get(0);
        assertEquals(ConnectorSemanticService.EV_COMMENT, row.getEvidence());
        assertEquals(RelationCandidates.ORIGIN_FK, detail(row).get("origin"));
        assertEquals(1, result.foreignKeyCandidates());
    }
}

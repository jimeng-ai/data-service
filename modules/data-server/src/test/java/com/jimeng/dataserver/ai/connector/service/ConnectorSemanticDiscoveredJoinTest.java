package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关系发现写 JOIN 行（数据星图设计 v3 §5.4）：逐列按「现有的行 × 候选来源」那张表处理。
 */
class ConnectorSemanticDiscoveredJoinTest {

    private static final Long CONN_ID = 100L;

    /** 快照里的单列唯一键：t_cust.id、t_cust.code 各自唯一；t_ord.id 唯一。 */
    private static final Map<String, Set<String>> UNIQUE = Map.of(
            "t_cust", Set.of("id", "code"),
            "t_ord", Set.of("id"),
            "t_item", Set.of("id"));

    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId("t1");
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private static ConnectorSemantic candidate(String from, String column, String to, String toColumn, String origin) {
        ConnectorSemantic row = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, from, column, "");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", to);
        detail.put("to_column", toColumn);
        detail.put("basis", "未经数据验证");
        detail.put("origin", origin);
        row.setDetailJson(SemanticRowAssembler.toJson(detail));
        row.setGloss("关联 " + to + "." + toColumn + "。本条未经数据验证，join 前建议先看该列的取值分布。");
        row.setEvidence(ConnectorSemanticService.EV_NAME);
        row.setConfidence(80);
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
        row.setAnchorHash("new-anchor");
        return row;
    }

    private static ConnectorSemantic existing(String from, String column, String to, String toColumn,
                                              String source, String verified, Map<String, Object> more) {
        ConnectorSemantic row = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, from, column, "");
        row.setId(9L);
        row.setTenantId("t1");
        row.setConnectorId(CONN_ID);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", to);
        detail.put("to_column", toColumn);
        detail.putAll(more);
        row.setDetailJson(SemanticRowAssembler.toJson(detail));
        row.setSource(source);
        row.setVerified(verified);
        row.setGloss("旧的说明");
        row.setAnchorHash("old-anchor");
        return row;
    }

    private ConnectorSemanticService.DiscoveryWrite write(List<ConnectorSemantic> existingRows,
                                                          ConnectorSemantic... candidates) {
        when(semanticMapper.selectList(any())).thenReturn(existingRows);
        return service.mergeDiscoveredJoins(CONN_ID, List.of(candidates), UNIQUE);
    }

    private LambdaUpdateWrapper<ConnectorSemantic> capturedUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper).update(any(), cap.capture());
        return cap.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailIn(LambdaUpdateWrapper<ConnectorSemantic> w) throws Exception {
        for (Object v : w.getParamNameValuePairs().values()) {
            if (v instanceof String s && s.contains("to_object")) {
                return CommonUtil.getObjectMapper().readValue(s, Map.class);
            }
        }
        throw new AssertionError("set 子句里没有 detail_json：" + w.getSqlSet());
    }

    @Test
    @DisplayName("这一列还没有关系：新增一条推断行（DRAFT / NONE，带租户与连接）")
    void 新增() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
        verify(semanticMapper).insert(cap.capture());
        ConnectorSemantic row = cap.getValue();
        assertEquals("t1", row.getTenantId());
        assertEquals(CONN_ID, row.getConnectorId());
        assertEquals(ConnectorSemanticService.SOURCE_INFERRED, row.getSource());
        assertEquals(ConnectorSemanticService.ST_DRAFT, row.getStatus());
        assertEquals(ConnectorSemanticService.V_NONE, row.getVerified());
        assertEquals(1, w.inserted());
        assertEquals(List.of("t_ord"), w.touchedObjects());
    }

    @Test
    @DisplayName("模型那一遍的候选，这一列还没有关系时同样新增")
    void 模型候选新增() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_item", "so_id", "t_ord", "id", RelationCandidates.ORIGIN_RELATION_PASS));
        assertEquals(1, w.inserted());
    }

    @Test
    @DisplayName("人工或导入的行：一个字不碰，哪怕它指向的不是唯一键")
    void 人工行不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "name", ConnectorSemanticService.SOURCE_HUMAN, "NONE",
                                Map.of("human_verdict", "RELATED")),
                        existing("t_ord", "shop_id", "t_cust", "name", ConnectorSemanticService.SOURCE_IMPORTED, "NONE",
                                Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK),
                candidate("t_ord", "shop_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        verify(semanticMapper, never()).insert(any(ConnectorSemantic.class));
        verify(semanticMapper, never()).update(any(), any());
        assertEquals(2, w.kept());
    }

    @Test
    @DisplayName("推断的行已核对通过：不动（数据说它成立）")
    void 已核对的不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "name", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_CONFIRMED, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("★ 推断的行指向非唯一键、也没核对通过：外键 / 命名规则的候选替换它，留下原终点")
    void 弱推断被替换() throws Exception {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_UNDECIDABLE, Map.of("sample_n", 3))),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        LambdaUpdateWrapper<ConnectorSemantic> u = capturedUpdate();
        Map<String, Object> detail = detailIn(u);
        assertEquals("t_cust", detail.get("to_object"));
        assertEquals("id", detail.get("to_column"));
        assertEquals("t_item.cust_id", detail.get("replaced_target"));
        assertEquals(RelationCandidates.ORIGIN_NAME_RULE, detail.get("origin"));
        assertTrue(u.getSqlSet().contains("verified"), "替换后要回到未核对：" + u.getSqlSet());
        assertTrue(u.getParamNameValuePairs().containsValue(ConnectorSemanticService.V_NONE));
        // 写回带「这一行仍是读到时的样子」的条件：读与写之间被人确认或刚落了核对结论的行不覆盖。
        assertTrue(u.getSqlSegment().contains("update_time"), u.getSqlSegment());
        assertEquals(1, w.replaced());
        assertEquals(List.of("t_ord"), w.touchedObjects());
    }

    @Test
    @DisplayName("终点已不在快照里：同样算弱推断，可被替换")
    void 终点消失() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_gone", "id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));
        assertEquals(1, w.replaced());
    }

    @Test
    @DisplayName("模型那一遍的候选不替换任何已有的行")
    void 模型候选不替换() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_RELATION_PASS));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("推断的行已经指向唯一键：不动（它本来就是一条像样的关系）")
    void 指向唯一键的不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "code", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("带业务方结论的行不动")
    void 业务方结论不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of("human_verdict", "UNRELATED"))),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("按唯一键的排序规则比：大小写不同的同一列算已有")
    void 大小写不敏感() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("T_ORD", "CUST_ID", "t_cust", "id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        verify(semanticMapper, never()).insert(any(ConnectorSemantic.class));
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("替换时这一行刚被别处改过：不覆盖，记一次并发跳过")
    void 替换撞上并发() {
        when(semanticMapper.update(any(), any())).thenReturn(0);
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        assertEquals(0, w.replaced());
        assertEquals(1, w.concurrentSkips());
        assertEquals(List.of(), w.touchedObjects());
    }

    @Test
    @DisplayName("插入撞唯一键：只丢这一条，其余照写")
    void 插入撞键() {
        when(semanticMapper.insert(any(ConnectorSemantic.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(1);
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK),
                candidate("t_item", "ord_id", "t_ord", "id", RelationCandidates.ORIGIN_NAME_RULE));

        assertEquals(1, w.conflicts());
        assertEquals(1, w.inserted());
        assertEquals(List.of("t_item"), w.touchedObjects());
    }
}

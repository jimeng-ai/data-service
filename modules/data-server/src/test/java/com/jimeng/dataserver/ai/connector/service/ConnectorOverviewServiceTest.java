package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「默认注入目录」（{@link ConnectorOverviewService}）。
 *
 * <p>四条断言对应四个真实的失败模式：
 * <ul>
 *   <li><b>没授权 / 没上下文就注入</b> → 匿名对话也能看见客户的表名；</li>
 *   <li><b>静默截断</b> → 模型把半截清单当成全部，然后说「系统里没有这张表」；</li>
 *   <li><b>不说自己是快照</b> → 模型拿一份可能过期的结构当权威；</li>
 *   <li><b>读库炸了就抛</b> → 一个可选优化打断整轮对话。</li>
 * </ul>
 */
class ConnectorOverviewServiceTest {

    private AgentConnectionMapper agentConnectionMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorProperties properties;
    private ConnectorOverviewService service;

    /** LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentConnection.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        agentConnectionMapper = mock(AgentConnectionMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        properties = new ConnectorProperties();
        // MetricRewriter 没有任何注入依赖，直接 new：它的上限字段有默认值，不经 Spring 也能用。
        service = new ConnectorOverviewService(agentConnectionMapper, connectionMapper,
                schemaMapper, semanticMapper, properties, new MetricRewriter());

        TenantContext.set("t1");
        AgentContext.set(AgentRuntimeView.builder().agentId(7L).tenantId("t1").build());
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ 不该注入的四种情况

    @Test
    void noConnectorGranted_injectsNothing() {
        when(agentConnectionMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.buildOverview())
                .as("没绑连接器时一个字节都不该注入")
                .isNull();
        // 连 connection 表都不该查——没授权就到此为止。
        verifyNoInteractions(connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
    void noAgentContext_failsClosed() {
        AgentContext.clear();

        assertThat(service.buildOverview())
                .as("没有 Agent 上下文＝谁也没授权，不是「不过滤」")
                .isNull();
        verifyNoInteractions(agentConnectionMapper, connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
    void noTenantContext_failsClosed() {
        TenantContext.clear();

        assertThat(service.buildOverview())
                .as("没有租户上下文时查询会命中 0 行，不能把它当成「这个租户没有连接器」继续往下走")
                .isNull();
        verifyNoInteractions(agentConnectionMapper, connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
    void dbFailure_injectsNothingAndDoesNotThrow() {
        when(agentConnectionMapper.selectList(any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(service.buildOverview())
                .as("读库失败只该少一段文字，绝不能把异常抛回对话循环")
                .isNull();
    }

    @Test
    void disabledByConfig_injectsNothing() {
        properties.getOverview().setEnabled(false);
        assertThat(service.buildOverview()).isNull();

        properties.getOverview().setEnabled(true);
        properties.getOverview().setMaxChars(0);
        assertThat(service.buildOverview()).as("预算配成 0 等同于关掉").isNull();

        verifyNoInteractions(agentConnectionMapper);
    }

    // ------------------------------------------------------------------ 注入的内容

    @Test
    void rendersTablesSemanticsAndGlossary_withSnapshotDisclaimer() {
        grant(1L, "crm", "客户库", "mysql");
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schema(1L, "t_ord_mst", "订单主表", "TABLE"),
                schema(1L, "v_ord_sum", null, "VIEW")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(
                object(1L, "t_ord_mst", "订单主表，一单一行", ConnectorSemanticService.ST_CONFIRMED),
                metric(1L, "销售额", "按支付时间统计，扣除退款", "张三", new Date())));

        String text = service.buildOverview();

        assertThat(text).isNotNull();
        assertThat(text).as("模型寻址用的是连接名").contains("crm");
        assertThat(text).contains("t_ord_mst", "订单主表", "语义: 订单主表，一单一行");
        assertThat(text).as("非普通表要标出类型").contains("v_ord_sum(VIEW)");
        assertThat(text).as("口径要带依据，否则口径被改坏之后没人看得见").contains("销售额", "扣除退款", "由张三确认");
        assertThat(text.indexOf("销售额")).as("口径必须排在表清单前面：它决定要选哪几张表")
                .isLessThan(text.indexOf("t_ord_mst"));
        assertThat(text).as("必须自报是快照，并指出权威结构在哪儿")
                .contains("conn_describe").contains("conn_catalog");
    }

    @Test
    void staleMetric_isNotInjectedAtAll() {
        grant(1L, "crm", null, "mysql");
        when(schemaMapper.selectList(any())).thenReturn(List.of(schema(1L, "t_ord_mst", "订单主表", "TABLE")));
        ConnectorSemantic stale = metric(1L, "销售额", "扣除退款", "张三", new Date());
        stale.setStatus(ConnectorSemanticService.ST_STALE);
        when(semanticMapper.selectList(any())).thenReturn(List.of(stale));

        assertThat(service.buildOverview())
                .as("STALE 口径里的 SQL 片段可能已经错了，而错了不报错——整条不注入，不是带个标记继续给")
                .doesNotContain("销售额");
    }

    // ------------------------------------------------------------------ ★ 截断必须自报

    @Test
    void truncation_saysHowManyWereOmitted_andKeepsAnnotatedTablesFirst() {
        properties.getOverview().setMaxChars(200);   // 刻意远小于 40 张表所需
        grant(1L, "crm", null, "mysql");

        List<ConnectorSchema> many = new ArrayList<>();
        many.add(schema(1L, "zz_has_comment", "这张有注释", "TABLE"));
        for (int i = 0; i < 40; i++) {
            many.add(schema(1L, String.format("t_bare_%02d", i), null, "TABLE"));
        }
        when(schemaMapper.selectList(any())).thenReturn(many);
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        String text = service.buildOverview();

        assertThat(text).as("总数必须如实给出").contains("共 41 张");
        assertThat(text).as("截断了就要说，否则模型会把半截清单当成全部")
                .contains("只列出").contains("conn_catalog");
        assertThat(text).as("不要因为这里没有就断言系统里没有这张表").contains("系统里没有这张表");
        assertThat(text).as("预算不够时先砍信息量最低的：有注释的表要留下")
                .contains("zz_has_comment");
        assertThat(text).as("确实砍掉了一部分").doesNotContain("t_bare_39");
    }

    @Test
    void multipleConnectors_eachGetsAShareOfTheBudget() {
        properties.getOverview().setMaxChars(300);
        Connection a = conn(1L, "crm", null, "mysql");
        Connection b = conn(2L, "erp", null, "mysql");
        when(agentConnectionMapper.selectList(any())).thenReturn(List.of(bind(1L), bind(2L)));
        when(connectionMapper.selectList(any())).thenReturn(List.of(a, b));

        List<ConnectorSchema> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(schema(1L, String.format("crm_t_%02d", i), "一段注释", "TABLE"));
        for (int i = 0; i < 30; i++) rows.add(schema(2L, String.format("erp_t_%02d", i), "一段注释", "TABLE"));
        when(schemaMapper.selectList(any())).thenReturn(rows);
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        String text = service.buildOverview();

        assertThat(text).as("第一个连接器不该把预算吃光——整个数据源消失在注入文本里是看不出来的")
                .contains("crm_t_00").contains("erp_t_00");
    }

    @Test
    void connectorWithoutSnapshot_isSkippedEntirely() {
        grant(1L, "crm", null, "mysql");
        when(schemaMapper.selectList(any())).thenReturn(List.of());
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.buildOverview())
                .as("既没快照也没口径的连接器，只写一行标题是纯噪声")
                .isNull();
    }

    @Test
    void onlyActiveConnectionsAreConsidered() {
        grant(1L, "crm", null, "mysql");
        when(schemaMapper.selectList(any())).thenReturn(List.of(schema(1L, "t_ord_mst", "订单主表", "TABLE")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        service.buildOverview();

        // 状态过滤写在 wrapper 里，这里只保证真的走了 connection 表那一趟（wrapper 内容由集成层保证）。
        verify(connectionMapper).selectList(any());
    }

    // ------------------------------------------------------------------ 夹具

    private void grant(Long id, String name, String display, String kind) {
        when(agentConnectionMapper.selectList(any())).thenReturn(List.of(bind(id)));
        when(connectionMapper.selectList(any())).thenReturn(List.of(conn(id, name, display, kind)));
    }

    private static AgentConnection bind(Long connectionId) {
        AgentConnection ac = new AgentConnection();
        ac.setAgentId(7L);
        ac.setConnectionId(connectionId);
        return ac;
    }

    private static Connection conn(Long id, String name, String display, String kind) {
        Connection c = new Connection();
        c.setId(id);
        c.setName(name);
        c.setDisplayName(display);
        c.setKind(kind);
        c.setStatus("ACTIVE");
        return c;
    }

    private static ConnectorSchema schema(Long connectorId, String name, String comment, String type) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(connectorId);
        s.setObjectName(name);
        s.setObjectComment(comment);
        s.setObjectType(type);
        return s;
    }

    private static ConnectorSemantic object(Long connectorId, String objectName, String gloss, String status) {
        ConnectorSemantic s = new ConnectorSemantic();
        s.setConnectorId(connectorId);
        s.setScope(ConnectorSemanticService.SCOPE_OBJECT);
        s.setObjectName(objectName);
        s.setGloss(gloss);
        s.setStatus(status);
        s.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        return s;
    }

    private static ConnectorSemantic metric(Long connectorId, String term, String gloss,
                                            String answeredName, Date answeredAt) {
        ConnectorSemantic s = new ConnectorSemantic();
        s.setConnectorId(connectorId);
        s.setScope(ConnectorSemanticService.SCOPE_METRIC);
        s.setObjectName("");
        s.setFieldName("");
        s.setTerm(term);
        s.setGloss(gloss);
        s.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        s.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        s.setAnsweredName(answeredName);
        s.setAnsweredAt(answeredAt);
        return s;
    }
}

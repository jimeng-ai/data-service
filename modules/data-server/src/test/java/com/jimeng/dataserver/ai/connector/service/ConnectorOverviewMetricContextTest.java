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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「这一轮的确定性口径命中」：{@link ConnectorOverviewService#buildRequestContext}。
 *
 * <p>断言对应的失败都不会报错，只会让答案悄悄错掉：
 * <ul>
 *   <li>跨连接合并口径 → 同一个词出现两条矛盾定义，模型挑了哪条没人知道；</li>
 *   <li>把没确认过的口径当成唯一标准摆出去 → 一条编出来的口径被当成事实执行；</li>
 *   <li>改写用户原话 → 对话记录里再也复原不出模型当时读到了什么；</li>
 *   <li>关掉目录概览顺手关掉口径 → 有人为省上下文关了概览，口径从此静默失效。</li>
 * </ul>
 */
class ConnectorOverviewMetricContextTest {

    private AgentConnectionMapper agentConnectionMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorProperties properties;
    private ConnectorOverviewService service;

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

    @Test
    void hitOnThisTurn_isInjectedWithItsBasis() {
        granted(List.of(conn(1L, "crm")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(metric(1L, "销售额", "按支付时间统计，扣除退款")));

        String block = service.buildRequestContext("上个月销售额多少").getMetricContext();

        assertThat(block).contains("销售额").contains("扣除退款");
        // 依据那半句是口径被改坏之后唯一可能被业务方看见并纠正的地方，不能省。
        assertThat(block).contains("依据").contains("确认");
    }

    @Test
    void wordNotMentionedThisTurn_injectsNothing() {
        granted(List.of(conn(1L, "crm")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(metric(1L, "销售额", "扣除退款")));

        assertThat(service.buildRequestContext("帮我看看今天的天气").getMetricContext())
                .as("没命中就不该注入一个只有标题的空块")
                .isNull();
    }

    /** ★ 块里没有「这是哪条连接」这一栏，所以必须一条连接一段，而且各自点名。 */
    @Test
    void twoConnectors_areNeverMergedIntoOneBlock() {
        granted(List.of(conn(1L, "crm"), conn(2L, "erp")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(
                metric(1L, "销售额", "扣除退款"),
                metric(2L, "销售额", "不扣退款，含税")));

        String block = service.buildRequestContext("销售额多少").getMetricContext();

        assertThat(block).contains("[数据源 crm").contains("[数据源 erp");
        assertThat(block).contains("只适用于这一条连接");
        assertThat(block.indexOf("扣除退款"))
                .as("两条矛盾定义必须落在各自的数据源段落里")
                .isLessThan(block.indexOf("[数据源 erp"));
    }

    @Test
    void onlyHumanConfirmedMetricsAreForced() {
        granted(List.of(conn(1L, "crm")));
        ConnectorSemantic inferred = metric(1L, "销售额", "平台自己推出来的口径");
        inferred.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        when(semanticMapper.selectList(any())).thenReturn(List.of(inferred));

        assertThat(service.buildRequestContext("销售额多少").getMetricContext())
                .as("口径的答案不在数据库里，推出来的都是编的——编出来的不能按唯一标准摆出去")
                .isNull();
    }

    @Test
    void overviewDisabled_stillDetectsMetrics() {
        properties.getOverview().setEnabled(false);
        granted(List.of(conn(1L, "crm")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(metric(1L, "销售额", "扣除退款")));

        ConnectorOverviewService.RequestContext ctx = service.buildRequestContext("销售额多少");

        assertThat(ctx.getOverview()).as("关掉的是表清单").isNull();
        assertThat(ctx.getMetricContext()).as("口径是正确性机制，不该被一个省往返的开关顺手关掉").isNotNull();
        // 表清单那一趟是最重的一次查询，关掉概览就不该再打。
        verifyNoInteractions(schemaMapper);
    }

    @Test
    void overviewDisabledAndNoUserText_touchesNothing() {
        properties.getOverview().setEnabled(false);

        assertThat(service.buildRequestContext(null).isEmpty()).isTrue();
        verifyNoInteractions(agentConnectionMapper, connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
    void dbFailure_injectsNothingAndDoesNotThrow() {
        when(agentConnectionMapper.selectList(any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(service.buildRequestContext("销售额多少").isEmpty())
                .as("读库失败只该少一段文字，绝不能把异常抛回对话循环")
                .isTrue();
    }

    // ------------------------------------------------------------------ 夹具

    private void granted(List<Connection> conns) {
        List<AgentConnection> binds = new ArrayList<>();
        for (Connection c : conns) {
            AgentConnection ac = new AgentConnection();
            ac.setAgentId(7L);
            ac.setConnectionId(c.getId());
            binds.add(ac);
        }
        when(agentConnectionMapper.selectList(any())).thenReturn(binds);
        when(connectionMapper.selectList(any())).thenReturn(conns);
        // schemaMapper 刻意不打桩：本用例只关心口径，表清单那一趟查不查得到无关紧要，
        // 而不打桩才能让 verifyNoInteractions 干净地断言"关掉概览就真的没打那一趟"。
    }

    private static Connection conn(Long id, String name) {
        Connection c = new Connection();
        c.setId(id);
        c.setName(name);
        c.setKind("mysql");
        c.setStatus("ACTIVE");
        return c;
    }

    private static ConnectorSemantic metric(Long connectorId, String term, String gloss) {
        ConnectorSemantic m = new ConnectorSemantic();
        m.setConnectorId(connectorId);
        m.setScope(ConnectorSemanticService.SCOPE_METRIC);
        m.setTerm(term);
        m.setGloss(gloss);
        m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        m.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        m.setAnsweredAt(new Date());
        return m;
    }
}

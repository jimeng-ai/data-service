package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关横切层的 <b>fail-closed 行为</b>。
 *
 * <p>这些分支的共同点是：坏掉的时候<b>不会报错</b>，只会「悄悄放行」或「悄悄查不到」。
 * 没有回归保护的话，某次重构把某一处的 {@code throw} 改成 {@code return} 谁都不会发现，
 * 直到某天一次匿名对话碰到了客户的生产库。
 *
 * <p>另外三条（限流、审计、执行）不在这里测：它们坏掉是吵闹的，而且已在真实环境跑通过。
 */
class ConnectorGatewayTest {

    private ConnectionMapper connectionMapper;
    private AgentConnectionMapper agentConnectionMapper;
    private ConnectorProperties properties;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private ConnectorGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        agentConnectionMapper = mock(AgentConnectionMapper.class);
        properties = new ConnectorProperties();
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        gateway = new ConnectorGateway(
                connectionMapper,
                agentConnectionMapper,
                registry,
                loader,
                properties,
                mock(ConnectorAuditService.class),
                mock(RedisTemplate.class));
        TenantContext.set("t1");
        AgentContext.set(AgentRuntimeView.builder().agentId(7L).tenantId("t1").build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AgentContext.clear();
    }

    /** 一条正常可用的连接行：ACTIVE、direct、能力齐。 */
    private Connection row() {
        Connection c = new Connection();
        c.setId(100L);
        c.setTenantId("t1");
        c.setName("crm");
        c.setKind("MYSQL");
        c.setStatus("ACTIVE");
        c.setTransport("direct");
        c.setCapabilityFlags("QUERY,DESCRIBE,HEALTH");
        return c;
    }

    @SuppressWarnings("unchecked")
    private void givenRow(Connection c) {
        when(connectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(c);
    }

    @SuppressWarnings("unchecked")
    private void givenGranted(boolean granted) {
        when(agentConnectionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(granted ? 1L : 0L);
    }

    private ConnectorException call() {
        return assertThrows(ConnectorException.class,
                () -> gateway.execute("crm", Capability.QUERY, "conn_query", session -> "never"));
    }

    // ================================================================

    @Test
    @DisplayName("总开关关闭 → 明确报错，不是静默返回空")
    void 总开关关闭时明确报错() {
        properties.setEnabled(false);
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 不能依赖 MyBatis 拦截器的 {@code __no_tenant__} 哨兵兜底。
     * 那个兜底的表现是「查不到任何行」，会被上层读成「没有这条连接」，
     * 再被模型转述成「系统里没有这个数据源」——一个线程上下文丢失的 bug，
     * 最后变成一句自信的错误回答。
     */
    @Test
    @DisplayName("租户上下文缺失 → 明确报错，不靠哨兵兜底")
    void 租户上下文缺失时明确报错() {
        TenantContext.clear();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 最关键的一条。直连 {@code /data/claude/messages} 不带 {@code agent_id} 时
     * {@code AgentContext.get()} 就是 null。绝不能照抄
     * {@code SkillRuntimeService.filterByAgentAllowlist} 的 {@code if (agent == null) return packages;}
     * ——那是「不过滤」，用在连接器上等于任何一次匿名对话都能碰客户的生产库。
     */
    @Test
    @DisplayName("AgentContext 为 null → 拒绝（不是不过滤）")
    void 无Agent上下文时拒绝() {
        AgentContext.clear();
        givenRow(row());
        assertEquals(ConnectorErrorCode.FORBIDDEN, call().getCode());
    }

    @Test
    @DisplayName("连接不存在与未被授权 → 同形，避免用报错差异枚举出租户里有哪些连接")
    void 不存在与未授权同形() {
        givenRow(null);
        ConnectorException notFound = call();

        givenRow(row());
        givenGranted(false);
        ConnectorException noGrant = call();

        assertEquals(ConnectorErrorCode.NOT_FOUND, notFound.getCode());
        assertEquals(ConnectorErrorCode.NOT_FOUND, noGrant.getCode());
        assertEquals(notFound.getSafeDetail(), noGrant.getSafeDetail());
    }

    @Test
    @DisplayName("连接已停用 → 拒绝")
    void 停用的连接被拒() {
        Connection c = row();
        c.setStatus("DISABLED");
        givenRow(c);
        givenGranted(true);
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 隧道未实现时要<b>明确报错</b>。{@code ConnectionResolver} 对这种行只 warn 后静默跳过，
     * 表现为「连接明明配好了却不生效」，排查成本极高。
     */
    @Test
    @DisplayName("transport=tunnel → 明确报错，不静默跳过")
    void 隧道未实现时明确报错() {
        Connection c = row();
        c.setTransport("tunnel");
        givenRow(c);
        givenGranted(true);
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        assertTrue(e.getSafeDetail().contains("隧道"));
    }

    @Test
    @DisplayName("能力不匹配 → 拒绝，并说清实际可用的是什么")
    void 能力不匹配被拒() {
        Connection c = row();
        c.setCapabilityFlags("INVOKE,HEALTH");   // 没有 QUERY
        givenRow(c);
        givenGranted(true);
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        assertTrue(e.getSafeDetail().contains("INVOKE"));
    }

    @Test
    @DisplayName("未探测（capability_flags 为空）→ 拒绝并提示去点测试连接")
    void 未探测的连接被拒() {
        Connection c = row();
        c.setCapabilityFlags(null);
        givenRow(c);
        givenGranted(true);
        assertTrue(call().getSafeDetail().contains("测试连接"));
    }

    // ---------------------------------------------------------------- listAuthorized

    @Test
    @DisplayName("listAuthorized：无 Agent 上下文 → 空集")
    void 列表在无Agent时返回空集() {
        AgentContext.clear();
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    @Test
    @DisplayName("listAuthorized：无租户上下文 → 空集（这里返回空而不是抛，见实现注释）")
    void 列表在无租户时返回空集() {
        TenantContext.clear();
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    @Test
    @DisplayName("listAuthorized：一条授权都没有 → 空集，且不去查 connection 表")
    void 列表在无授权时返回空集() {
        when(agentConnectionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    // ---------------------------------------------------------------- 能力标记解析

    @Test
    @DisplayName("能力标记：无法识别的值跳过而不是整条作废")
    void 能力标记容错() {
        Set<Capability> caps = ConnectorGateway.parseCapabilities("QUERY, SOMETHING_NEW ,DESCRIBE");
        assertEquals(Set.of(Capability.QUERY, Capability.DESCRIBE), caps);
    }

    @Test
    @DisplayName("能力标记：空 → 空集（而不是当成全能力）")
    void 能力标记为空时是空集() {
        assertTrue(ConnectorGateway.parseCapabilities(null).isEmpty());
        assertTrue(ConnectorGateway.parseCapabilities("  ").isEmpty());
    }

    /** 一条正向用例：六道闸全过之后，op 的返回值原样交回调用方。 */
    @Test
    @DisplayName("授权齐全 → 放行，并原样返回 op 的结果")
    void 授权齐全时放行() {
        givenRow(row());
        givenGranted(true);
        ConnectorInstance inst = new ConnectorInstance(
                100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
        when(loader.load(any(Connection.class))).thenReturn(inst);
        Connector connector = mock(Connector.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        when(connector.open(inst)).thenReturn(mock(ConnectorSession.class));

        String out = gateway.execute("crm", Capability.QUERY, "conn_query", session -> "已执行");
        assertEquals("已执行", out);
    }

    /**
     * 网关对外的契约是「只抛 ConnectorException」——工具层靠它保证回灌模型的永远是那九类之一。
     * loader 抛未归类异常时必须被归一，而不是原样穿出去。
     */
    @Test
    @DisplayName("实例加载抛未归类异常 → 归一成 ConnectorException，不穿透")
    void 实例加载异常被归一() {
        givenRow(row());
        givenGranted(true);
        when(loader.load(any(Connection.class))).thenThrow(new IllegalStateException("内部细节 db-prod-01"));
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        // 原始异常文本不准出现在对外文案里。
        assertTrue(!e.getMessage().contains("db-prod-01"));
    }
}

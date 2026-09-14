package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.service.PendingWriteService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ★ 管理面与 Agent 面的<b>限流桶必须是两本账</b>。
 *
 * <p>为什么值得单独一个用例类：语义层的采样验证一轮要往客户的库打上百条探查。
 * 两条路共用一个计数器时，一次后台剖析就能把客户当分钟的问数额度吃干净——
 * 表现是模型突然回「本租户对外部系统的调用已达每分钟上限」，而<b>客户那一侧一句话都没问</b>。
 * 更糟的是排查：审计里那一分钟确实有上百次调用，但它们全是平台自己发的，
 * 没有任何线索把「用户被限流」指回真凶。
 *
 * <p>这类 bug 的共同点是：坏掉的时候不会报错，只会偶发地砸在用户脸上。
 * 所以这里钉的是 key 的<b>形状</b>，而不是某个额度数字。
 *
 * @see ConnectorGatewayTest 其余横切层的 fail-closed 行为在那边
 */
class ConnectorGatewayPlatformRateBucketTest {

    private ConnectionMapper connectionMapper;
    private AgentConnectionMapper agentConnectionMapper;
    private ConnectorProperties properties;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private ConnectorAuditService auditService;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> ops;
    private ConnectorGateway gateway;

    /** 每一次 INCR 用的 key，按顺序记下来。 */
    private List<String> keys;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        agentConnectionMapper = mock(AgentConnectionMapper.class);
        properties = new ConnectorProperties();
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        auditService = mock(ConnectorAuditService.class);
        redisTemplate = mock(RedisTemplate.class);
        ops = mock(ValueOperations.class);
        ObjectProvider<PendingWriteService> pendingWrites = mock(ObjectProvider.class);
        gateway = new ConnectorGateway(connectionMapper, agentConnectionMapper, registry, loader,
                properties, auditService, redisTemplate, pendingWrites);

        keys = new ArrayList<>();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> {
            keys.add(inv.getArgument(0));
            return 1L;
        });

        TenantContext.set("t1");
        AgentContext.set(AgentRuntimeView.builder().agentId(7L).tenantId("t1").build());

        Connection row = new Connection();
        row.setId(100L);
        row.setTenantId("t1");
        row.setName("crm");
        row.setKind("MYSQL");
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        row.setCapabilityFlags("QUERY,DESCRIBE,HEALTH");
        when(connectionMapper.selectById(100L)).thenReturn(row);
        when(connectionMapper.selectOne(any())).thenReturn(row);
        when(agentConnectionMapper.selectCount(any())).thenReturn(1L);

        ConnectorInstance inst = new ConnectorInstance(100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
        when(loader.load(any(Connection.class))).thenReturn(inst);
        Connector connector = mock(Connector.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        when(connector.open(inst)).thenReturn(mock(ConnectorSession.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AgentContext.clear();
    }

    /**
     * ★ 本类的全部要点：两条路各记各的。
     *
     * <p>key 前缀不同，所以平台剖析打多少次都不会让客户的计数器往前走一格。
     */
    @Test
    @DisplayName("★ 管理面与 Agent 面用的不是同一个计数 key")
    void 两条路不共用一个桶() {
        gateway.execute("crm", Capability.QUERY, "conn_query", s -> "ok");
        gateway.executeAsPlatform(100L, Capability.QUERY, ConnectorAuditService.OP_SEMANTIC_PROBE, s -> "ok");

        assertEquals(2, keys.size());
        String agentKey = keys.get(0);
        String platformKey = keys.get(1);

        assertNotEquals(agentKey, platformKey, "共用一个 key 就是这次要修的那个缺陷");
        assertTrue(agentKey.startsWith(ConnectorGateway.RATE_KEY_PREFIX), agentKey);
        assertTrue(platformKey.startsWith(ConnectorGateway.RATE_KEY_PLATFORM_PREFIX), platformKey);
        // Agent 面的 key 不能<b>恰好</b>也匹配平台前缀，否则两者在 Redis 里仍然是同一族。
        assertFalse(agentKey.startsWith(ConnectorGateway.RATE_KEY_PLATFORM_PREFIX), agentKey);
        assertTrue(agentKey.contains("t1") && platformKey.contains("t1"), "两条路都必须按租户分桶");
    }

    /** key 里必须带租户与分钟窗口：少了租户是跨租户串账，少了分钟窗口就不是固定窗口限流了。 */
    @Test
    @DisplayName("两条路的 key 形状：前缀 + 租户 + 分钟窗口")
    void key的形状() {
        assertEquals("connector:rate:t1:29000000", ConnectorGateway.rateKey("t1", false, 29_000_000L));
        assertEquals("connector:rate:platform:t1:29000000", ConnectorGateway.rateKey("t1", true, 29_000_000L));
    }

    /**
     * 没单独配平台额度时沿用 Agent 面那个<b>数值</b>，但仍然是独立的一本账。
     *
     * <p>这一条同时保证了本次改动<b>只拆账、不动额度</b>——两件事混在一次改动里，
     * 出了问题分不清是哪一半造成的。
     */
    @Test
    @DisplayName("平台额度未配置时沿用每租户额度的数值（桶仍是分开的）")
    void 平台额度默认沿用数值() {
        properties.getLimit().setPerTenantPerMinute(1);
        properties.getLimit().setPerTenantPlatformPerMinute(0);
        when(ops.increment(anyString())).thenReturn(99L);

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> gateway.executeAsPlatform(100L, Capability.QUERY,
                        ConnectorAuditService.OP_SEMANTIC_PROBE, s -> "never"));

        assertEquals(ConnectorErrorCode.RATE_LIMITED, e.getCode());
        // 文案要分得开：给后台任务回一句「本租户调用过于频繁」，会把读日志的人带去查客户的用量。
        assertTrue(e.getSafeDetail().contains("平台侧"), e.getSafeDetail());
    }

    @Test
    @DisplayName("单独配了平台额度就用它，Agent 面的额度不受影响")
    void 平台额度可单独配() {
        properties.getLimit().setPerTenantPerMinute(1000);
        properties.getLimit().setPerTenantPlatformPerMinute(5);
        when(ops.increment(anyString())).thenReturn(6L);

        ConnectorException platform = assertThrows(ConnectorException.class,
                () -> gateway.executeAsPlatform(100L, Capability.QUERY,
                        ConnectorAuditService.OP_SEMANTIC_PROBE, s -> "never"));
        assertEquals(ConnectorErrorCode.RATE_LIMITED, platform.getCode());

        // 同样是第 6 次，Agent 面的额度是 1000，照常放行。
        assertEquals("ok", gateway.execute("crm", Capability.QUERY, "conn_query", s -> "ok"));
    }

    /**
     * 这是本文件里唯一刻意的 fail-open：限的是速率不是权限，
     * 因为缓存抖动把客户的查询全拒掉，代价大于收益。<b>拆桶不能顺手把它改掉。</b>
     */
    @Test
    @DisplayName("Redis 不可用时两条路都放行（唯一刻意的 fail-open 不受本次改动影响）")
    void Redis故障仍然放行() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertEquals("ok", gateway.execute("crm", Capability.QUERY, "conn_query", s -> "ok"));
        assertEquals("ok", gateway.executeAsPlatform(100L, Capability.QUERY,
                ConnectorAuditService.OP_SEMANTIC_PROBE, s -> "ok"));
    }
}

package com.jimeng.dataserver.ai.http.callback;

import cn.hutool.jwt.JWTUtil;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AgentExecRun;
import com.jimeng.persistence.mapper.AgentExecRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpGatewayEndedRunTest {
    private static final String SECRET = "synthetic-http-run-jwt-key-0123456789abcdef";
    private final AgentExecRunMapper runs = mock(AgentExecRunMapper.class);
    @Test
    @SuppressWarnings("unchecked")
    void recoveredRedisCannotReauthorizeEndedRunOnAnotherServingInstance() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Map<String, String> stored = new HashMap<>();
        doAnswer(i -> { stored.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("transient-delete-failure"));
        AgentExecRun run = new AgentExecRun();
        run.setId(123L); run.setAgentId("5"); run.setUserId("7"); run.setTenantId("t1"); run.setStatus("RUNNING");
        when(runs.selectById(123L)).thenAnswer(i -> run);
        var owner = new HttpGatewayRunRegistry(redis, runs);
        var peer = new HttpGatewayRunRegistry(redis, runs);
        var grants = List.of(new SkillGrant(1L, 1, "test", SkillManifest.EMPTY));
        assertThat(owner.begin("t1", "123", 5L, grants, 300_000)).isTrue();
        JwtSecretProvider key = new JwtSecretProvider(); ReflectionTestUtils.setField(key, "secret", SECRET);
        String token = JWTUtil.createToken(Map.of("purpose", "http-gateway", "tenant_id", "t1", "id", "7",
                "aid", "5", "rid", "123", "realm", "ENTERPRISE", "exp", new Date(System.currentTimeMillis() + 300_000)), SECRET.getBytes(StandardCharsets.UTF_8));
        for (var instance : List.of(owner, peer)) assertThat(invoke(instance, key, token, "tool")).isEqualTo(200);
        run.setUserId("8"); assertThat(invoke(peer, key, token, "tool")).isEqualTo(403); run.setUserId("7");
        run.setAgentId("6"); assertThat(invoke(peer, key, token, "tool")).isEqualTo(403); run.setAgentId("5");
        run.setTenantId("other"); assertThat(invoke(peer, key, token, "tool")).isEqualTo(403); run.setTenantId("t1");
        run.setDeleted(true); assertThat(invoke(peer, key, token, "tool")).isEqualTo(403); run.setDeleted(false);
        when(runs.selectById(123L)).thenReturn(null);
        assertThat(invoke(peer, key, token, "script")).isEqualTo(403);
        when(runs.selectById(123L)).thenThrow(new IllegalStateException("unreadable-state"));
        assertThat(invoke(peer, key, token, "script")).isEqualTo(403);
        doReturn(run).when(runs).selectById(123L);
        run.setStatus("FAILED"); // AgentExecRun 是跨实例共享的权威终态；Redis 删除失败时不回滚它。
        owner.end("t1", "123");
        assertThat(stored).isNotEmpty();
        for (var instance : List.of(owner, peer)) for (String plane : List.of("tool", "script")) {
            assertThat(invoke(instance, key, token, plane)).as("terminal %s callback", plane).isEqualTo(403);
        }
        doAnswer(i -> stored.remove(i.getArgument(0)) != null).when(redis).delete(anyString());
        owner.retryPending();
        assertThat(stored).isEmpty();
        assertThat(owner.end("t1", "123")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dualStoreFailureIsUnconfirmedAndRetriesWithOriginalTerminalState() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        var owner = new HttpGatewayRunRegistry(redis, runs);
        assertThat(owner.begin("t1", "123", 5L, List.of(), 300_000)).isTrue();
        var terminal = new AgentExecRun();
        terminal.setId(123L); terminal.setTenantId("t1"); terminal.setAgentId("5"); terminal.setUserId("7"); terminal.setStatus("SUCCESS");
        when(runs.updateById(any(AgentExecRun.class))).thenThrow(new IllegalStateException("db-unavailable"));
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis-unavailable"));
        assertThat(owner.confirmTerminal(terminal)).isFalse();
        assertThat(owner.end("t1", "123")).isFalse();
        var durable = new java.util.concurrent.atomic.AtomicReference<AgentExecRun>();
        doAnswer(i -> { durable.set(i.getArgument(0)); return 1; }).when(runs).updateById(any(AgentExecRun.class));
        doReturn(true).when(redis).delete(anyString());
        com.jimeng.common.core.tenant.TenantContext.set("other-request");
        try {
            owner.retryPending();
            assertThat(durable.get().getStatus()).isEqualTo("SUCCESS");
            assertThat(com.jimeng.common.core.tenant.TenantContext.get()).isEqualTo("other-request");
            assertThat(owner.end("t1", "123")).isTrue();
        } finally { com.jimeng.common.core.tenant.TenantContext.clear(); }
    }
    private static int invoke(HttpGatewayRunRegistry registry, JwtSecretProvider key, String token, String plane) throws Exception {
        var req = new MockHttpServletRequest("POST", "/data/internal/http-gateway/" + plane);
        req.addHeader("Authorization", token); req.addHeader("X-Tenant-Id", "t1"); req.addHeader("user-id", "7");
        var res = new MockHttpServletResponse();
        new HttpGatewayScopeFilter(registry, key).doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }
}

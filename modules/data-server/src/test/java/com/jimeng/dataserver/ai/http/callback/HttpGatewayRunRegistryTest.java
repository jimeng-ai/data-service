package com.jimeng.dataserver.ai.http.callback;

import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpGatewayRunRegistryTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private HttpGatewayRunRegistry registry;
    private final List<SkillGrant> grants = List.of(new SkillGrant(7L, 2, "crm",
            new SkillManifest(List.of("cdn.example.com"), true, 90, null, null, List.of())));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        var runs = mock(com.jimeng.persistence.mapper.AgentExecRunMapper.class);
        var run = new com.jimeng.persistence.entity.AgentExecRun();
        run.setId(123L); run.setTenantId("t1"); run.setAgentId("5"); run.setUserId("7"); run.setStatus("RUNNING");
        when(runs.selectById(123L)).thenReturn(run);
        registry = new HttpGatewayRunRegistry(redis, runs);
    }

    @Test
    @DisplayName("登记：键带租户和 runId，TTL 与 token 同口径；读回来的快照与登记的一致")
    void beginAndRead() {
        assertThat(registry.begin("t1", "123", 5L, grants, 60_000)).isTrue();
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("http-gw:run:t1:123"), value.capture(), eq(Duration.ofMillis(60_000)));
        when(ops.get("http-gw:run:t1:123")).thenReturn(value.getValue());
        assertThat(registry.activeGrants("t1", "123", 5L, 7L)).isEqualTo(grants);
    }

    @Test
    @DisplayName("fail-closed：没登记、Agent 对不上、Redis 出错，一律当作不在跑")
    void failClosed() {
        registry.begin("t1", "123", 5L, grants, 60_000);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(ops).set(anyString(), value.capture(), any(Duration.class));
        when(ops.get("http-gw:run:t1:123")).thenReturn(value.getValue());
        assertThat(registry.activeGrants("t1", "123", 6L, 7L)).isNull();
        assertThat(registry.activeGrants("t1", "124", 5L, 7L)).isNull();
        when(ops.get("http-gw:run:t1:123")).thenThrow(new RuntimeException("redis down"));
        assertThat(registry.activeGrants("t1", "123", 5L, 7L)).isNull();
    }

    @Test
    void registryFailuresDoNotLogStoredDataOrExceptionMessages() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HttpGatewayRunRegistry.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            when(ops.get("http-gw:run:t1:123")).thenReturn("SECRET-STORED-SENTINEL");
            assertThat(registry.activeGrants("t1", "123", 5L, 7L)).isNull();
            when(ops.get("http-gw:run:t1:123")).thenThrow(new IllegalStateException("SECRET-EXCEPTION-SENTINEL"));
            assertThat(registry.activeGrants("t1", "123", 5L, 7L)).isNull();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRET-STORED-SENTINEL", "SECRET-EXCEPTION-SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    @DisplayName("登记失败返回 false（调用方据此不下发能力）；结束即删除")
    void beginFailureAndEnd() {
        doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        assertThat(registry.begin("t1", "123", 5L, grants, 60_000)).isFalse();
        registry.end("t1", "123");
        verify(redis).delete("http-gw:run:t1:123");
    }
}

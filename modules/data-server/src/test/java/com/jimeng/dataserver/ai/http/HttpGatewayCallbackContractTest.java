package com.jimeng.dataserver.ai.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.handler.GlobalResponseHandler;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.http.callback.*;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 签发、真实 Redis 编解码、范围过滤、回调、网关和最终平台信封的同一条路径。 */
class HttpGatewayCallbackContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void toolTransformsBeforeFinalScrubAndScriptPreservesBytes() throws Exception {
        String secret = "a&b12345678";
        AtomicReference<String> upstreamCredential = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            upstreamCredential.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "<p>a&amp;b12345678 / a&amp;amp;b12345678</p>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            HttpGatewayProperties props = new HttpGatewayProperties();
            SkillManifest manifest = new SkillManifest(List.of("customer.test"), false, 60, null, null,
                    List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("customer.test"), List.of("GET"), null)));
            List<SkillGrant> grants = List.of(new SkillGrant(7L, 2, "crm", manifest));
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            Map<String, String> entries = new ConcurrentHashMap<>();
            when(redis.opsForValue()).thenReturn(ops);
            doAnswer(i -> { entries.put(i.getArgument(0), i.getArgument(1)); return null; })
                    .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
            when(ops.get(anyString())).thenAnswer(i -> entries.get(i.getArgument(0)));
            when(redis.delete(anyString())).thenAnswer(i -> entries.remove(i.getArgument(0)) != null);
            var runs = mock(com.jimeng.persistence.mapper.AgentExecRunMapper.class);
            var run = new com.jimeng.persistence.entity.AgentExecRun();
            run.setId(123L); run.setTenantId("t1"); run.setAgentId("5"); run.setUserId("1"); run.setStatus("RUNNING");
            when(runs.selectById(123L)).thenReturn(run);
            HttpGatewayRunRegistry registry = new HttpGatewayRunRegistry(redis, runs);
            assertThat(registry.begin("t1", "123", 5L, grants, 60_000)).isTrue();
            SkillSecretService secrets = mock(SkillSecretService.class);
            when(secrets.loadKeys(eq(grants))).thenReturn(List.of(new SkillKey("CRM_TOKEN", 7L, "crm",
                    List.of("customer.test"), List.of("GET"), null, secret)));
            HttpGateway gateway = new HttpGateway(props, secrets,
                    host -> List.of(InetAddress.getLoopbackAddress()), (host, ip, declared) -> null);
            JwtSecretProvider jwtSecret = new JwtSecretProvider();
            ReflectionTestUtils.setField(jwtSecret, "secret", "callback-contract-signing-key-0123456789");
            AdminAuthService auth = new AdminAuthService(mock(SysUserMapper.class), mock(SysEnterpriseMapper.class),
                    new BCryptPasswordEncoder(), jwtSecret);
            String token = auth.mintHttpGatewayToken(1L, "t1", 5L, "123", 60_000);
            var mvc = MockMvcBuilders.standaloneSetup(new HttpGatewayCallbackController(gateway, props))
                    .setControllerAdvice(new GlobalResponseHandler(new ObjectMapper()))
                    .addFilters(new HttpGatewayScopeFilter(registry, jwtSecret)).build();
            String url = "http://customer.test:" + server.getAddress().getPort() + "/";
            String input = new ObjectMapper().writeValueAsString(Map.of("input", Map.of("method", "GET", "url", url,
                    "headers", Map.of("Authorization", "Bearer {{CRM_TOKEN}}"), "as_text", true,
                    "tenantId", "evil", "agentId", 99, "runId", "evil", "grants", List.of())));
            var tool = mvc.perform(post("/data/internal/http-gateway/tool").header("Authorization", token)
                    .header("X-Tenant-Id", "t1").header("user-id", "1").contentType("application/json").content(input))
                    .andReturn().getResponse();
            assertThat(tool.getStatus()).isEqualTo(200);
            Map<String, Object> envelope = new ObjectMapper().readValue(tool.getContentAsByteArray(), Map.class);
            assertThat(envelope).containsEntry("success", true).containsEntry("respCode", "200");
            String body = (String) ((Map<?, ?>) envelope.get("data")).get("body");
            assertThat(body).doesNotContain(secret, "<p>").contains("a&amp;b12345678");
            assertThat(tool.getContentAsString()).doesNotContain(token);
            assertThat(upstreamCredential.get()).isEqualTo("Bearer " + secret);

            byte[] meta = new ObjectMapper().writeValueAsBytes(Map.of("method", "GET", "url", url,
                    "headers", List.of(List.of("Authorization", "Bearer {{CRM_TOKEN}}"))));
            byte[] frame = ByteBuffer.allocate(4 + meta.length).putInt(meta.length).put(meta).array();
            var script = mvc.perform(post("/data/internal/http-gateway/script").header("Authorization", token)
                    .header("X-Tenant-Id", "t1").header("user-id", "1")
                    .contentType(HttpEnvelope.CONTENT_TYPE).content(frame)).andReturn().getResponse();
            assertThat(script.getContentType()).isEqualTo(HttpEnvelope.CONTENT_TYPE);
            ByteBuffer bytes = ByteBuffer.wrap(script.getContentAsByteArray());
            byte[] responseMeta = new byte[bytes.getInt()]; bytes.get(responseMeta);
            assertThat(new ObjectMapper().readValue(responseMeta, Map.class)).containsEntry("status", 200);
            byte[] rawBody = new byte[bytes.remaining()]; bytes.get(rawBody);
            assertThat(new String(rawBody, StandardCharsets.UTF_8)).isEqualTo("<p>a&amp;b12345678 / a&amp;amp;b12345678</p>");
            registry.end("t1", "123");
            assertThat(mvc.perform(post("/data/internal/http-gateway/tool").header("Authorization", token)
                    .header("X-Tenant-Id", "t1").header("user-id", "1").content(input))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
        } finally { server.stop(0); }
    }
}

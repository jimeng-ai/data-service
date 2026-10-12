package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.http.HttpGateway;
import com.jimeng.dataserver.ai.http.HttpGatewayContext;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.HttpGatewayResult;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.service.ActivatedSkills;
import com.jimeng.dataserver.ai.skill.source.AiSkillToolPackage;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpRequestToolExecutorTest {

    private final HttpGateway gateway = mock(HttpGateway.class);
    private final HttpRequestToolExecutor executor = new HttpRequestToolExecutor(gateway, new HttpGatewayProperties());

    @AfterEach
    void clear() {
        AgentContext.clear();
        TenantContext.clear();
        MDC.remove("connectionId");
    }

    private static AiSkillToolPackage skill(long id, String name, SkillManifest m) {
        AiSkill s = new AiSkill();
        s.setId(id);
        s.setTenantId("t1");
        s.setName(name);
        s.setVersion(3);
        s.setManifest(m.toJson());
        return new AiSkillToolPackage(s);
    }

    private static final SkillManifest HTTP = new SkillManifest(List.of("api.customer.com"), false, 90, null, null, List.of());

    @Test
    @DisplayName("只把本轮已激活、且有 HTTP 声明的 skill 交给网关；租户、Agent、runId 取自线程上下文")
    void grantsComeFromActivatedSkillsOnly() {
        TenantContext.set("t1");
        AgentContext.set(AgentRuntimeView.builder().agentId(5L).tenantId("t1").build());
        MDC.put("connectionId", "run-9");
        when(gateway.executeTool(any(), any(), anyBoolean())).thenReturn(HttpGatewayResult.error(403, "denied", "x"));
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(skill(1L, "crm", HTTP), skill(2L, "plain", SkillManifest.EMPTY),
                    new com.jimeng.dataserver.ai.skill.model.SkillPackage("platform", "", "", null,
                            List.of(HttpRequestToolDefinition.DEF), new com.jimeng.dataserver.ai.skill.manifest.JmMetadata(
                            List.of("extra.example.com"), true, 60, null, null))));
            executor.execute("http_request", Map.of("method", "GET", "url", "https://api.customer.com/v1", "tenantId", "spoof", "agentId", 99L, "runId", "spoof"));
        } finally {
            ActivatedSkills.end();
        }
        ArgumentCaptor<HttpGatewayContext> ctx = ArgumentCaptor.forClass(HttpGatewayContext.class);
        verify(gateway).executeTool(ctx.capture(), any(), eq(false));
        assertThat(ctx.getValue().grants()).singleElement().satisfies(g -> {
            assertThat(g.skillId()).isEqualTo(1L);
            assertThat(g.version()).isEqualTo(3);
            assertThat(g.manifest().timeoutSeconds()).isEqualTo(90);
        });
        assertThat(ctx.getValue().tenantId()).isEqualTo("t1");
        assertThat(ctx.getValue().agentId()).isEqualTo(5L);
        assertThat(ctx.getValue().runId()).isEqualTo("run-9");
        assertThat(ctx.getValue().plane()).isEqualTo(HttpGatewayContext.Plane.TOOL);
    }

    @Test
    @DisplayName("本轮没激活带 HTTP 声明的 skill：不调网关，告诉模型先激活")
    void noActivatedSkill() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) executor.execute("http_request", Map.of("method", "GET", "url", "https://a.example.com"));
        assertThat(r).containsEntry("gateway_error", "no_http_skill");
        verify(gateway, never()).executeTool(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("参数不合法：不调网关，返回给模型能看懂的错误")
    void badInput() {
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(skill(1L, "crm", HTTP)));
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) executor.execute("http_request", Map.of("url", "https://a.example.com"));
            assertThat(r).containsEntry("gateway_error", "bad_request");
        } finally {
            ActivatedSkills.end();
        }
        verify(gateway, never()).executeTool(any(), any(), anyBoolean());
    }
    @Test
    void passesAsTextToGatewayAndNeverDecodesConvertedResponseAgain() {
        when(gateway.executeTool(any(), any(), eq(true))).thenReturn(new HttpGatewayResult(200,
                List.of(Map.entry("content-type", "text/html")), "<html>a&amp;b12345678".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, null, null));
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(skill(1, "crm", HTTP)));
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) executor.execute("http_request", Map.of("method", "GET", "url", "https://api.customer.com", "as_text", true));
            assertThat(r).containsEntry("body", "<html>a&amp;b12345678");
            verify(gateway).executeTool(any(), any(), eq(true));
        } finally {
            ActivatedSkills.end();
        }
    }

}

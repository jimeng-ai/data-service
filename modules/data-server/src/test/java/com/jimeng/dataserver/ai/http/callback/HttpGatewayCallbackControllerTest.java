package com.jimeng.dataserver.ai.http.callback;

import com.jimeng.dataserver.ai.http.HttpGateway;
import com.jimeng.dataserver.ai.http.HttpGatewayContext;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.HttpGatewayRequest;
import com.jimeng.dataserver.ai.http.HttpGatewayResult;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.http.tool.HttpToolResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

class HttpGatewayCallbackControllerTest {

    private final HttpGateway gateway = mock(HttpGateway.class);
    private final HttpGatewayProperties props = new HttpGatewayProperties();
    private final HttpGatewayCallbackController controller = new HttpGatewayCallbackController(gateway, props);
    private final List<SkillGrant> grants = List.of(new SkillGrant(7L, 1, "crm", SkillManifest.EMPTY));

    private MockHttpServletRequest authed(byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/data/internal/http-gateway/x");
        r.setAttribute(HttpGatewayTokens.PRINCIPAL_ATTRIBUTE, new HttpGatewayPrincipal("t1", 1L, 5L, "run-1"));
        r.setAttribute(HttpGatewayTokens.GRANTS_ATTRIBUTE, grants);
        r.setContent(body);
        return r;
    }

    @Test
    @DisplayName("/tool：身份和可用 skill 只取过滤器写的属性，参数原样交给网关，返回给模型的形状与对话平面一致")
    void tool() throws Exception {
        when(gateway.executeToolPayload(any(), any(), anyBoolean())).thenReturn(HttpToolResults.toModelPayload(new HttpGatewayResult(200,
                List.of(Map.entry("content-type", "application/json")), "{\"ok\":true}".getBytes(), false, null, null), false, 20000));
        Map<String, Object> out = controller.tool(authed(("{\"input\":{\"method\":\"GET\",\"url\":\"https://a.example.com\","
                + "\"headers\":{\"X-K\":\"{{K_1}}\"},\"tenantId\":\"evil\"}}").getBytes(StandardCharsets.UTF_8)));
        assertThat(out).containsEntry("status", 200).containsEntry("body", "{\"ok\":true}");
        ArgumentCaptor<HttpGatewayContext> ctx = ArgumentCaptor.forClass(HttpGatewayContext.class);
        ArgumentCaptor<HttpGatewayRequest> req = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(gateway).executeToolPayload(ctx.capture(), req.capture(), eq(false));
        assertThat(ctx.getValue()).isEqualTo(new HttpGatewayContext("t1", 5L, "run-1", HttpGatewayContext.Plane.TOOL, grants));
        assertThat(req.getValue().headers()).containsExactly(Map.entry("X-K", "{{K_1}}"));
    }

    @Test
    @DisplayName("/tool：请求体不是 JSON 或过大 → bad_request，不调网关")
    void toolBadBody() throws Exception {
        assertThat(controller.tool(authed("nope".getBytes()))).containsEntry("gateway_error", "bad_request");
        props.setToolRequestMaxBytes(10);
        assertThat(controller.tool(authed(("{\"input\":{\"method\":\"POST\",\"url\":\"u\",\"body\":\"" + "x".repeat(70_000) + "\"}}")
                .getBytes()))).containsEntry("gateway_error", "bad_request");
        verify(gateway, never()).executeToolPayload(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("/script：拆封包 → 网关（脚本平面）→ 回一个封包；头允许重名，正文原样")
    void script() throws Exception {
        when(gateway.execute(any(), any())).thenReturn(new HttpGatewayResult(201,
                List.of(Map.entry("content-type", "image/png"), Map.entry("set-cookie", "a=1"), Map.entry("set-cookie", "b=2")),
                new byte[]{1, 2, 3}, true, null, null));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.script(authed(HttpEnvelopeTest.frame(
                "{\"method\":\"POST\",\"url\":\"https://a.example.com/u\",\"headers\":[[\"Authorization\",\"Bearer {{K_1}}\"]]}",
                new byte[]{7, 7})), resp);
        ArgumentCaptor<HttpGatewayContext> ctx = ArgumentCaptor.forClass(HttpGatewayContext.class);
        ArgumentCaptor<HttpGatewayRequest> req = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(gateway).execute(ctx.capture(), req.capture());
        assertThat(ctx.getValue().plane()).isEqualTo(HttpGatewayContext.Plane.SCRIPT);
        assertThat(req.getValue().body()).containsExactly(7, 7);
        assertThat(req.getValue().headers()).containsExactly(Map.entry("Authorization", "Bearer {{K_1}}"));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentType()).startsWith(HttpEnvelope.CONTENT_TYPE);
        ByteBuffer b = ByteBuffer.wrap(resp.getContentAsByteArray());
        byte[] meta = new byte[b.getInt()];
        b.get(meta);
        assertThat(new String(meta, StandardCharsets.UTF_8)).contains("\"status\":201")
                .contains("[\"set-cookie\",\"a=1\"]").contains("[\"set-cookie\",\"b=2\"]").contains("\"truncated\":true");
        byte[] body = new byte[b.remaining()];
        b.get(body);
        assertThat(body).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("/script：坏封包回一个 400 的网关错误封包，不调网关")
    void scriptBadFrame() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.script(authed(new byte[]{0, 0}), resp);
        verify(gateway, never()).execute(any(), any());
        ByteBuffer b = ByteBuffer.wrap(resp.getContentAsByteArray());
        byte[] meta = new byte[b.getInt()];
        b.get(meta);
        assertThat(new String(meta, StandardCharsets.UTF_8)).contains("\"status\":400").contains("\"gatewayError\":\"bad_request\"");
    }
}

package com.jimeng.dataserver.ai.http.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.http.HttpGateway;
import com.jimeng.dataserver.ai.http.HttpGatewayContext;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.HttpGatewayRequest;
import com.jimeng.dataserver.ai.http.HttpGatewayResult;
import com.jimeng.dataserver.ai.http.tool.HttpToolInput;
import com.jimeng.dataserver.ai.http.tool.HttpToolResults;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 沙箱里两个入口回调网关的地方（设计文档 §4.1 第二、三行）。身份和可用 skill 只取过滤器写进请求属性的那份，
 * 请求体里的任何身份字段一概不看。请求体都由这里有界地读（/script 可达 20 MB，过滤器不缓存）。
 */
@RestController
@RequestMapping("/data/internal/http-gateway")
@RequiredArgsConstructor
public class HttpGatewayCallbackController {

    /** /tool 请求体是 {"input": {...}}，JSON 外壳和请求头的余量。 */
    private static final int TOOL_ENVELOPE_SLACK = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpGateway gateway;
    private final HttpGatewayProperties props;

    /** 宿主侧 MCP 工具 http_request：参数、返回形状与对话平面完全一致（HttpToolInput / HttpToolResults 两个平面共用）。 */
    @PostMapping("/tool")
    @SuppressWarnings("unchecked")
    public Map<String, Object> tool(HttpServletRequest request) throws IOException {
        HttpGatewayPrincipal p = HttpGatewayTokens.requirePrincipal(request);
        int max = props.getToolRequestMaxBytes() + TOOL_ENVELOPE_SLACK;
        byte[] raw = request.getInputStream().readNBytes(max + 1);
        if (raw.length > max) return HttpToolResults.rejected(413, "bad_request", "参数过大，请缩短 body");
        Map<String, Object> input;
        try {
            Object in = JSON.readValue(raw, Map.class).get("input");
            input = in instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (IOException | RuntimeException e) {
            return HttpToolResults.rejected(400, "bad_request", "请求体不是合法的 JSON");
        }
        HttpToolInput in;
        try {
            in = HttpToolInput.parse(input, props.getToolRequestMaxBytes());
        } catch (IllegalArgumentException e) {
            return HttpToolResults.rejected(400, "bad_request", e.getMessage());
        }
        HttpGatewayContext ctx = new HttpGatewayContext(p.tenantId(), p.agentId(), p.runId(),
                HttpGatewayContext.Plane.TOOL, HttpGatewayTokens.requireGrants(request));
        return HttpToolResults.toModelPayload(gateway.executeTool(ctx, in.toRequest(), in.asText()), false, props.getToolTextMaxChars());
    }

    /** 沙箱脚本的 $JM_HTTP：出网代理把脚本的整个请求封进请求体转过来，这里回一个同样形状的封包。 */
    @PostMapping("/script")
    public void script(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpGatewayPrincipal p = HttpGatewayTokens.requirePrincipal(request);
        HttpEnvelope.Decoded d;
        try {
            d = HttpEnvelope.readRequest(request.getInputStream(), props.getScriptRequestMaxBytes());
        } catch (HttpEnvelope.EnvelopeException e) {
            write(response, HttpGatewayResult.error(400, "bad_request", e.getMessage()));
            return;
        }
        List<Map.Entry<String, String>> headers = d.meta().headers() == null ? List.of() : d.meta().headers().stream()
                .filter(h -> h != null && h.size() == 2 && h.get(0) != null && h.get(1) != null)
                .map(h -> Map.entry(h.get(0), h.get(1)))
                .toList();
        HttpGatewayContext ctx = new HttpGatewayContext(p.tenantId(), p.agentId(), p.runId(),
                HttpGatewayContext.Plane.SCRIPT, HttpGatewayTokens.requireGrants(request));
        write(response, gateway.execute(ctx, new HttpGatewayRequest(d.meta().method(), d.meta().url(), headers, d.body())));
    }

    private static void write(HttpServletResponse response, HttpGatewayResult r) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(HttpEnvelope.CONTENT_TYPE);
        List<List<String>> headers = r.headers().stream().map(h -> List.of(h.getKey(), h.getValue())).toList();
        HttpEnvelope.writeResponse(response.getOutputStream(),
                new HttpEnvelope.ResponseMeta(r.status(), headers, r.truncated(), r.gatewayError(), r.message()), r.body());
    }
}

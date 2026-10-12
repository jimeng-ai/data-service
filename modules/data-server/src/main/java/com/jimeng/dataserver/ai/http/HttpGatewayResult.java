package com.jimeng.dataserver.ai.http;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 网关的结果。{@code gatewayError == null} 表示这是上游的回包（已抹 key、已按上限截断）；否则是网关自己拒绝或失败，
 * body 是 {@code {"gateway_error": "...", "message": "..."}}，脚本拿到的就是它。
 */
public record HttpGatewayResult(int status, List<Map.Entry<String, String>> headers, byte[] body, boolean truncated,
                                String gatewayError, String message) {

    public HttpGatewayResult {
        headers = headers == null ? List.of() : List.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public static HttpGatewayResult error(int status, String code, String message) {
        String json = "{\"gateway_error\":\"" + new String(JsonStringEncoder.getInstance().quoteAsString(code))
                + "\",\"message\":\"" + new String(JsonStringEncoder.getInstance().quoteAsString(String.valueOf(message))) + "\"}";
        return new HttpGatewayResult(status, List.of(Map.entry("content-type", "application/json; charset=utf-8")),
                json.getBytes(StandardCharsets.UTF_8), false, code, message);
    }

    /** 第一个同名响应头的值（大小写无关）；没有返回 null。 */
    public String header(String name) {
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        }
        return null;
    }

    @Override
    public String toString() {
        return "HttpGatewayResult[" + status + (gatewayError == null ? "" : " " + gatewayError) + ", "
                + body.length + " bytes" + (truncated ? ", truncated" : "") + "]";
    }
}

package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.dataserver.ai.http.HttpGatewayRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** http_request 的参数（设计文档 §4.2）。两个平面共用：对话平面的执行器、沙箱回调的 /tool 接口。 */
public record HttpToolInput(String method, String url, List<Map.Entry<String, String>> headers, byte[] body, boolean asText) {

    /** @throws IllegalArgumentException 文案直接给模型看 */
    public static HttpToolInput parse(Map<String, Object> input, int maxBodyBytes) {
        if (input == null) throw new IllegalArgumentException("缺少参数：method、url 必填");
        Object m = input.get("method");
        if (!(m instanceof String method) || method.isBlank()) throw new IllegalArgumentException("缺少 method（GET、POST 等）");
        Object u = input.get("url");
        if (!(u instanceof String url) || url.isBlank()) throw new IllegalArgumentException("缺少 url（完整网址）");
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        Object h = input.get("headers");
        if (h != null) {
            if (!(h instanceof Map<?, ?> hm)) throw new IllegalArgumentException("headers 必须是对象，键和值都是字符串");
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v)) {
                    throw new IllegalArgumentException("headers 的键和值都必须是字符串");
                }
                headers.add(Map.entry(k, v));
            }
        }
        byte[] body = null;
        Object b = input.get("body");
        if (b != null) {
            if (!(b instanceof String s)) throw new IllegalArgumentException("body 必须是字符串；JSON 也按字符串传");
            body = s.getBytes(StandardCharsets.UTF_8);
            if (body.length > maxBodyBytes) throw new IllegalArgumentException("请求体超过 " + maxBodyBytes / 1024 + " KB 上限");
            boolean hasType = headers.stream().anyMatch(e -> e.getKey().equalsIgnoreCase("content-type"));
            if (!hasType && body.length > 0) {
                String t = s.stripLeading();
                headers.add(Map.entry("Content-Type", t.startsWith("{") || t.startsWith("[")
                        ? "application/json; charset=utf-8" : "text/plain; charset=utf-8"));
            }
        }
        Object a = input.get("as_text");
        if (a != null && !(a instanceof Boolean)) throw new IllegalArgumentException("as_text 必须是布尔值");
        boolean asText = Boolean.TRUE.equals(a);
        return new HttpToolInput(method.strip().toUpperCase(Locale.ROOT), url.strip(), List.copyOf(headers), body, asText);
    }

    public HttpGatewayRequest toRequest() {
        return new HttpGatewayRequest(method, url, headers, body);
    }
}

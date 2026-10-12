package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.dataserver.ai.http.HttpGatewayResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpToolResultsTest {

    private static HttpGatewayResult ok(String contentType, byte[] body, boolean truncated) {
        return new HttpGatewayResult(200, List.of(Map.entry("content-type", contentType), Map.entry("x-request-id", "r1"),
                Map.entry("set-cookie", "s=1"), Map.entry("x-ratelimit-remaining", "9")), body, truncated, null, null);
    }

    @Test
    @DisplayName("文本回包：status、主要响应头、正文；Set-Cookie 这类不给模型")
    void text() {
        Map<String, Object> p = HttpToolResults.toModelPayload(ok("application/json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), false), false, 100);
        assertThat(p).containsEntry("status", 200).containsEntry("body", "{\"a\":1}").doesNotContainKey("truncated");
        @SuppressWarnings("unchecked")
        Map<String, String> h = (Map<String, String>) p.get("headers");
        assertThat(h).containsKeys("content-type", "x-request-id", "x-ratelimit-remaining").doesNotContainKey("set-cookie");
    }

    @Test
    @DisplayName("as_text：HTML 转纯文本；不是 HTML 原样")
    void asText() {
        byte[] html = "<html><head><title>t</title></head><body><script>x()</script><p>你好 &amp; 再见</p></body></html>".getBytes(StandardCharsets.UTF_8);
        assertThat(HttpToolResults.toModelPayload(ok("text/html; charset=utf-8", html, false), true, 100).get("body")).isEqualTo("你好 & 再见");
        assertThat(HttpToolResults.toModelPayload(ok("text/html", html, false), false, 1000).get("body").toString()).contains("<p>");
    }

    @Test
    @DisplayName("二进制只给类型和大小；正文超过字数上限截断并注明；不把代理对劈开")
    void binaryAndClip() {
        Map<String, Object> bin = HttpToolResults.toModelPayload(ok("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0}, false), false, 100);
        assertThat(bin).doesNotContainKey("body");
        assertThat(bin.get("binary")).isEqualTo(Map.of("content_type", "image/png", "bytes", 5));
        Map<String, Object> clipped = HttpToolResults.toModelPayload(ok("text/plain", ("ab" + "😀".repeat(3)).getBytes(StandardCharsets.UTF_8), false), false, 3);
        assertThat(clipped.get("body")).isEqualTo("ab");
        assertThat(clipped).containsEntry("truncated", true).containsKey("note");
        assertThat(HttpToolResults.toModelPayload(ok("text/plain", "x".getBytes(), true), false, 100)).containsEntry("truncated", true);
    }

    @Test
    @DisplayName("网关拒绝：只给 status、gateway_error、message")
    void gatewayError() {
        Map<String, Object> p = HttpToolResults.toModelPayload(HttpGatewayResult.error(403, "denied", "key K 不允许发往 x"), false, 100);
        assertThat(p).containsOnlyKeys("status", "gateway_error", "message").containsEntry("gateway_error", "denied");
    }

    @Test
    void handlesUnknownContentTypeWithoutLossyBinaryDecoding() {
        HttpGatewayResult utf8 = new HttpGatewayResult(200, List.of(), "你好".getBytes(StandardCharsets.UTF_8), false, null, null);
        assertThat(HttpToolResults.toModelPayload(utf8, false, 100)).containsEntry("body", "你好");
        for (byte[] bytes : List.of(new byte[]{'a', 0}, new byte[]{(byte) 0xff})) {
            HttpGatewayResult binary = new HttpGatewayResult(200, List.of(), bytes, false, null, null);
            assertThat(HttpToolResults.toModelPayload(binary, false, 100)).doesNotContainKey("body").containsKey("binary");
        }
        assertThat(HttpToolResults.toModelPayload(utf8, false, 0)).containsEntry("body", "").containsEntry("truncated", true);
    }

}

package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.dataserver.ai.http.HttpGatewayRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolInputTest {

    @Test
    @DisplayName("method / url 必填；headers 必须是字符串到字符串；as_text 默认 false")
    void parses() {
        HttpToolInput in = HttpToolInput.parse(Map.of("method", "get", "url", "https://a.example.com/x",
                "headers", Map.of("Authorization", "Bearer {{K_1}}")), 1024);
        HttpGatewayRequest r = in.toRequest();
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.url()).isEqualTo("https://a.example.com/x");
        assertThat(r.headers()).containsExactly(Map.entry("Authorization", "Bearer {{K_1}}"));
        assertThat(r.body()).isNull();
        assertThat(in.asText()).isFalse();
        assertThatThrownBy(() -> HttpToolInput.parse(Map.of("url", "https://a.example.com"), 1024)).hasMessageContaining("method");
        assertThatThrownBy(() -> HttpToolInput.parse(Map.of("method", "GET"), 1024)).hasMessageContaining("url");
        assertThatThrownBy(() -> HttpToolInput.parse(Map.of("method", "GET", "url", "u", "headers", Map.of("X", 1)), 1024))
                .hasMessageContaining("headers");
    }

    @Test
    @DisplayName("有请求体没写 Content-Type：像 JSON 的补 application/json，否则补 text/plain；写了的不动")
    void defaultContentType() {
        Map<String, Object> m = new HashMap<>(Map.of("method", "POST", "url", "https://a.example.com", "body", "{\"a\":1}"));
        assertThat(HttpToolInput.parse(m, 1024).toRequest().headers())
                .contains(Map.entry("Content-Type", "application/json; charset=utf-8"));
        m.put("body", "a=1");
        assertThat(HttpToolInput.parse(m, 1024).toRequest().headers())
                .contains(Map.entry("Content-Type", "text/plain; charset=utf-8"));
        m.put("headers", Map.of("content-type", "application/x-www-form-urlencoded"));
        assertThat(HttpToolInput.parse(m, 1024).toRequest().headers())
                .containsExactly(Map.entry("content-type", "application/x-www-form-urlencoded"));
        assertThat(new String(HttpToolInput.parse(m, 1024).toRequest().body(), StandardCharsets.UTF_8)).isEqualTo("a=1");
    }

    @Test
    @DisplayName("请求体超过上限在本地就拒绝")
    void bodyTooLarge() {
        assertThatThrownBy(() -> HttpToolInput.parse(Map.of("method", "POST", "url", "https://a.example.com",
                "body", "x".repeat(2000)), 1024)).hasMessageContaining("请求体");
    }

    @Test
    void rejectsMalformedOptionalValuesAndCountsUtf8Bytes() {
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("method", "GET", "url", "u", "headers", List.of()),
                Map.of("method", "GET", "url", "u", "body", Map.of("a", 1)),
                Map.of("method", "GET", "url", "u", "as_text", "true"))) {
            assertThatThrownBy(() -> HttpToolInput.parse(input, 1024)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HttpToolInput.parse(Map.of("method", "POST", "url", "u", "body", "你好"), 5))
                .hasMessageContaining("请求体");
        assertThat(HttpToolInput.parse(Map.of("method", "POST", "url", "u", "body", "你好", "as_text", true), 6).asText()).isTrue();
    }

}

package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.HmacAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HmacAuthApplierTest {

    private HmacAuthApplier applier;

    @BeforeEach
    void setUp() {
        applier = new HmacAuthApplier();
    }

    private RenderedRequest sampleRequest() {
        RenderedRequest req = new RenderedRequest();
        req.setMethod("GET");
        req.setUrl("https://api.example.com/v1/orders");
        return req;
    }

    /** HMAC-SHA256 with known vector: key="secret", input="POST\n/path\n123" → hex matches OpenSSL */
    @Test
    void hmacSha256_knownVector() throws Exception {
        String key = "secret";
        String message = "GET\n/v1/orders";

        // 标准库自己算一遍作为黄金答案
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedHex = HexFormat.of().formatHex(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));

        // applier 跑一遍
        RenderedRequest req = sampleRequest();
        Map<String, Object> creds = Map.of("secret_key", key);
        Map<String, Object> authConfig = Map.of(
                "algorithm", "HMAC_SHA256",
                "sign_template", "{method}\n{path}",
                "encoding", "hex",
                "placement", Map.of("type", "header", "name", "X-Sign")
        );
        applier.apply(req, creds, authConfig);

        assertEquals(expectedHex, req.getHeaders().get("X-Sign"));
    }

    @Test
    void hmacSha256_base64Encoding() throws Exception {
        String key = "k";
        String message = "GET\n/v1/orders";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedBase64 = java.util.Base64.getEncoder().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));

        RenderedRequest req = sampleRequest();
        applier.apply(req, Map.of("secret_key", key), Map.of(
                "algorithm", "HMAC_SHA256",
                "sign_template", "{method}\n{path}",
                "encoding", "base64",
                "placement", Map.of("type", "header", "name", "X-Sign")
        ));

        assertEquals(expectedBase64, req.getHeaders().get("X-Sign"));
    }

    @Test
    void hmac_placement_query() {
        RenderedRequest req = sampleRequest();
        applier.apply(req, Map.of("secret_key", "k"), Map.of(
                "algorithm", "HMAC_SHA256",
                "sign_template", "{method}",
                "placement", Map.of("type", "query", "name", "sign")
        ));
        assertNotNull(req.getQuery().get("sign"));
    }
}

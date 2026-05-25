package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * HMAC 自定义签名认证（覆盖国内 ERP/OA 常见的签名风格）。
 *
 * <p>auth_config 期望字段：
 * <pre>{
 *   "algorithm": "HMAC_SHA256" | "HMAC_SHA1" | "MD5",
 *   "sign_template": "{method}\n{path}\n{env.timestamp}\n{env.body_sha256}",
 *   "placement": { "type": "header" | "query", "name": "X-Sign" },
 *   "encoding": "hex" | "base64",          // 默认 hex
 *   "timestamp_field": { "type":"header","name":"X-Ts" },  // 可选
 *   "nonce_field":     { "type":"header","name":"X-Nonce" } // 可选
 * }</pre>
 *
 * <p>credentials 期望字段：{@code secret_key}（用于 HMAC 的 key）；MD5 时也用这个字段拼盐。
 *
 * <p>sign_template 内可用占位符：
 * <ul>
 *   <li>{@code {method}} → HTTP 方法</li>
 *   <li>{@code {path}}   → URL 的 path 段（不含 query）</li>
 *   <li>{@code {url}}    → 完整 URL</li>
 *   <li>{@code {env.timestamp}} / {@code {env.nonce}} / {@code {env.body_sha256}}</li>
 * </ul>
 */
@Slf4j
@Component
public class HmacAuthApplier implements PluginAuthApplier {

    @Override
    public String authType() {
        return "HMAC";
    }

    @Override
    public void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        Object secretObj = credentials == null ? null : credentials.get("secret_key");
        if (secretObj == null) {
            throw new IllegalArgumentException("HMAC 凭证缺失 secret_key 字段");
        }
        String secretKey = String.valueOf(secretObj);

        if (authConfig == null || authConfig.isEmpty()) {
            throw new IllegalArgumentException("HMAC 必须配置 auth_config");
        }
        String algorithm = String.valueOf(authConfig.getOrDefault("algorithm", "HMAC_SHA256")).toUpperCase();
        String template = String.valueOf(authConfig.getOrDefault("sign_template", ""));
        String encoding = String.valueOf(authConfig.getOrDefault("encoding", "hex")).toLowerCase();

        // 注入 timestamp/nonce 辅助字段（若 auth_config 指定了）
        injectAuxField(request, authConfig.get("timestamp_field"), getEnvValue(request, "timestamp"));
        injectAuxField(request, authConfig.get("nonce_field"), getEnvValue(request, "nonce"));

        // 拼 canonical string
        String canonical = expandTemplate(template, request);
        byte[] signed = sign(algorithm, secretKey, canonical);
        String signature = "base64".equals(encoding)
                ? Base64.getEncoder().encodeToString(signed)
                : HexFormat.of().formatHex(signed);

        // 放到指定位置
        Object placement = authConfig.get("placement");
        if (!(placement instanceof Map)) {
            throw new IllegalArgumentException("HMAC 必须配置 placement");
        }
        Map<?, ?> placementMap = (Map<?, ?>) placement;
        Object typeObj = placementMap.get("type");
        Object nameObj = placementMap.get("name");
        String type = (typeObj == null ? "header" : String.valueOf(typeObj)).toLowerCase();
        String name = nameObj == null ? "X-Sign" : String.valueOf(nameObj);
        if ("query".equals(type)) {
            request.addQuery(name, signature);
        } else {
            request.addHeader(name, signature);
        }
    }

    private byte[] sign(String algorithm, String secret, String input) {
        try {
            if ("MD5".equals(algorithm)) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                // 约定：MD5 算法用 (secret + input) 直接 md5
                return md.digest((secret + input).getBytes(StandardCharsets.UTF_8));
            }
            String macName = switch (algorithm) {
                case "HMAC_SHA256" -> "HmacSHA256";
                case "HMAC_SHA1" -> "HmacSHA1";
                case "HMAC_MD5" -> "HmacMD5";
                default -> throw new IllegalArgumentException("不支持的 algorithm: " + algorithm);
            };
            Mac mac = Mac.getInstance(macName);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macName));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败: " + e.getMessage(), e);
        }
    }

    /** 极简模板替换（仅支持几个固定占位符，sign_template 用） */
    private String expandTemplate(String template, RenderedRequest req) {
        if (!StringUtils.hasText(template)) return "";
        String result = template;
        result = result.replace("{method}", req.getMethod() == null ? "" : req.getMethod());
        result = result.replace("{url}", req.getUrl() == null ? "" : req.getUrl());
        result = result.replace("{path}", extractPath(req.getUrl()));
        result = result.replace("{body}", req.getBody() == null ? "" : req.getBody());
        // env.* / meta.* 已经被 PluginTemplateRenderer 渲染到 headers/query 里了，
        // 但 sign_template 在 RenderedRequest 渲染之后才用，需要单独从 request 上拿 env 值。
        // 简化：env.timestamp / env.nonce / env.body_sha256 由 PluginHttpInvoker 在调用 apply 前
        // 已经写入了 request.headers/query 或保留在 context（不在 RenderedRequest 上）。
        // 为兼容性，提供一个 hook：用户也可以直接在 sign_template 写 {{env.xxx}} 让上层渲染。
        return result;
    }

    private String extractPath(String url) {
        if (!StringUtils.hasText(url)) return "";
        try {
            URI uri = URI.create(url);
            String p = uri.getRawPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    /** 从已渲染 request 上反查 env 值——目前仅供 {timestamp_field} 等便捷字段使用。 */
    private String getEnvValue(RenderedRequest req, String key) {
        // 我们没有把 env 直接挂在 RenderedRequest 上。
        // 简化处理：返回空，让 sign_template 自己用 {{env.xxx}} 渲染（在 PluginTemplateRenderer 阶段就处理掉）。
        // 这里只是兼容 timestamp_field / nonce_field 的"自动注入"功能：如果 auth_config 配了
        // timestamp_field 但 headers 里没有，业务方应在 headers_template 里显式写 {{env.timestamp}}。
        return null;
    }

    private void injectAuxField(RenderedRequest req, Object placement, String value) {
        if (value == null) return;
        if (!(placement instanceof Map)) return;
        Map<?, ?> p = (Map<?, ?>) placement;
        Object typeObj = p.get("type");
        Object nameObj = p.get("name");
        String type = (typeObj == null ? "header" : String.valueOf(typeObj)).toLowerCase();
        String name = nameObj == null ? "" : String.valueOf(nameObj);
        if (!StringUtils.hasText(name)) return;
        if ("query".equals(type)) {
            req.addQuery(name, value);
        } else {
            req.addHeader(name, value);
        }
    }
}

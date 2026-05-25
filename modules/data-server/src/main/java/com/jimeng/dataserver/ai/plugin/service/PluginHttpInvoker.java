package com.jimeng.dataserver.ai.plugin.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.auth.PluginAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginError;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 插件 HTTP 调用编排器：
 * <ol>
 *   <li>解 secrets（{@link PluginCredentialService}）</li>
 *   <li>构造 {@link PluginExecutionContext}</li>
 *   <li>渲染请求（{@link PluginTemplateRenderer}）</li>
 *   <li>注入认证（{@link PluginAuthApplier}）</li>
 *   <li>调 HTTP（OkHttp）</li>
 *   <li>抽取响应（{@link PluginResponseExtractor}）</li>
 *   <li>异常一律包成 {@link PluginError} 返回，让 LLM 看得懂</li>
 * </ol>
 */
@Slf4j
@Service
public class PluginHttpInvoker {

    private final OkHttpClient defaultHttpClient;
    private final PluginCredentialService credentialService;
    private final PluginTemplateRenderer templateRenderer;
    private final PluginResponseExtractor responseExtractor;
    private final Map<String, PluginAuthApplier> appliersByType;

    @Autowired
    public PluginHttpInvoker(OkHttpClient defaultHttpClient,
                              PluginCredentialService credentialService,
                              PluginTemplateRenderer templateRenderer,
                              PluginResponseExtractor responseExtractor,
                              List<PluginAuthApplier> appliers) {
        this.defaultHttpClient = defaultHttpClient;
        this.credentialService = credentialService;
        this.templateRenderer = templateRenderer;
        this.responseExtractor = responseExtractor;
        Map<String, PluginAuthApplier> map = new LinkedHashMap<>();
        if (appliers != null) {
            for (PluginAuthApplier a : appliers) {
                if (a != null && StringUtils.hasText(a.authType())) {
                    map.put(a.authType().toUpperCase(), a);
                }
            }
        }
        this.appliersByType = map;
    }

    /**
     * 入口：执行一个工具调用。永不抛异常，结果一律可 JSON 序列化返给 LLM。
     */
    public Object invoke(PluginToolEntry entry, Map<String, Object> input, String credentialAlias) {
        if (entry == null) {
            return PluginError.of(PluginError.CODE_CONFIG_INVALID, "PluginToolEntry 为空").toMap();
        }

        // 1. 解 secrets
        Map<String, Object> secrets;
        try {
            secrets = needAuth(entry)
                    ? credentialService.resolveSecrets(entry.getPlugin().getId(), credentialAlias)
                    : new LinkedHashMap<>();
        } catch (PluginCredentialService.CredentialMissingException e) {
            log.warn("凭证解析失败: tool={}, error={}", entry.toolName(), e.getMessage());
            return PluginError.of(PluginError.CODE_CREDENTIAL_MISSING, e.getMessage()).toMap();
        }

        // 2. 构造上下文
        PluginExecutionContext ctx = new PluginExecutionContext(
                entry.tenantId(),
                input == null ? new LinkedHashMap<>() : input,
                secrets,
                buildEnv(),
                buildMeta()
        );

        // 3. 渲染
        RenderedRequest req;
        try {
            req = templateRenderer.render(entry.getMapping(), ctx);
        } catch (PluginTemplateRenderer.TemplateRenderException e) {
            log.warn("模板渲染失败: tool={}, error={}", entry.toolName(), e.getMessage());
            return PluginError.of(PluginError.CODE_TEMPLATE_ERROR, e.getMessage()).toMap();
        }

        // 4. 认证
        if (needAuth(entry)) {
            String authType = entry.getPlugin().getAuthType().toUpperCase();
            PluginAuthApplier applier = appliersByType.get(authType);
            if (applier == null) {
                return PluginError.of(PluginError.CODE_CONFIG_INVALID,
                        "未注册的 auth_type: " + authType).toMap();
            }
            Map<String, Object> authConfig = parseJsonMapOrEmpty(entry.getPlugin().getAuthConfig());
            // env 里塞 body_sha256：HMAC 签名时常用，这里懒求值放到 env
            ctx.getEnv().put("body_sha256", sha256Hex(req.getBody()));
            try {
                applier.apply(req, secrets, authConfig);
            } catch (Exception e) {
                log.warn("认证注入失败: tool={}, type={}, error={}", entry.toolName(), authType, e.getMessage());
                return PluginError.of(PluginError.CODE_AUTH_FAILED, e.getMessage()).toMap();
            }
        }

        // 5. 调 HTTP
        Response resp = null;
        try {
            Request okRequest = buildOkRequest(req);
            OkHttpClient client = clientFor(entry.getMapping().getTimeoutMs());
            resp = client.newCall(okRequest).execute();
            int status = resp.code();
            String body = resp.body() == null ? null : resp.body().string();

            if (status >= 400) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", status);
                details.put("body", truncate(body, 1000));
                String code = status >= 500 ? PluginError.CODE_HTTP_5XX : PluginError.CODE_HTTP_4XX;
                return PluginError.of(code, "HTTP " + status, details).toMap();
            }

            // 6. 抽取响应
            return responseExtractor.extract(body, entry.getMapping());
        } catch (SocketTimeoutException e) {
            return PluginError.of(PluginError.CODE_TIMEOUT, e.getMessage()).toMap();
        } catch (IOException e) {
            return PluginError.of(PluginError.CODE_NETWORK, e.getMessage()).toMap();
        } catch (Exception e) {
            log.warn("插件调用未预期异常: tool={}, error={}", entry.toolName(), e.getMessage(), e);
            return PluginError.of(PluginError.CODE_UNKNOWN, e.getMessage()).toMap();
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean needAuth(PluginToolEntry entry) {
        String type = entry.getPlugin().getAuthType();
        return StringUtils.hasText(type) && !"NONE".equalsIgnoreCase(type);
    }

    private Request buildOkRequest(RenderedRequest req) {
        String url = appendQuery(req.getUrl(), req.getQuery());
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : req.getHeaders().entrySet()) {
            builder.addHeader(e.getKey(), e.getValue());
        }

        String method = req.getMethod() == null ? "GET" : req.getMethod().toUpperCase();
        if ("GET".equals(method) || "DELETE".equals(method) || "HEAD".equals(method)) {
            // 通常 GET/DELETE 不带 body；DELETE 允许带 body 但 OkHttp 需要显式
            if ("DELETE".equals(method) && StringUtils.hasText(req.getBody())) {
                builder.method("DELETE", okBody(req));
            } else {
                builder.method(method, null);
            }
        } else {
            builder.method(method, okBody(req));
        }
        return builder.build();
    }

    private RequestBody okBody(RenderedRequest req) {
        if (!StringUtils.hasText(req.getBody())) {
            return RequestBody.create(new byte[0], MediaType.parse(req.getContentType()));
        }
        return RequestBody.create(req.getBody(), MediaType.parse(req.getContentType()));
    }

    private String appendQuery(String url, Map<String, Object> query) {
        if (query == null || query.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private OkHttpClient clientFor(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) return defaultHttpClient;
        return defaultHttpClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    private Map<String, Object> buildEnv() {
        Map<String, Object> env = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        env.put("timestamp", String.valueOf(now / 1000));
        env.put("timestamp_ms", String.valueOf(now));
        env.put("nonce", UUID.randomUUID().toString().replace("-", ""));
        env.put("uuid", UUID.randomUUID().toString());
        // body_sha256 由 PluginHttpInvoker 在认证前补进来
        return env;
    }

    private Map<String, Object> buildMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenant_id", Optional.ofNullable(TenantContext.get()).orElse(""));
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest httpReq = attrs.getRequest();
                String userId = httpReq.getHeader("user-id");
                String traceId = httpReq.getHeader("trace-id");
                if (userId != null) meta.put("user_id", userId);
                if (traceId != null) meta.put("trace_id", traceId);
            }
        } catch (Exception ignored) {
            // 非 web 上下文（例如定时任务）下取不到，正常
        }
        return meta;
    }

    private Map<String, Object> parseJsonMapOrEmpty(String json) {
        if (!StringUtils.hasText(json)) return new LinkedHashMap<>();
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("auth_config JSON 解析失败, json={}, error={}", json, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String sha256Hex(String input) {
        if (input == null) input = "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(" + (s.length() - max) + " more)";
    }
}

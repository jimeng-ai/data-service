package com.jimeng.dataserver.ai.plugin.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.auth.PluginAuthApplier;
import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
    public PluginHttpInvoker(@Qualifier("pluginHttpClient") OkHttpClient defaultHttpClient,
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
    public Object invoke(PluginToolEntry entry, Map<String, Object> input) {
        return doInvoke(entry, input, false).result;
    }

    /**
     * 试调用专用：除抽取结果外，还回传渲染后的真实请求与等价 curl，便于在控制台核对。
     * 注意：headers 含认证后的真实 token，仅供有权限的插件管理者调试用。
     */
    public Map<String, Object> invokeForTest(PluginToolEntry entry, Map<String, Object> input) {
        // 试调用走宽松渲染：缺参不抛错，留可见标记，保证「无论是否报错都能看到 curl」
        InvokeOutcome o = doInvoke(entry, input, true);
        Map<String, Object> envelope = new LinkedHashMap<>();
        if (o.request != null) {
            String finalUrl = appendQuery(o.request.getUrl(), o.request.getQuery());
            Map<String, Object> reqMap = new LinkedHashMap<>();
            reqMap.put("method", o.request.getMethod());
            reqMap.put("url", finalUrl);
            reqMap.put("headers", o.request.getHeaders());
            if (StringUtils.hasText(o.request.getBody())) {
                reqMap.put("body", o.request.getBody());
            }
            envelope.put("request", reqMap);
            envelope.put("curl", toCurl(o.request.getMethod(), finalUrl,
                    o.request.getHeaders(), o.request.getBody()));
        }
        if (o.status != null) envelope.put("status", o.status);
        if (o.rawBody != null) envelope.put("response", truncate(o.rawBody, 10000));
        envelope.put("extracted", o.result);
        return envelope;
    }

    /** 渲染 + 调用的完整过程，捕获中间产物（请求/状态/原始响应）供试调用回显。 */
    private InvokeOutcome doInvoke(PluginToolEntry entry, Map<String, Object> input, boolean lenient) {
        InvokeOutcome out = new InvokeOutcome();
        if (entry == null) {
            out.result = PluginError.of(PluginError.CODE_CONFIG_INVALID, "PluginToolEntry 为空").toMap();
            return out;
        }

        // 1. 解 secrets（缺失不立即返回：先用空 secrets 把请求渲染出来，让试调用仍能回显 curl，
        //    再在渲染后带着 out.request 返回凭证缺失错误）
        Map<String, Object> secrets;
        Map<String, Object> deferredError = null;
        try {
            secrets = needAuth(entry)
                    ? credentialService.resolveSecrets(entry.getPlugin().getId())
                    : new LinkedHashMap<>();
        } catch (PluginCredentialService.CredentialMissingException e) {
            log.warn("凭证解析失败: tool={}, error={}", entry.toolName(), e.getMessage());
            secrets = new LinkedHashMap<>();
            deferredError = PluginError.of(PluginError.CODE_CREDENTIAL_MISSING, e.getMessage()).toMap();
        }

        // 2. 构造上下文
        PluginExecutionContext ctx = new PluginExecutionContext(
                entry.tenantId(),
                input == null ? new LinkedHashMap<>() : input,
                secrets,
                buildEnv(),
                buildMeta()
        );
        ctx.setLenient(lenient);

        // 3. 渲染
        RenderedRequest req;
        try {
            req = templateRenderer.render(entry.getMapping(), ctx);
        } catch (PluginTemplateRenderer.TemplateRenderException e) {
            log.warn("模板渲染失败: tool={}, error={}", entry.toolName(), e.getMessage());
            out.result = PluginError.of(PluginError.CODE_TEMPLATE_ERROR, e.getMessage()).toMap();
            return out;
        }
        // 工具 urlTemplate 只写路径时，域名取插件基础信息的 baseUrl；已是绝对地址则原样用
        req.setUrl(resolveUrl(entry.getPlugin().getBaseUrl(), req.getUrl()));
        out.request = req;

        // 凭证缺失：请求已渲染（curl 可回显），到这里再返回错误，不真正发请求
        if (deferredError != null) {
            out.result = deferredError;
            return out;
        }

        // 4. 认证（applier/authConfig 提到方法作用域，供 401 兜底重试复用）
        PluginAuthApplier applier = null;
        Map<String, Object> authConfig = null;
        if (needAuth(entry)) {
            String authType = entry.getPlugin().getAuthType().toUpperCase();
            applier = appliersByType.get(authType);
            if (applier == null) {
                out.result = PluginError.of(PluginError.CODE_CONFIG_INVALID,
                        "未注册的 auth_type: " + authType).toMap();
                return out;
            }
            authConfig = parseJsonMapOrEmpty(entry.getPlugin().getAuthConfig());
            try {
                applyAuth(applier, req, ctx, secrets, entry.getPlugin().getId(), authConfig);
            } catch (Exception e) {
                log.warn("认证注入失败: tool={}, type={}, error={}", entry.toolName(), authType, e.getMessage());
                out.result = authError(e).toMap();
                return out;
            }
        }

        // 5. 调 HTTP
        Response resp = null;
        try {
            OkHttpClient client = clientFor(entry.getMapping().getTimeoutMs());
            Request okRequest = buildOkRequest(req);
            resp = client.newCall(okRequest).execute();
            int status = resp.code();
            String body = resp.body() == null ? null : resp.body().string();
            out.status = status;
            out.rawBody = body;

            // 业务接口 401 + token-caching 鉴权 → 作废缓存、重渲染、重注入、重试一次（最多一次，无循环）
            if (status == 401 && applier instanceof TokenCachingAuthApplier tca) {
                resp.close();
                tca.invalidate(ctx, entry.getPlugin().getId(), authConfig);
                req = templateRenderer.render(entry.getMapping(), ctx);
                req.setUrl(resolveUrl(entry.getPlugin().getBaseUrl(), req.getUrl()));
                out.request = req;
                tca.applyWithContext(req, ctx, entry.getPlugin().getId(), authConfig);
                okRequest = buildOkRequest(req);
                resp = client.newCall(okRequest).execute();
                status = resp.code();
                body = resp.body() == null ? null : resp.body().string();
                out.status = status;
                out.rawBody = body;
            }

            if (status >= 400) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", status);
                details.put("body", truncate(body, 1000));
                String code = status >= 500 ? PluginError.CODE_HTTP_5XX : PluginError.CODE_HTTP_4XX;
                out.result = PluginError.of(code, "HTTP " + status, details).toMap();
                return out;
            }

            // 6. 抽取响应
            out.result = responseExtractor.extract(body, entry.getMapping());
            return out;
        } catch (SocketTimeoutException e) {
            out.result = PluginError.of(PluginError.CODE_TIMEOUT, e.getMessage()).toMap();
            return out;
        } catch (IOException e) {
            out.result = PluginError.of(PluginError.CODE_NETWORK, e.getMessage()).toMap();
            return out;
        } catch (Exception e) {
            log.warn("插件调用未预期异常: tool={}, error={}", entry.toolName(), e.getMessage(), e);
            out.result = PluginError.of(PluginError.CODE_UNKNOWN, e.getMessage()).toMap();
            return out;
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    /** doInvoke 的中间产物载体 */
    private static class InvokeOutcome {
        private RenderedRequest request;
        private Integer status;
        private String rawBody;
        private Object result;
    }

    /** 渲染后的请求转成等价 curl 命令（单引号转义，便于直接粘到终端复现） */
    private String toCurl(String method, String url, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder("curl -X ")
                .append(method == null ? "GET" : method.toUpperCase());
        sb.append(" '").append(shellQuote(url)).append("'");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(" \\\n  -H '").append(e.getKey()).append(": ")
                  .append(shellQuote(e.getValue())).append("'");
            }
        }
        if (StringUtils.hasText(body)) {
            sb.append(" \\\n  -d '").append(shellQuote(body)).append("'");
        }
        return sb.toString();
    }

    private String shellQuote(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    /** 认证注入：token-caching 走带上下文入口（取/缓存 token），其余 applier 走基类 apply。 */
    private void applyAuth(PluginAuthApplier applier, RenderedRequest req, PluginExecutionContext ctx,
                           Map<String, Object> secrets, Long pluginId, Map<String, Object> authConfig) {
        if (applier instanceof TokenCachingAuthApplier tca) {
            tca.applyWithContext(req, ctx, pluginId, authConfig);
        } else {
            // env 里塞 body_sha256：HMAC 签名时常用，这里懒求值放到 env
            ctx.getEnv().put("body_sha256", sha256Hex(req.getBody()));
            applier.apply(req, secrets, authConfig);
        }
    }

    /** 认证异常归类：换 token 失败 / 配置非法 / 其余鉴权失败。 */
    private PluginError authError(Exception e) {
        if (e instanceof PluginTokenProvider.TokenFetchException) {
            return PluginError.of(PluginError.CODE_TOKEN_FETCH_FAILED, e.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return PluginError.of(PluginError.CODE_CONFIG_INVALID, e.getMessage());
        }
        return PluginError.of(PluginError.CODE_AUTH_FAILED, e.getMessage());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 拼接最终请求 URL：
     * <ul>
     *   <li>urlTemplate 已是 http(s) 绝对地址 → 原样使用（向后兼容旧配置）</li>
     *   <li>否则视为路径，前缀插件 baseUrl（去重斜杠）；baseUrl 为空则保持原样</li>
     * </ul>
     */
    private String resolveUrl(String baseUrl, String url) {
        String u = url == null ? "" : url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            return u;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (u.isEmpty()) {
            return base;
        }
        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        return base + u;
    }

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

    /**
     * 插件未显式配置超时时的默认值（毫秒）。避免回落到全局 OkHttp 的 180s read-timeout——
     * 挂死的插件后端不该占住线程/连接长达 3 分钟。阶段 1.2 拆出独立 pluginHttpClient 后可下沉到配置。
     */
    private static final int DEFAULT_PLUGIN_TIMEOUT_MS = 30_000;

    private OkHttpClient clientFor(Integer timeoutMs) {
        int effective = (timeoutMs == null || timeoutMs <= 0) ? DEFAULT_PLUGIN_TIMEOUT_MS : timeoutMs;
        return defaultHttpClient.newBuilder()
                .callTimeout(effective, TimeUnit.MILLISECONDS)
                .readTimeout(effective, TimeUnit.MILLISECONDS)
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

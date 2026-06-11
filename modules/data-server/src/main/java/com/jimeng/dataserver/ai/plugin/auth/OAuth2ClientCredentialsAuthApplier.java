package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 标准 OAuth2 client_credentials（authType=OAUTH2）。
 *
 * <p>凭证：{@code client_id} / {@code client_secret}。
 * auth_config：{@code token_url}（必填）、{@code scope}、{@code client_auth}(body|basic)、
 * {@code default_ttl_sec}、{@code safety_margin_sec}。
 * token 取 {@code $.access_token}，过期取 {@code $.expires_in}，注入 {@code Authorization: Bearer}。
 */
@Component
public class OAuth2ClientCredentialsAuthApplier implements TokenCachingAuthApplier {

    private final PluginTokenProvider provider;

    public OAuth2ClientCredentialsAuthApplier(PluginTokenProvider provider) {
        this.provider = provider;
    }

    @Override
    public String authType() {
        return "OAUTH2";
    }

    @Override
    public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                                 Long pluginId, Map<String, Object> authConfig) {
        TokenFetchSpec spec = buildSpec(authConfig, ctx.getSecrets());
        String cacheKey = provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets());
        String token = provider.resolveToken(spec, ctx, cacheKey);
        req.addHeader("Authorization", "Bearer " + token);
    }

    @Override
    public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig) {
        provider.invalidate(provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets()));
    }

    private TokenFetchSpec buildSpec(Map<String, Object> authConfig, Map<String, Object> secrets) {
        String tokenUrl = str(authConfig.get("token_url"));
        if (!StringUtils.hasText(tokenUrl)) {
            throw new IllegalArgumentException("OAUTH2 缺少 token_url 配置");
        }
        String scope = str(authConfig.get("scope"));
        String clientAuth = StringUtils.hasText(str(authConfig.get("client_auth")))
                ? str(authConfig.get("client_auth")) : "body";

        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(tokenUrl);
        s.setMethod("POST");
        s.setContentType("application/x-www-form-urlencoded");
        s.setTokenPath("$.access_token");
        s.setExpirePath("$.expires_in");
        s.setDefaultTtlSec(num(authConfig.get("default_ttl_sec"), 3600));
        s.setSafetyMarginSec(num(authConfig.get("safety_margin_sec"), 60));

        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        if ("basic".equalsIgnoreCase(clientAuth)) {
            // client_id:client_secret 用真实凭证算 Basic（不进 body）
            String id = str(secrets.get("client_id"));
            String secret = str(secrets.get("client_secret"));
            String basic = Base64.getEncoder().encodeToString(
                    (id + ":" + secret).getBytes(StandardCharsets.UTF_8));
            s.getHeaders().put("Authorization", "Basic " + basic);
        } else {
            // 用 {{secrets.x}} 占位，交给 renderer 渲染（与系统其余部分一致）
            body.append("&client_id={{secrets.client_id}}")
                .append("&client_secret={{secrets.client_secret}}");
        }
        if (StringUtils.hasText(scope)) {
            body.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        s.setBodyTemplate(body.toString());
        return s;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private long num(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? def : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

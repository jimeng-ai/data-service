package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用「登录换 token」（authType=TOKEN_FETCH）：请求/取值/注入全部由 {@code auth_config} 驱动。
 *
 * <p>auth_config 形态见 spec：{@code token_request}(method/url/content_type/headers/body) +
 * {@code token_path}/{@code expire_path}/{@code expire_unit}/{@code default_ttl_sec}/{@code inject}。
 */
@Component
public class GenericTokenAuthApplier implements TokenCachingAuthApplier {

    private final PluginTokenProvider provider;

    public GenericTokenAuthApplier(PluginTokenProvider provider) {
        this.provider = provider;
    }

    @Override
    public String authType() {
        return "TOKEN_FETCH";
    }

    @Override
    public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                                 Long pluginId, Map<String, Object> authConfig) {
        TokenFetchSpec spec = buildSpec(authConfig);
        String cacheKey = provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets());
        String token = provider.resolveToken(spec, ctx, cacheKey);
        inject(req, authConfig, token);
    }

    @Override
    public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig) {
        provider.invalidate(provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets()));
    }

    @SuppressWarnings("unchecked")
    private TokenFetchSpec buildSpec(Map<String, Object> authConfig) {
        Object tr = authConfig == null ? null : authConfig.get("token_request");
        if (!(tr instanceof Map)) {
            throw new IllegalArgumentException("TOKEN_FETCH 缺少 token_request 配置");
        }
        Map<String, Object> req = (Map<String, Object>) tr;
        String url = str(req.get("url"), null);
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("TOKEN_FETCH token_request.url 不能为空");
        }
        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(url);
        s.setMethod(str(req.get("method"), "POST"));
        s.setContentType(str(req.get("content_type"), "application/json"));
        if (req.get("headers") instanceof Map<?, ?> hm) {
            Map<String, String> headers = new LinkedHashMap<>();
            hm.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            s.setHeaders(headers);
        }
        s.setBodyTemplate(str(req.get("body"), null));
        s.setTokenPath(str(authConfig.get("token_path"), "$.access_token"));
        s.setExpirePath(str(authConfig.get("expire_path"), null));
        s.setExpireUnit(str(authConfig.get("expire_unit"), "sec"));
        s.setDefaultTtlSec(num(authConfig.get("default_ttl_sec"), 3600));
        s.setSafetyMarginSec(num(authConfig.get("safety_margin_sec"), 60));
        return s;
    }

    @SuppressWarnings("unchecked")
    private void inject(RenderedRequest req, Map<String, Object> authConfig, String token) {
        String location = "header";
        String name = "Authorization";
        String prefix = "Bearer ";
        Object inj = authConfig.get("inject");
        if (inj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) inj;
            location = str(m.get("location"), location);
            name = str(m.get("name"), name);
            if (m.containsKey("prefix")) {
                prefix = str(m.get("prefix"), "");
            }
        }
        String value = prefix + token;
        if ("query".equalsIgnoreCase(location)) {
            req.addQuery(name, value);
        } else {
            req.addHeader(name, value);
        }
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
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

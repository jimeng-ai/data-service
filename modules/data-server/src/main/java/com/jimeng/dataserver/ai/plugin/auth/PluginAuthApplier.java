package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;

import java.util.Map;

/**
 * 认证策略接口。
 * 实现类在已渲染的 {@link RenderedRequest} 上原位注入 token / 签名 / 凭证头。
 */
public interface PluginAuthApplier {

    /**
     * @return 该 applier 支持的 auth_type，例如 "API_KEY" / "BEARER" / "BASIC" / "HMAC"
     */
    String authType();

    /**
     * 注入认证。
     *
     * @param request     已渲染的请求（method/url/headers/query/body 都已就位）
     * @param credentials 解析后的凭证 Map（如 {"value":"xxx"} / {"username":"u","password":"p"} 等）
     * @param authConfig  插件的非密 auth_config（如 {"location":"header","key_name":"X-API-Key"}）
     */
    void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig);
}

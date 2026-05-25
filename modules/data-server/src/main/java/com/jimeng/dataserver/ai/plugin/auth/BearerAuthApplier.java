package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bearer Token 认证。
 *
 * <p>credentials 期望字段：{@code token}（Bearer Token 字符串）
 */
@Component
public class BearerAuthApplier implements PluginAuthApplier {

    @Override
    public String authType() {
        return "BEARER";
    }

    @Override
    public void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        Object tokenObj = credentials == null ? null : credentials.get("token");
        if (tokenObj == null) {
            throw new IllegalArgumentException("BEARER 凭证缺失 token 字段");
        }
        request.addHeader("Authorization", "Bearer " + tokenObj);
    }
}

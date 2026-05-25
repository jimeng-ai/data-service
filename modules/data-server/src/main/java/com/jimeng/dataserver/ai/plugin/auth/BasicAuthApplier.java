package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Basic Auth 认证。
 *
 * <p>credentials 期望字段：{@code username} + {@code password}
 */
@Component
public class BasicAuthApplier implements PluginAuthApplier {

    @Override
    public String authType() {
        return "BASIC";
    }

    @Override
    public void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        Object userObj = credentials == null ? null : credentials.get("username");
        Object passObj = credentials == null ? null : credentials.get("password");
        if (userObj == null || passObj == null) {
            throw new IllegalArgumentException("BASIC 凭证需要 username + password");
        }
        String token = userObj + ":" + passObj;
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Basic " + encoded);
    }
}

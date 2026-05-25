package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * API Key 认证。
 *
 * <p>auth_config 期望字段：
 * <pre>{
 *   "location": "header" 或 "query",   // 默认 header
 *   "key_name": "X-API-Key"             // 默认 X-API-Key
 * }</pre>
 *
 * <p>credentials 期望字段：{@code value}（API Key 字符串）
 */
@Component
public class ApiKeyAuthApplier implements PluginAuthApplier {

    @Override
    public String authType() {
        return "API_KEY";
    }

    @Override
    public void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        Object valueObj = credentials == null ? null : credentials.get("value");
        if (valueObj == null) {
            throw new IllegalArgumentException("API_KEY 凭证缺失 value 字段");
        }
        String value = String.valueOf(valueObj);

        String location = "header";
        String keyName = "X-API-Key";
        if (authConfig != null) {
            Object loc = authConfig.get("location");
            if (loc != null && StringUtils.hasText(loc.toString())) {
                location = loc.toString().toLowerCase();
            }
            Object kn = authConfig.get("key_name");
            if (kn != null && StringUtils.hasText(kn.toString())) {
                keyName = kn.toString();
            }
        }

        if ("query".equals(location)) {
            request.addQuery(keyName, value);
        } else {
            request.addHeader(keyName, value);
        }
    }
}

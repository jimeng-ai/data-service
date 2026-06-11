package com.jimeng.dataserver.ai.plugin.dto;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件执行错误的结构化结果——直接返给 LLM 让模型自己决定下一步（重试/换参/报错）。
 *
 * <p>序列化后 LLM 看到的形态：
 * <pre>{
 *   "_error": true,
 *   "code": "HTTP_4XX",
 *   "message": "...",
 *   "details": { ... }
 * }</pre>
 */
@Getter
public class PluginError {

    public static final String CODE_HTTP_4XX = "HTTP_4XX";
    public static final String CODE_HTTP_5XX = "HTTP_5XX";
    public static final String CODE_TIMEOUT = "TIMEOUT";
    public static final String CODE_NETWORK = "NETWORK_ERROR";
    public static final String CODE_AUTH_FAILED = "AUTH_FAILED";
    public static final String CODE_TOKEN_FETCH_FAILED = "TOKEN_FETCH_FAILED";
    public static final String CODE_TEMPLATE_ERROR = "TEMPLATE_ERROR";
    public static final String CODE_CREDENTIAL_MISSING = "CREDENTIAL_MISSING";
    public static final String CODE_CONFIG_INVALID = "CONFIG_INVALID";
    public static final String CODE_UNKNOWN = "UNKNOWN";

    private final String code;
    private final String message;
    private final Map<String, Object> details;

    private PluginError(String code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = details == null ? new LinkedHashMap<>() : details;
    }

    public static PluginError of(String code, String message) {
        return new PluginError(code, message, null);
    }

    public static PluginError of(String code, String message, Map<String, Object> details) {
        return new PluginError(code, message, details);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_error", Boolean.TRUE);
        map.put("code", code);
        map.put("message", message);
        if (details != null && !details.isEmpty()) {
            map.put("details", details);
        }
        return map;
    }
}

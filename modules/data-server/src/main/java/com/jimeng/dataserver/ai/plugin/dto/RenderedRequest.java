package com.jimeng.dataserver.ai.plugin.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模板渲染后的 HTTP 请求载体。
 * 认证策略 {@code PluginAuthApplier} 会原位修改这个对象（往 headers/query 加 token、签名等）。
 */
@Data
public class RenderedRequest {

    private String method;
    private String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, Object> query = new LinkedHashMap<>();
    private String body;
    private String contentType = "application/json";

    public void addHeader(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, value);
        }
    }

    public void addQuery(String name, Object value) {
        if (name != null && value != null) {
            query.put(name, value);
        }
    }
}

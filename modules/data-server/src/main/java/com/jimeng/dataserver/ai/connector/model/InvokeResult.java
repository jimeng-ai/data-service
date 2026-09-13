package com.jimeng.dataserver.ai.connector.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次「调用接口」的结果。
 *
 * @param statusCode 上游状态码
 * @param body       响应体（可能已截断）
 * @param truncated  是否截断
 * @param elapsedMs  耗时
 */
public record InvokeResult(int statusCode, String body, boolean truncated, long elapsedMs) {

    public Map<String, Object> toModelPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", statusCode);
        out.put("body", body);
        out.put("elapsed_ms", elapsedMs);
        out.put("truncated", truncated);
        if (truncated) {
            out.put("warning", "响应体过大已被截断，内容不完整。");
        }
        return out;
    }
}

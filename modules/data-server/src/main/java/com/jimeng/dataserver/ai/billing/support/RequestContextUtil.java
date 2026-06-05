package com.jimeng.dataserver.ai.billing.support;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取当前 HTTP 请求上下文 + 请求头脱敏的纯工具。
 * 注意：流式问答在异步线程里落库，此时已无 HTTP 请求上下文，{@link #currentRequest()}
 * 会返回 null，调用方需准备好兜底来源（如 TenantContext）。
 * 从 {@link com.jimeng.dataserver.ai.billing.AiModelCallRecordService} 抽出（阶段 3.1）。
 */
@Slf4j
public final class RequestContextUtil {

    private RequestContextUtil() {
    }

    /** 当前线程绑定的 HTTP 请求；无请求上下文（如异步线程）返回 null，不抛异常。 */
    public static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes == null ? null : attributes.getRequest();
        } catch (Exception e) {
            log.warn("读取当前请求上下文失败: {}", e.getMessage());
            return null;
        }
    }

    /** 取请求头；request 或 key 为空时返回 null。 */
    public static String getHeader(HttpServletRequest request, String key) {
        if (request == null || StrUtil.isBlank(key)) {
            return null;
        }
        return request.getHeader(key);
    }

    /** 复制一份请求头并对 authorization / x-api-key 做脱敏，用于落库展示。 */
    public static Map<String, String> maskSensitiveHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (k == null) {
                return;
            }
            String key = k.toLowerCase();
            if ("authorization".equals(key) || "x-api-key".equals(key)) {
                sanitized.put(k, maskValue(v));
            } else {
                sanitized.put(k, v);
            }
        });
        return sanitized;
    }

    /** 保留头尾、中间打码；过短直接 {@code ****}。 */
    public static String maskValue(String value) {
        if (StrUtil.isBlank(value) || value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }
}

package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.persistence.entity.AiModelCallContent;
import com.jimeng.persistence.entity.AiModelCallLog;
import com.jimeng.persistence.mapper.AiModelCallContentMapper;
import com.jimeng.persistence.mapper.AiModelCallLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiModelCallRecordService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILED = 2;
    private static final int MAX_ERROR_MSG_LEN = 1000;

    private final AiModelCallLogMapper aiModelCallLogMapper;
    private final AiModelCallContentMapper aiModelCallContentMapper;

    public Long recordRequest(Map<String, Object> requestBody, Map<String, String> requestHeaders) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setProvider("anthropic");
        logEntity.setEndpoint("/v1/messages");
        logEntity.setModel(getString(requestBody, "model", "claude-opus-4-6"));
        logEntity.setStream(getBoolean(requestBody, "stream"));
        logEntity.setMaxTokens(getInteger(requestBody, "max_tokens"));
        logEntity.setTemperature(getDecimal(requestBody, "temperature"));
        logEntity.setTopP(getDecimal(requestBody, "top_p"));
        logEntity.setRetryCount(0);
        logEntity.setCallStatus(STATUS_PENDING);

        // 业务字段（可选）
        logEntity.setBizType(getString(requestBody, "biz_type", null));
        logEntity.setBizId(getString(requestBody, "biz_id", null));
        logEntity.setSceneCode(getString(requestBody, "scene_code", null));

        // 从请求头/上下文提取链路和用户信息
        HttpServletRequest request = currentRequest();
        logEntity.setTraceId(firstNonBlank(
                getHeader(request, "trace-id"),
                getHeader(request, "x-trace-id"),
                getString(requestHeaders, "trace-id", null),
                getString(requestHeaders, "x-trace-id", null)
        ));
        logEntity.setTenantId(firstNonBlank(
                getString(requestBody, "tenant_id", null),
                getHeader(request, "tenant-id"),
                getHeader(request, "x-tenant-id")
        ));
        logEntity.setUserId(firstNonBlank(
                getString(requestBody, "user_id", null),
                getHeader(request, "user-id"),
                getHeader(request, "x-user-id")
        ));

        List<String> toolNames = extractToolNames(requestBody);
        logEntity.setHasTool(!toolNames.isEmpty());
        logEntity.setToolNames(toolNames.isEmpty() ? null : String.join(",", toolNames));

        ContentFlags flags = extractContentFlags(requestBody);
        logEntity.setHasText(flags.hasText);
        logEntity.setHasImage(flags.hasImage);
        logEntity.setHasDocument(flags.hasDocument);

        aiModelCallLogMapper.insert(logEntity);

        AiModelCallContent contentEntity = new AiModelCallContent();
        contentEntity.setLogId(logEntity.getId());
        contentEntity.setReqHeaders(JSONUtil.toJsonStr(maskSensitiveHeaders(requestHeaders)));
        contentEntity.setReqBody(JSONUtil.toJsonStr(requestBody));
        aiModelCallContentMapper.insert(contentEntity);

        return logEntity.getId();
    }

    public void recordResponse(Long logId, Integer httpStatus, String responseBody, Integer latencyMs) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setHttpStatus(httpStatus);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setCallStatus(isSuccess(httpStatus) ? STATUS_SUCCESS : STATUS_FAILED);

        if (StrUtil.isNotBlank(responseBody) && JSONUtil.isTypeJSON(responseBody)) {
            JSONObject root = JSONUtil.parseObj(responseBody);
            logEntity.setRequestId(root.getStr("id"));
            fillUsage(logEntity, root.getJSONObject("usage"));

            JSONObject error = root.getJSONObject("error");
            if (error != null) {
                logEntity.setErrorCode(error.getStr("type"));
                logEntity.setErrorMsg(limit(error.getStr("message"), MAX_ERROR_MSG_LEN));
                logEntity.setCallStatus(STATUS_FAILED);
            }
        } else if (!isSuccess(httpStatus)) {
            logEntity.setErrorMsg(limit(responseBody, MAX_ERROR_MSG_LEN));
        }
        aiModelCallLogMapper.updateById(logEntity);

        boolean stream = Boolean.TRUE.equals(getStreamById(logId));
        LambdaUpdateWrapper<AiModelCallContent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AiModelCallContent::getLogId, logId);
        if (stream) {
            wrapper.set(AiModelCallContent::getStreamEvents, responseBody);
        } else {
            wrapper.set(AiModelCallContent::getRespBody, responseBody);
        }
        aiModelCallContentMapper.update(null, wrapper);
    }

    /**
     * 记录流式响应结果。直接使用累积器提供的 token 用量，
     * 将流事件 JSON 存入 content 表的 stream_events 字段。
     *
     * @param logId            日志记录 ID
     * @param httpStatus       HTTP 状态码
     * @param inputTokens      输入 token 数
     * @param outputTokens     输出 token 数
     * @param streamEventsJson 流事件 JSON 字符串
     * @param latencyMs        延迟毫秒数
     */
    public void recordStreamResponse(Long logId, Integer httpStatus,
                                     int inputTokens, int outputTokens,
                                     String streamEventsJson, Integer latencyMs,
                                     String requestId) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setHttpStatus(httpStatus);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setCallStatus(isSuccess(httpStatus) ? STATUS_SUCCESS : STATUS_FAILED);
        logEntity.setInputTokens(inputTokens);
        logEntity.setOutputTokens(outputTokens);
        logEntity.setTotalTokens(inputTokens + outputTokens);
        if (StrUtil.isNotBlank(requestId)) {
            logEntity.setRequestId(requestId);
        }
        aiModelCallLogMapper.updateById(logEntity);

        LambdaUpdateWrapper<AiModelCallContent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AiModelCallContent::getLogId, logId);
        wrapper.set(AiModelCallContent::getStreamEvents, streamEventsJson);
        aiModelCallContentMapper.update(null, wrapper);
    }

    public void recordException(Long logId, Throwable throwable, Integer latencyMs) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setCallStatus(STATUS_FAILED);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setErrorCode(throwable == null ? "UNKNOWN_ERROR" : throwable.getClass().getSimpleName());
        logEntity.setErrorMsg(limit(throwable == null ? "未知异常" : throwable.getMessage(), MAX_ERROR_MSG_LEN));
        aiModelCallLogMapper.updateById(logEntity);
    }

    private void fillUsage(AiModelCallLog logEntity, JSONObject usage) {
        if (usage == null) {
            return;
        }
        Integer input = usage.getInt("input_tokens");
        Integer output = usage.getInt("output_tokens");
        logEntity.setInputTokens(input);
        logEntity.setOutputTokens(output);
        if (input != null || output != null) {
            logEntity.setTotalTokens((input == null ? 0 : input) + (output == null ? 0 : output));
        }
    }

    private List<String> extractToolNames(Map<String, Object> requestBody) {
        Object toolsObj = requestBody.get("tools");
        if (!(toolsObj instanceof List<?> tools) || tools.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object toolObj : tools) {
            if (!(toolObj instanceof Map<?, ?> toolMap)) {
                continue;
            }
            Object nameObj = toolMap.get("name");
            if (nameObj != null && StrUtil.isNotBlank(String.valueOf(nameObj))) {
                names.add(String.valueOf(nameObj));
            }
        }
        return names;
    }

    private ContentFlags extractContentFlags(Map<String, Object> requestBody) {
        ContentFlags flags = new ContentFlags();
        Object messagesObj = requestBody.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return flags;
        }
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> messageMap)) {
                continue;
            }
            Object contentObj = messageMap.get("content");
            if (contentObj instanceof String) {
                flags.hasText = true;
                continue;
            }
            if (contentObj instanceof List<?> contentList) {
                for (Object blockObj : contentList) {
                    if (!(blockObj instanceof Map<?, ?> blockMap)) {
                        continue;
                    }
                    String type = blockMap.get("type") == null ? null : String.valueOf(blockMap.get("type"));
                    if ("text".equals(type)) {
                        flags.hasText = true;
                    } else if ("image".equals(type)) {
                        flags.hasImage = true;
                    } else if ("document".equals(type)) {
                        flags.hasDocument = true;
                    }
                }
            }
        }
        return flags;
    }

    private Boolean getStreamById(Long logId) {
        AiModelCallLog entity = aiModelCallLogMapper.selectById(logId);
        return entity == null ? null : entity.getStream();
    }

    private boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    private String getHeader(HttpServletRequest request, String key) {
        if (request == null || StrUtil.isBlank(key)) {
            return null;
        }
        return request.getHeader(key);
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes == null ? null : attributes.getRequest();
        } catch (Exception e) {
            log.warn("读取当前请求上下文失败: {}", e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String limit(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private String getString(Map<String, ?> map, String key, String defaultValue) {
        if (map == null || StrUtil.isBlank(key)) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return StrUtil.isBlank(text) ? defaultValue : text;
    }

    private Integer getInteger(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (StrUtil.isNotBlank(String.valueOf(value))) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception e) {
                log.warn("整数解析失败, key={}, value={}", key, value);
            }
        }
        return null;
    }

    private BigDecimal getDecimal(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (StrUtil.isNotBlank(String.valueOf(value))) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (Exception e) {
                log.warn("小数解析失败, key={}, value={}", key, value);
            }
        }
        return null;
    }

    private Boolean getBoolean(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return false;
        }
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, String> maskSensitiveHeaders(Map<String, String> headers) {
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

    private String maskValue(String value) {
        if (StrUtil.isBlank(value) || value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    private static class ContentFlags {
        private boolean hasText = false;
        private boolean hasImage = false;
        private boolean hasDocument = false;
    }
}

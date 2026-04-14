package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeService {

    @Value("${ai-api.claude.api-key}")
    private String apiKey;

    @Value("${ai-api.claude.base-Url}")
    private String baseUrl;

    @Value("${ai-api.claude.model}")
    private String defaultModel;

    @Value("${ai-api.claude.max-tokens}")
    private Integer defaultMaxTokens;

    @Value("${skill.max-tool-rounds}")
    private Integer maxToolRounds;

    @Value("${ai.system-prompt}")
    private String systemPrompt;

    private final RequestService requestService;
    private final AiModelCallRecordService aiModelCallRecordService;
    private final SkillRuntimeService skillRuntimeService;

    public Object messages(Map<String, Object> requestBody) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未配置ai-api.claude.api-key");
        }

        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        normalizeMaxTokens(body);
        body.putIfAbsent("system", systemPrompt);
        body.putIfAbsent("model", defaultModel);
        body.putIfAbsent("max_tokens", defaultMaxTokens);

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }

        SkillApplyResult skillApplyResult = skillRuntimeService.applySkillContext(body);
        if (skillApplyResult.isEnabled()) {
            log.info("启用skills: {}", skillApplyResult.getSelectedSkillNames());
        }

        Map<String, String> headers = buildHeaders();
        String url = buildMessagesUrl();
        int toolRound = 0;

        // 多轮 token 累加变量
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        // 提取 trace-id
        String traceId = extractTraceId();

        while (true) {
            long roundStart = System.currentTimeMillis();
            Long logId = safeRecordRequest(body, headers);
            try {
                RequestService.HttpResp httpResp = requestService.post(url, headers, Collections.emptyMap(), body);
                Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                safeRecordResponse(logId, httpResp.getStatusCode(), httpResp.getBody(), latencyMs);
                log.info("模型接口返回: {}", httpResp.getBody());

                Object parsedResponse = tryParseJson(httpResp.getBody());
                if (!isSuccessStatus(httpResp.getStatusCode()) || !skillApplyResult.isEnabled()) {
                    return parsedResponse;
                }

                Map<String, Object> responseMap = parseResponseMap(httpResp.getBody());
                if (responseMap == null || responseMap.isEmpty()) {
                    return parsedResponse;
                }

                // 累加本轮 token 用量
                int[] accumulated = accumulateUsageResult(responseMap, totalInputTokens, totalOutputTokens);
                totalInputTokens = accumulated[0];
                totalOutputTokens = accumulated[1];

                List<ToolUseCall> toolCalls = skillRuntimeService.extractToolUseCalls(responseMap);
                if (toolCalls.isEmpty()) {
                    // 多轮调用结束，注入聚合信息
                    if (toolRound > 0) {
                        return buildAggregatedResponse(responseMap, totalInputTokens, totalOutputTokens, toolRound + 1, traceId);
                    }
                    return parsedResponse;
                }
                if (toolRound >= Math.max(maxToolRounds, 1)) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool调用轮次超过限制");
                }

                List<Map<String, Object>> toolResultBlocks = skillRuntimeService.buildToolResultBlocks(toolCalls);
                if (toolResultBlocks.isEmpty()) {
                    if (toolRound > 0) {
                        return buildAggregatedResponse(responseMap, totalInputTokens, totalOutputTokens, toolRound + 1, traceId);
                    }
                    return parsedResponse;
                }
                skillRuntimeService.appendAssistantAndToolResultMessages(body, responseMap, toolResultBlocks);
                toolRound++;
            } catch (Exception e) {
                Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                safeRecordException(logId, e, latencyMs);
                throw e;
            }
        }
    }

    private Map<String, Object> parseResponseMap(String raw) {
        if (StrUtil.isBlank(raw) || !JSONUtil.isTypeJSON(raw)) {
            return null;
        }
        try {
            return CommonUtil.getObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSuccessStatus(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    private void normalizeMaxTokens(Map<String, Object> body) {
        if (body.containsKey("max_tokens")) {
            return;
        }
        Object camelValue = body.remove("maxTokens");
        if (camelValue != null) {
            body.put("max_tokens", camelValue);
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private String buildMessagesUrl() {
        String normalized = StrUtil.removeSuffix(baseUrl, "/");
        return normalized + "/v1/messages";
    }

    private Long safeRecordRequest(Map<String, Object> body, Map<String, String> headers) {
        try {
            return aiModelCallRecordService.recordRequest(body, headers);
        } catch (Exception e) {
            log.warn("模型调用日志记录请求阶段失败: {}", e.getMessage());
            return null;
        }
    }

    private void safeRecordResponse(Long logId, Integer httpStatus, String responseBody, Integer latencyMs) {
        if (logId == null) {
            return;
        }
        try {
            aiModelCallRecordService.recordResponse(logId, httpStatus, responseBody, latencyMs);
        } catch (Exception e) {
            log.warn("模型调用日志记录响应阶段失败: {}", e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable throwable, Integer latencyMs) {
        if (logId == null) {
            return;
        }
        try {
            aiModelCallRecordService.recordException(logId, throwable, latencyMs);
        } catch (Exception e) {
            log.warn("模型调用日志记录异常阶段失败: {}", e.getMessage());
        }
    }

    private Object tryParseJson(Object response) {
        if (response == null) {
            return null;
        }
        String raw = String.valueOf(response);
        if (JSONUtil.isTypeJSON(raw)) {
            return JSONUtil.parse(raw);
        }
        return raw;
    }

    private String extractTraceId() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            String traceId = request.getHeader("trace-id");
            if (StrUtil.isNotBlank(traceId)) {
                return traceId;
            }
            return request.getHeader("x-trace-id");
        } catch (Exception e) {
            log.warn("提取trace-id失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private int[] accumulateUsageResult(Map<String, Object> responseMap, int currentInput, int currentOutput) {
        Object usageObj = responseMap.get("usage");
        if (usageObj instanceof Map) {
            Map<String, Object> usage = (Map<String, Object>) usageObj;
            currentInput += toInt(usage.get("input_tokens"));
            currentOutput += toInt(usage.get("output_tokens"));
        }
        return new int[]{currentInput, currentOutput};
    }

    @SuppressWarnings("unchecked")
    private Object buildAggregatedResponse(Map<String, Object> responseMap, int totalInput, int totalOutput, int toolRounds, String traceId) {
        // 构建汇总 usage
        Map<String, Object> aggregatedUsage;
        Object existingUsage = responseMap.get("usage");
        if (existingUsage instanceof Map) {
            aggregatedUsage = new LinkedHashMap<>((Map<String, Object>) existingUsage);
        } else {
            aggregatedUsage = new LinkedHashMap<>();
        }
        aggregatedUsage.put("input_tokens", totalInput);
        aggregatedUsage.put("output_tokens", totalOutput);
        aggregatedUsage.put("total_tokens", totalInput + totalOutput);
        responseMap.put("usage", aggregatedUsage);

        // 注入 trace-id 和轮次数
        if (StrUtil.isNotBlank(traceId)) {
            responseMap.put("x-trace-id", traceId);
        }
        responseMap.put("tool_rounds", toolRounds);

        return responseMap;
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

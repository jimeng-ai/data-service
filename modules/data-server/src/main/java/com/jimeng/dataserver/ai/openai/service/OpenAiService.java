package com.jimeng.dataserver.ai.openai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService {

    private static final String PROVIDER = "openai";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";

    @Value("${ai-api.openai.api-key:}")
    private String apiKey;

    @Value("${ai-api.openai.base-Url}")
    private String baseUrl;

    @Value("${ai-api.openai.model:gpt-5.3-codex}")
    private String defaultModel;

    @Value("${ai-api.openai.max-completion-tokens:${ai-api.openai.max-tokens:8192}}")
    private Integer defaultMaxCompletionTokens;

    @Value("${ai-api.openai.temperature:1}")
    private Integer defaultTemperature;

    @Value("${ai.system-prompt:}")
    private String systemPrompt;

    @Value("${skill.max-tool-rounds:6}")
    private Integer maxToolRounds;

    private final RequestService requestService;
    private final AiModelCallRecordService aiModelCallRecordService;
    private final SseServiceUtil sseServiceUtil;
    private final SkillRuntimeService skillRuntimeService;
    private final SkillPackageLoaderService skillPackageLoaderService;

    public Object chatCompletions(Map<String, Object> requestBody) {
        Map<String, Object> body = buildRequestBody(requestBody, false);
        Map<String, String> headers = buildHeaders();
        String url = buildChatCompletionsUrl();
        SkillApplyResult skillApplyResult = skillRuntimeService.applyOpenAiSkillContext(body);
        if (skillApplyResult.isEnabled()) {
            log.info("OpenAI启用skills: {}", skillApplyResult.getSelectedSkillNames());
        }
        Map<String, SkillPackage> skillMap = null;
        if (skillApplyResult.isDiscoveryPhase()) {
            skillMap = skillPackageLoaderService.loadSkillPackages();
        }

        int toolRound = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        while (true) {
            long roundStart = System.currentTimeMillis();
            Long logId = safeRecordRequest(body, headers);
            try {
                RequestService.HttpResp httpResp = requestService.post(url, headers, Collections.emptyMap(), body);
                Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                safeRecordResponse(logId, httpResp.getStatusCode(), httpResp.getBody(), latencyMs);
                log.info("OpenAI模型接口返回: {}", httpResp.getBody());

                Object parsedResponse = tryParseJson(httpResp.getBody());
                if (!isSuccessStatus(httpResp.getStatusCode()) || !skillApplyResult.isEnabled()) {
                    return parsedResponse;
                }

                Map<String, Object> responseMap = parseResponseMap(httpResp.getBody());
                if (responseMap == null || responseMap.isEmpty()) {
                    return parsedResponse;
                }

                int[] accumulated = accumulateOpenAiUsageResult(responseMap, totalInputTokens, totalOutputTokens);
                totalInputTokens = accumulated[0];
                totalOutputTokens = accumulated[1];

                List<ToolUseCall> toolCalls = skillRuntimeService.extractOpenAiToolUseCalls(responseMap);
                if (toolCalls.isEmpty()) {
                    if (toolRound > 0) {
                        return buildOpenAiAggregatedResponse(responseMap, totalInputTokens, totalOutputTokens, toolRound + 1, null);
                    }
                    return parsedResponse;
                }

                if (skillApplyResult.isDiscoveryPhase()) {
                    ToolUseCall activateCall = findActivateSkillsCall(toolCalls);
                    if (activateCall != null) {
                        ActivationResult activation = skillRuntimeService.handleOpenAiActivateSkills(
                                body, activateCall, skillMap);
                        skillRuntimeService.appendOpenAiAssistantAndToolResultMessages(
                                body, responseMap, List.of(activation.getToolResultBlock()));
                        skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                        toolRound++;
                        continue;
                    }
                }

                if (toolRound >= Math.max(maxToolRounds, 1)) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool调用轮次超过限制");
                }

                List<Map<String, Object>> toolResultMessages = skillRuntimeService.buildOpenAiToolResultMessages(toolCalls);
                if (toolResultMessages.isEmpty()) {
                    if (toolRound > 0) {
                        return buildOpenAiAggregatedResponse(responseMap, totalInputTokens, totalOutputTokens, toolRound + 1, null);
                    }
                    return parsedResponse;
                }
                skillRuntimeService.appendOpenAiAssistantAndToolResultMessages(body, responseMap, toolResultMessages);
                toolRound++;
            } catch (Exception e) {
                Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                safeRecordException(logId, e, latencyMs);
                throw e;
            }
        }
    }

    public void chatCompletionsStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        Map<String, Object> body;
        try {
            body = buildRequestBody(requestBody, true);
        } catch (Exception e) {
            sendErrorAndComplete(connectionId, e);
            return;
        }

        Map<String, String> headers = buildHeaders();
        if (StrUtil.isNotBlank(traceId)) {
            headers.put("trace-id", traceId);
        }

        String url = buildChatCompletionsUrl();
        SkillApplyResult skillApplyResult = skillRuntimeService.applyOpenAiSkillContext(body);
        if (skillApplyResult.isEnabled()) {
            log.info("OpenAI流式模式启用skills: {}", skillApplyResult.getSelectedSkillNames());
        }
        Map<String, SkillPackage> skillMap = null;
        if (skillApplyResult.isDiscoveryPhase()) {
            skillMap = skillPackageLoaderService.loadSkillPackages();
        }

        int toolRound = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        long totalStartTime = System.currentTimeMillis();

        try {
            while (true) {
                long roundStart = System.currentTimeMillis();
                Long logId = safeRecordRequest(body, headers);
                CountDownLatch latch = new CountDownLatch(1);
                AtomicInteger httpStatus = new AtomicInteger(200);
                AtomicReference<String> requestId = new AtomicReference<>();
                OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator();

                EventSourceListener listener = new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        if (response != null) {
                            httpStatus.set(response.code());
                        }
                        log.info("OpenAI流式连接已建立, connectionId={}", connectionId);
                    }

                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        try {
                            sseServiceUtil.sendEvent(connectionId, "openai-delta", data);
                        } catch (Exception e) {
                            log.warn("OpenAI SSE转发事件失败, connectionId={}, error={}", connectionId, e.getMessage());
                        }
                        accumulator.accumulate(data);
                        if (StrUtil.isNotBlank(accumulator.getRequestId())) {
                            requestId.set(accumulator.getRequestId());
                        }
                        if ("[DONE]".equals(data)) {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onClosed(EventSource eventSource) {
                        log.info("OpenAI流式连接已关闭, connectionId={}", connectionId);
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        String errorMsg = buildStreamErrorMsg(t, response);
                        if (response != null) {
                            httpStatus.set(response.code());
                        }
                        log.error("OpenAI流式连接失败, connectionId={}, error={}", connectionId, errorMsg);
                        try {
                            Map<String, Object> errorData = new LinkedHashMap<>();
                            errorData.put("error", "stream_failure");
                            errorData.put("message", errorMsg);
                            sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
                        } catch (Exception e) {
                            log.warn("发送OpenAI流式错误事件失败: {}", e.getMessage());
                        }
                        latch.countDown();
                    }
                };

                try {
                    requestService.postStream(url, headers, JSONUtil.toJsonStr(body), listener);
                    boolean completed = latch.await(5, TimeUnit.MINUTES);
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                    if (!completed) {
                        RuntimeException timeout = new RuntimeException("OpenAI流式请求超时");
                        safeRecordException(logId, timeout, latencyMs);
                        sendErrorAndComplete(connectionId, timeout);
                        return;
                    }

                    safeRecordStreamResponse(logId, httpStatus.get(),
                            accumulator.getInputTokens(), accumulator.getOutputTokens(),
                            accumulator.toJson(), latencyMs, requestId.get());
                    totalInputTokens += accumulator.getInputTokens();
                    totalOutputTokens += accumulator.getOutputTokens();

                    Map<String, Object> responseMap = accumulator.buildResponseMap();
                    List<ToolUseCall> toolCalls = skillRuntimeService.extractOpenAiToolUseCalls(responseMap);
                    if (!skillApplyResult.isEnabled() || toolCalls.isEmpty()) {
                        sendSummaryEvent(connectionId, totalInputTokens, totalOutputTokens,
                                toolRound > 0 ? toolRound + 1 : 0, traceId,
                                (System.currentTimeMillis() - totalStartTime) / 1000.0);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    if (skillApplyResult.isDiscoveryPhase()) {
                        ToolUseCall activateCall = findActivateSkillsCall(toolCalls);
                        if (activateCall != null) {
                            ActivationResult activation = skillRuntimeService.handleOpenAiActivateSkills(
                                    body, activateCall, skillMap);
                            skillRuntimeService.appendOpenAiAssistantAndToolResultMessages(
                                    body, responseMap, List.of(activation.getToolResultBlock()));
                            skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                            toolRound++;
                            continue;
                        }
                    }

                    if (toolRound >= Math.max(maxToolRounds, 1)) {
                        Map<String, Object> errorData = new LinkedHashMap<>();
                        errorData.put("error", "max_tool_rounds_exceeded");
                        errorData.put("message", "tool调用轮次超过限制: " + maxToolRounds);
                        sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    sendProgressEvent(connectionId, toolRound + 1, toolCalls);
                    List<Map<String, Object>> toolResultMessages = skillRuntimeService.buildOpenAiToolResultMessages(toolCalls);
                    if (toolResultMessages.isEmpty()) {
                        sendSummaryEvent(connectionId, totalInputTokens, totalOutputTokens,
                                toolRound + 1, traceId,
                                (System.currentTimeMillis() - totalStartTime) / 1000.0);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }
                    skillRuntimeService.appendOpenAiAssistantAndToolResultMessages(body, responseMap, toolResultMessages);
                    toolRound++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                    safeRecordException(logId, e, latencyMs);
                    throw new RuntimeException("OpenAI流式请求被中断", e);
                } catch (Exception e) {
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                    safeRecordException(logId, e, latencyMs);
                    throw e;
                }
            }
        } catch (Exception e) {
            sendErrorAndComplete(connectionId, e);
        }
    }

    private Map<String, Object> buildRequestBody(Map<String, Object> requestBody, boolean stream) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未配置ai-api.openai.api-key");
        }

        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.putIfAbsent("model", defaultModel);
        normalizeMaxTokens(body);
        if (!body.containsKey("max_tokens") && !body.containsKey("max_completion_tokens")) {
            body.put("max_completion_tokens", defaultMaxCompletionTokens);
        }
        body.putIfAbsent("temperature", defaultTemperature);
        body.put("stream", stream || Boolean.TRUE.equals(body.get("stream")));
        if (Boolean.TRUE.equals(body.get("stream"))) {
            body.putIfAbsent("stream_options", Map.of("include_usage", true));
        }

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        prependSystemMessageIfNeeded(body);
        return body;
    }

    @SuppressWarnings("unchecked")
    private void prependSystemMessageIfNeeded(Map<String, Object> body) {
        if (StrUtil.isBlank(systemPrompt)) {
            return;
        }
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) {
            return;
        }
        List<Object> messages = (List<Object>) rawMessages;
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> messageMap)) {
                continue;
            }
            Object roleObj = messageMap.get("role");
            if ("system".equals(roleObj) || "developer".equals(roleObj)) {
                return;
            }
        }

        List<Object> copied = new ArrayList<>(messages.size() + 1);
        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);
        copied.add(systemMessage);
        copied.addAll(messages);
        body.put("messages", copied);
    }

    private void normalizeMaxTokens(Map<String, Object> body) {
        Object maxCompletionTokens = body.containsKey("max_completion_tokens")
                ? body.get("max_completion_tokens")
                : body.remove("maxCompletionTokens");
        Object maxTokens = body.containsKey("max_tokens")
                ? body.remove("max_tokens")
                : body.remove("maxTokens");
        body.remove("max_output_tokens");

        Object effectiveMaxTokens = maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
        if (effectiveMaxTokens != null) {
            body.put("max_completion_tokens", effectiveMaxTokens);
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private String buildChatCompletionsUrl() {
        return StrUtil.removeSuffix(baseUrl, "/") + CHAT_COMPLETIONS_ENDPOINT;
    }

    private Long safeRecordRequest(Map<String, Object> body, Map<String, String> headers) {
        try {
            return aiModelCallRecordService.recordRequest(body, headers,
                    PROVIDER, CHAT_COMPLETIONS_ENDPOINT, defaultModel);
        } catch (Exception e) {
            log.warn("OpenAI模型调用日志记录请求阶段失败: {}", e.getMessage());
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
            log.warn("OpenAI模型调用日志记录响应阶段失败: {}", e.getMessage());
        }
    }

    private void safeRecordStreamResponse(Long logId, Integer httpStatus,
                                          int inputTokens, int outputTokens,
                                          String streamEventsJson, Integer latencyMs,
                                          String requestId) {
        if (logId == null) {
            return;
        }
        try {
            aiModelCallRecordService.recordStreamResponse(logId, httpStatus,
                    inputTokens, outputTokens, streamEventsJson, latencyMs, requestId);
        } catch (Exception e) {
            log.warn("OpenAI模型调用日志记录流式响应阶段失败: {}", e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable throwable, Integer latencyMs) {
        if (logId == null) {
            return;
        }
        try {
            aiModelCallRecordService.recordException(logId, throwable, latencyMs);
        } catch (Exception e) {
            log.warn("OpenAI模型调用日志记录异常阶段失败: {}", e.getMessage());
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

    @SuppressWarnings("unchecked")
    private int[] accumulateOpenAiUsageResult(Map<String, Object> responseMap, int currentInput, int currentOutput) {
        Object usageObj = responseMap.get("usage");
        if (usageObj instanceof Map) {
            Map<String, Object> usage = (Map<String, Object>) usageObj;
            currentInput += toInt(usage.get("prompt_tokens"), usage.get("input_tokens"));
            currentOutput += toInt(usage.get("completion_tokens"), usage.get("output_tokens"));
        }
        return new int[]{currentInput, currentOutput};
    }

    @SuppressWarnings("unchecked")
    private Object buildOpenAiAggregatedResponse(Map<String, Object> responseMap,
                                                 int totalInput, int totalOutput,
                                                 int toolRounds, String traceId) {
        Map<String, Object> aggregatedUsage;
        Object existingUsage = responseMap.get("usage");
        if (existingUsage instanceof Map) {
            aggregatedUsage = new LinkedHashMap<>((Map<String, Object>) existingUsage);
        } else {
            aggregatedUsage = new LinkedHashMap<>();
        }
        aggregatedUsage.put("prompt_tokens", totalInput);
        aggregatedUsage.put("completion_tokens", totalOutput);
        aggregatedUsage.put("total_tokens", totalInput + totalOutput);
        responseMap.put("usage", aggregatedUsage);

        if (StrUtil.isNotBlank(traceId)) {
            responseMap.put("x-trace-id", traceId);
        }
        responseMap.put("tool_rounds", toolRounds);
        return responseMap;
    }

    private ToolUseCall findActivateSkillsCall(List<ToolUseCall> toolCalls) {
        for (ToolUseCall call : toolCalls) {
            if (skillRuntimeService.isActivateSkillsCall(call)) {
                return call;
            }
        }
        return null;
    }

    private void sendProgressEvent(String connectionId, int round, List<ToolUseCall> toolCalls) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("round", round);
        List<String> toolNames = new ArrayList<>();
        for (ToolUseCall call : toolCalls) {
            toolNames.add(call.getToolName());
        }
        progress.put("tools", toolNames);
        sseServiceUtil.sendEvent(connectionId, "progress", JSONUtil.toJsonStr(progress));
    }

    private int toInt(Object first, Object second) {
        Object value = first != null ? first : second;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sendSummaryEvent(String connectionId, int inputTokens, int outputTokens,
                                  int toolRounds, String traceId, double elapsedSeconds) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("input_tokens", inputTokens);
        summary.put("output_tokens", outputTokens);
        summary.put("total_tokens", inputTokens + outputTokens);
        if (toolRounds > 0) {
            summary.put("tool_rounds", toolRounds);
        }
        summary.put("elapsed_seconds", elapsedSeconds);
        if (StrUtil.isNotBlank(traceId)) {
            summary.put("x-trace-id", traceId);
        }
        sseServiceUtil.sendEvent(connectionId, "summary", JSONUtil.toJsonStr(summary));
    }

    private void sendErrorAndComplete(String connectionId, Throwable throwable) {
        try {
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", throwable == null ? "UNKNOWN_ERROR" : throwable.getClass().getSimpleName());
            errorData.put("message", throwable == null ? "未知异常" : throwable.getMessage());
            sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
        } catch (Exception e) {
            log.warn("发送OpenAI错误事件失败: {}", e.getMessage());
        }
        try {
            sseServiceUtil.complete(connectionId);
        } catch (Exception e) {
            log.warn("关闭OpenAI SSE连接失败: {}", e.getMessage());
        }
    }

    private String buildStreamErrorMsg(Throwable t, Response response) {
        StringBuilder sb = new StringBuilder();
        if (response != null) {
            sb.append("HTTP ").append(response.code());
            try {
                if (response.body() != null) {
                    String respBody = response.body().string();
                    if (StrUtil.isNotBlank(respBody)) {
                        sb.append(" - ").append(respBody);
                    }
                }
            } catch (Exception e) {
                log.warn("读取OpenAI流式错误响应体失败: {}", e.getMessage());
            }
        }
        if (t != null) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "未知流式错误";
    }

    private static class OpenAiStreamAccumulator {
        private final List<Object> events = new ArrayList<>();
        private final StringBuilder contentBuilder = new StringBuilder();
        private final Map<Integer, ToolCallChunk> toolCallChunks = new LinkedHashMap<>();
        private String requestId;
        private String model;
        private String role = "assistant";
        private String finishReason;
        private int inputTokens;
        private int outputTokens;

        private void accumulate(String data) {
            if (StrUtil.isBlank(data) || "[DONE]".equals(data)) {
                return;
            }
            events.add(data);
            if (!JSONUtil.isTypeJSON(data)) {
                return;
            }
            try {
                JSONObject root = JSONUtil.parseObj(data);
                if (StrUtil.isBlank(requestId)) {
                    requestId = root.getStr("id");
                }
                if (StrUtil.isBlank(model)) {
                    model = root.getStr("model");
                }
                JSONObject usage = root.getJSONObject("usage");
                if (usage != null) {
                    inputTokens = toInt(usage.get("prompt_tokens"), usage.get("input_tokens"));
                    outputTokens = toInt(usage.get("completion_tokens"), usage.get("output_tokens"));
                }
                accumulateChoiceDelta(root);
            } catch (Exception ignored) {
                // 部分代理会发送非标准心跳，忽略即可。
            }
        }

        private String getRequestId() {
            return requestId;
        }

        private int getInputTokens() {
            return inputTokens;
        }

        private int getOutputTokens() {
            return outputTokens;
        }

        private String toJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("events", events);
            payload.put("usage", Map.of(
                    "input_tokens", inputTokens,
                    "output_tokens", outputTokens,
                    "total_tokens", inputTokens + outputTokens
            ));
            return JSONUtil.toJsonStr(payload);
        }

        private Map<String, Object> buildResponseMap() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", requestId);
            response.put("object", "chat.completion");
            response.put("model", model);

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", contentBuilder.isEmpty() ? "" : contentBuilder.toString());

            List<Map<String, Object>> toolCalls = buildToolCalls();
            if (!toolCalls.isEmpty()) {
                message.put("tool_calls", toolCalls);
            }

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);
            response.put("choices", List.of(choice));
            response.put("usage", Map.of(
                    "prompt_tokens", inputTokens,
                    "completion_tokens", outputTokens,
                    "total_tokens", inputTokens + outputTokens
            ));
            return response;
        }

        private void accumulateChoiceDelta(JSONObject root) {
            Object choicesObj = root.get("choices");
            if (!(choicesObj instanceof List<?> choices)) {
                return;
            }
            for (Object choiceObj : choices) {
                if (!(choiceObj instanceof Map<?, ?> choiceMap)) {
                    continue;
                }
                Object finishReasonObj = choiceMap.get("finish_reason");
                if (finishReasonObj != null) {
                    finishReason = String.valueOf(finishReasonObj);
                }
                Object deltaObj = choiceMap.get("delta");
                if (!(deltaObj instanceof Map<?, ?> deltaMap)) {
                    continue;
                }
                Object roleObj = deltaMap.get("role");
                if (roleObj != null) {
                    role = String.valueOf(roleObj);
                }
                Object contentObj = deltaMap.get("content");
                if (contentObj != null) {
                    contentBuilder.append(contentObj);
                }
                Object toolCallsObj = deltaMap.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    accumulateToolCalls(toolCalls);
                }
                Object functionCallObj = deltaMap.get("function_call");
                if (functionCallObj instanceof Map<?, ?> functionCallMap) {
                    accumulateLegacyFunctionCall(functionCallMap);
                }
            }
        }

        private void accumulateToolCalls(List<?> toolCalls) {
            for (Object toolCallObj : toolCalls) {
                if (!(toolCallObj instanceof Map<?, ?> toolCallMap)) {
                    continue;
                }
                int index = toInt(toolCallMap.get("index"), 0);
                ToolCallChunk chunk = toolCallChunks.computeIfAbsent(index, key -> new ToolCallChunk());
                Object idObj = toolCallMap.get("id");
                if (idObj != null) {
                    chunk.id = String.valueOf(idObj);
                }
                Object typeObj = toolCallMap.get("type");
                if (typeObj != null) {
                    chunk.type = String.valueOf(typeObj);
                }
                Object functionObj = toolCallMap.get("function");
                if (functionObj instanceof Map<?, ?> functionMap) {
                    Object nameObj = functionMap.get("name");
                    if (nameObj != null && StrUtil.isNotBlank(String.valueOf(nameObj))) {
                        chunk.name = String.valueOf(nameObj);
                    }
                    Object argumentsObj = functionMap.get("arguments");
                    if (argumentsObj != null) {
                        chunk.argumentsBuilder.append(argumentsObj);
                    }
                }
            }
        }

        private void accumulateLegacyFunctionCall(Map<?, ?> functionCallMap) {
            ToolCallChunk chunk = toolCallChunks.computeIfAbsent(0, key -> new ToolCallChunk());
            if (StrUtil.isBlank(chunk.id)) {
                chunk.id = requestId;
            }
            chunk.type = "function";
            Object nameObj = functionCallMap.get("name");
            if (nameObj != null && StrUtil.isNotBlank(String.valueOf(nameObj))) {
                chunk.name = String.valueOf(nameObj);
            }
            Object argumentsObj = functionCallMap.get("arguments");
            if (argumentsObj != null) {
                chunk.argumentsBuilder.append(argumentsObj);
            }
        }

        private List<Map<String, Object>> buildToolCalls() {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (Map.Entry<Integer, ToolCallChunk> entry : toolCallChunks.entrySet()) {
                ToolCallChunk chunk = entry.getValue();
                if (StrUtil.isBlank(chunk.id) || StrUtil.isBlank(chunk.name)) {
                    log.warn("忽略不完整的OpenAI流式tool_call, id={}, name={}", chunk.id, chunk.name);
                    continue;
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", chunk.name);
                String arguments = chunk.argumentsBuilder.toString();
                function.put("arguments", StrUtil.isBlank(arguments) ? "{}" : arguments);

                Map<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", chunk.id);
                toolCall.put("type", StrUtil.isBlank(chunk.type) ? "function" : chunk.type);
                toolCall.put("function", function);
                toolCalls.add(toolCall);
            }
            return toolCalls;
        }

        private int toInt(Object first, Object second) {
            Object value = first != null ? first : second;
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                return 0;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static class ToolCallChunk {
            private String id;
            private String type;
            private String name;
            private final StringBuilder argumentsBuilder = new StringBuilder();
        }
    }
}

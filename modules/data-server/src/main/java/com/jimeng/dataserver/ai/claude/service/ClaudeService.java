package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.stream.ClaudeStreamEventAccumulator;
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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private final SkillPackageLoaderService skillPackageLoaderService;
    private final SseServiceUtil sseServiceUtil;

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

        // 保存 skillMap 供 Activation Phase 使用
        Map<String, SkillPackage> skillMap = null;
        if (skillApplyResult.isDiscoveryPhase()) {
            skillMap = skillPackageLoaderService.loadSkillPackages();
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

                // 检查是否包含 activate_skills 调用（Discovery Phase → Activation Phase 转换）
                if (skillApplyResult.isDiscoveryPhase()) {
                    ToolUseCall activateCall = findActivateSkillsCall(toolCalls);
                    if (activateCall != null) {
                        ActivationResult activation = skillRuntimeService.handleActivateSkills(
                                body, activateCall, skillMap);
                        appendActivationMessages(body, responseMap, activation);
                        skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                        toolRound++;
                        continue;
                    }
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

    /**
     * 流式调用 Claude API，通过 SSE 实时转发事件给客户端。
     * 支持多轮 tool use 循环，每轮均使用流式调用。
     *
     * @param requestBody  请求体
     * @param connectionId SSE 连接标识
     */
    public void messagesStream(Map<String, Object> requestBody, String connectionId, String traceId) {
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

        // 设置 stream: true
        body.put("stream", true);

        SkillApplyResult skillApplyResult = skillRuntimeService.applySkillContext(body);
        if (skillApplyResult.isEnabled()) {
            log.info("流式模式启用skills: {}", skillApplyResult.getSelectedSkillNames());
        }

        // 保存 skillMap 供 Activation Phase 使用
        Map<String, SkillPackage> skillMap = null;
        if (skillApplyResult.isDiscoveryPhase()) {
            skillMap = skillPackageLoaderService.loadSkillPackages();
        }

        Map<String, String> headers = buildHeaders();
        // 将 trace-id 放入 headers，供 recordRequest 在异步线程中读取
        if (StrUtil.isNotBlank(traceId)) {
            headers.put("trace-id", traceId);
        }
        String url = buildMessagesUrl();
        int toolRound = 0;

        // 多轮 token 累加变量
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        // 总耗时起点
        long totalStartTime = System.currentTimeMillis();        try {
            while (true) {
                long roundStart = System.currentTimeMillis();
                Long logId = safeRecordRequest(body, headers);

                try {
                    // 每轮创建新的累积器和 latch
                    ClaudeStreamEventAccumulator accumulator = new ClaudeStreamEventAccumulator();
                    CountDownLatch latch = new CountDownLatch(1);

                    // 构建 EventSourceListener
                    final String connId = connectionId;
                    EventSourceListener listener = new EventSourceListener() {
                        @Override
                        public void onOpen(EventSource eventSource, Response response) {
                            log.info("流式连接已建立, connectionId={}", connId);
                        }

                        @Override
                        public void onEvent(EventSource eventSource, String id,
                                            String type, String data) {
                            try {
                                // 转发事件给客户端
                                sseServiceUtil.sendEvent(connId, "claude-delta", data);
                            } catch (Exception e) {
                                log.warn("SSE转发事件失败, connectionId={}, error={}", connId, e.getMessage());
                            }
                            // 同时累积事件
                            accumulator.accumulate(type, data);
                        }

                        @Override
                        public void onClosed(EventSource eventSource) {
                            log.info("流式连接已关闭, connectionId={}", connId);
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(EventSource eventSource, Throwable t,
                                              Response response) {
                            String errorMsg = buildStreamErrorMsg(t, response);
                            log.error("流式连接失败, connectionId={}, error={}", connId, errorMsg);
                            try {
                                Map<String, Object> errorData = new LinkedHashMap<>();
                                errorData.put("error", "stream_failure");
                                errorData.put("message", errorMsg);
                                sseServiceUtil.sendEvent(connId, "error", JSONUtil.toJsonStr(errorData));
                            } catch (Exception e) {
                                log.warn("发送流式错误事件失败: {}", e.getMessage());
                            }
                            latch.countDown();
                        }
                    };

                    // 发起流式请求
                    String bodyJson = JSONUtil.toJsonStr(body);
                    requestService.postStream(url, headers, bodyJson, listener);

                    // 等待流完成
                    boolean completed = latch.await(5, TimeUnit.MINUTES);
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);

                    if (!completed) {
                        // 超时处理
                        log.error("流式请求超时, connectionId={}", connectionId);
                        Map<String, Object> errorData = new LinkedHashMap<>();
                        errorData.put("error", "timeout");
                        errorData.put("message", "流式请求超时（5分钟）");
                        sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
                        sseServiceUtil.complete(connectionId);
                        safeRecordException(logId, new RuntimeException("流式请求超时"), latencyMs);
                        return;
                    }

                    // 记录流式响应日志
                    Map<String, Object> responseMap = accumulator.buildResponseMap();
                    String streamEventsJson = JSONUtil.toJsonStr(responseMap);
                    String requestId = responseMap.get("id") != null ? String.valueOf(responseMap.get("id")) : null;
                    safeRecordStreamResponse(logId, 200,
                            accumulator.getInputTokens(), accumulator.getOutputTokens(),
                            streamEventsJson, latencyMs, requestId);

                    // 累加本轮 token 用量
                    totalInputTokens += accumulator.getInputTokens();
                    totalOutputTokens += accumulator.getOutputTokens();

                    // 判断是否包含 tool_use
                    if (!skillApplyResult.isEnabled() || !accumulator.hasToolUse()) {
                        // Final_Round: 发送 summary 事件并关闭连接
                        double elapsedSeconds = (System.currentTimeMillis() - totalStartTime) / 1000.0;
                        sendSummaryEvent(connectionId, totalInputTokens, totalOutputTokens, toolRound > 0 ? toolRound + 1 : 0, traceId, elapsedSeconds);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    // Intermediate_Round: 提取 tool_use 调用
                    List<ToolUseCall> toolCalls = skillRuntimeService.extractToolUseCalls(responseMap);
                    if (toolCalls.isEmpty()) {
                        // 无 tool 调用，视为 Final_Round
                        double elapsedSeconds = (System.currentTimeMillis() - totalStartTime) / 1000.0;
                        sendSummaryEvent(connectionId, totalInputTokens, totalOutputTokens, toolRound > 0 ? toolRound + 1 : 0, traceId, elapsedSeconds);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    // 检查是否包含 activate_skills 调用（Discovery Phase → Activation Phase 转换）
                    if (skillApplyResult.isDiscoveryPhase()) {
                        ToolUseCall activateCall = findActivateSkillsCall(toolCalls);
                        if (activateCall != null) {
                            ActivationResult activation = skillRuntimeService.handleActivateSkills(
                                    body, activateCall, skillMap);
                            appendActivationMessages(body, responseMap, activation);
                            skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                            toolRound++;
                            continue;
                        }
                    }

                    // 检查 tool 轮次限制
                    if (toolRound >= Math.max(maxToolRounds, 1)) {
                        Map<String, Object> errorData = new LinkedHashMap<>();
                        errorData.put("error", "max_tool_rounds_exceeded");
                        errorData.put("message", "tool调用轮次超过限制: " + maxToolRounds);
                        sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    // 发送 progress 事件
                    sendProgressEvent(connectionId, toolRound + 1, toolCalls);

                    // 执行 tools
                    List<Map<String, Object>> toolResultBlocks = skillRuntimeService.buildToolResultBlocks(toolCalls);
                    if (toolResultBlocks.isEmpty()) {
                        double elapsedSeconds = (System.currentTimeMillis() - totalStartTime) / 1000.0;
                        sendSummaryEvent(connectionId, totalInputTokens, totalOutputTokens, toolRound + 1, traceId, elapsedSeconds);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }
                    skillRuntimeService.appendAssistantAndToolResultMessages(body, responseMap, toolResultBlocks);
                    toolRound++;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                    safeRecordException(logId, e, latencyMs);
                    throw new RuntimeException("流式请求被中断", e);
                } catch (Exception e) {
                    Integer latencyMs = Math.toIntExact(System.currentTimeMillis() - roundStart);
                    safeRecordException(logId, e, latencyMs);
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("流式处理异常, connectionId={}", connectionId, e);
            try {
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("error", e.getClass().getSimpleName());
                errorData.put("message", e.getMessage());
                sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
            } catch (Exception sendError) {
                log.warn("发送错误事件失败: {}", sendError.getMessage());
            }
            try {
                sseServiceUtil.complete(connectionId);
            } catch (Exception completeError) {
                log.warn("关闭SSE连接失败: {}", completeError.getMessage());
            }
        }
    }

    /**
     * 发送 summary 事件，包含聚合的 token 用量和轮次信息。
     */
    private void sendSummaryEvent(String connectionId, int inputTokens, int outputTokens, int toolRounds, String traceId, double elapsedSeconds) {
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

    /**
     * 发送 progress 事件，包含当前轮次和正在执行的 tool 名称列表。
     */
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

    /**
     * 在 toolCalls 中查找 activate_skills 调用。
     */
    private ToolUseCall findActivateSkillsCall(List<ToolUseCall> toolCalls) {
        for (ToolUseCall call : toolCalls) {
            if (skillRuntimeService.isActivateSkillsCall(call)) {
                return call;
            }
        }
        return null;
    }

    /**
     * 将 Discovery Phase 的 assistant 响应和 activate_skills 的 tool_result 追加到 messages 中。
     * 遵循 Claude API 的 assistant → user(tool_result) 交替格式。
     */
    @SuppressWarnings("unchecked")
    private void appendActivationMessages(Map<String, Object> body,
                                          Map<String, Object> responseMap,
                                          ActivationResult activation) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) {
            return;
        }
        List<Object> messages = (List<Object>) rawMessages;

        // 追加 Discovery Phase 的 assistant 响应
        Object contentObj = responseMap.get("content");
        List<Object> assistantContent = contentObj instanceof List<?>
                ? new java.util.ArrayList<>((List<?>) contentObj)
                : Collections.emptyList();

        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", assistantContent);
        messages.add(assistantMessage);

        // 追加 activation tool_result 作为 user 消息
        Map<String, Object> userToolResultMessage = new LinkedHashMap<>();
        userToolResultMessage.put("role", "user");
        userToolResultMessage.put("content", List.of(activation.getToolResultBlock()));
        messages.add(userToolResultMessage);
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
            log.warn("模型调用日志记录流式响应阶段失败: {}", e.getMessage());
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

    /**
     * 从 onFailure 回调中提取详细错误信息。
     * 优先读取 response body（Claude API 错误详情），其次读取 Throwable 信息。
     */
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
                log.warn("读取流式错误响应体失败: {}", e.getMessage());
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

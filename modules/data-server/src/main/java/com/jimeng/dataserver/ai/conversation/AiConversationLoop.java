package com.jimeng.dataserver.ai.conversation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationLoop {

    private final RequestService requestService;
    private final SkillRuntimeService skillRuntimeService;
    private final SkillPackageLoaderService skillPackageLoaderService;
    private final AiModelCallRecordService aiModelCallRecordService;
    private final SseServiceUtil sseServiceUtil;

    @Value("${skill.max-tool-rounds:10}")
    private int maxToolRounds;

    public record CallRecordConfig(String provider, String endpoint, String defaultModel) {}

    // ------------------------------------------------------------------ non-stream

    public Object runBlocking(Map<String, Object> body, AiProtocolAdapter adapter,
                               Map<String, String> headers, String url,
                               String traceId, CallRecordConfig rc) {
        SkillApplyResult skillApplyResult = skillRuntimeService.applySkillContext(body, adapter);
        if (skillApplyResult.isEnabled()) {
            log.info("启用skills ({}): {}", rc.provider(), skillApplyResult.getSelectedSkillNames());
        }
        Map<String, SkillPackage> skillMap = skillApplyResult.isDiscoveryPhase()
                ? skillPackageLoaderService.loadSkillPackages() : null;

        int toolRound = 0, totalIn = 0, totalOut = 0;

        while (true) {
            long start = System.currentTimeMillis();
            Long logId = safeRecordRequest(body, headers, rc);
            try {
                RequestService.HttpResp resp = requestService.post(url, headers, Collections.emptyMap(), body);
                int latency = elapsed(start);
                safeRecordResponse(logId, resp.getStatusCode(), resp.getBody(), latency);
                log.info("{} 接口返回: {}", rc.provider(), resp.getBody());

                Object parsed = tryParseJson(resp.getBody());
                if (!isSuccess(resp.getStatusCode()) || !skillApplyResult.isEnabled()) {
                    return parsed;
                }

                Map<String, Object> responseMap = parseResponseMap(resp.getBody());
                if (responseMap == null || responseMap.isEmpty()) return parsed;

                int[] usage = adapter.extractUsage(responseMap);
                totalIn += usage[0];
                totalOut += usage[1];

                List<ToolUseCall> toolCalls = adapter.extractToolUseCalls(responseMap);
                if (toolCalls.isEmpty()) {
                    if (toolRound > 0) {
                        return adapter.buildAggregatedResponse(responseMap, totalIn, totalOut, toolRound + 1, traceId);
                    }
                    return parsed;
                }

                if (skillApplyResult.isDiscoveryPhase()) {
                    ToolUseCall activateCall = findActivateCall(toolCalls);
                    if (activateCall != null) {
                        ActivationResult activation = skillRuntimeService.handleActivateSkills(
                                body, activateCall, skillMap, adapter);
                        adapter.appendActivationTurn(body, responseMap, activation);
                        skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                        toolRound++;
                        continue;
                    }
                }

                if (toolRound >= maxToolRounds) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool调用轮次超过限制");
                }

                List<ToolExecutionResult> results = skillRuntimeService.executeToolCalls(toolCalls);
                if (results.isEmpty()) {
                    if (toolRound > 0) {
                        return adapter.buildAggregatedResponse(responseMap, totalIn, totalOut, toolRound + 1, traceId);
                    }
                    return parsed;
                }
                adapter.appendToolResultTurn(body, responseMap, results);
                toolRound++;
            } catch (Exception e) {
                safeRecordException(logId, e, elapsed(start));
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ stream

    public void runStream(Map<String, Object> body, AiProtocolAdapter adapter,
                          Map<String, String> headers, String url,
                          String connectionId, String traceId, CallRecordConfig rc) {
        SkillApplyResult skillApplyResult = skillRuntimeService.applySkillContext(body, adapter);
        if (skillApplyResult.isEnabled()) {
            log.info("流式启用skills ({}): {}", rc.provider(), skillApplyResult.getSelectedSkillNames());
        }
        Map<String, SkillPackage> skillMap = skillApplyResult.isDiscoveryPhase()
                ? skillPackageLoaderService.loadSkillPackages() : null;

        int toolRound = 0, totalIn = 0, totalOut = 0;
        long totalStart = System.currentTimeMillis();

        try {
            while (true) {
                long start = System.currentTimeMillis();
                Long logId = safeRecordRequest(body, headers, rc);
                AiStreamAccumulator accumulator = adapter.createStreamAccumulator();
                CountDownLatch latch = new CountDownLatch(1);

                try {
                    EventSourceListener listener = buildListener(connectionId, adapter, accumulator, latch);
                    requestService.postStream(url, headers, JSONUtil.toJsonStr(body), listener);

                    boolean completed = latch.await(5, TimeUnit.MINUTES);
                    int latency = elapsed(start);

                    if (!completed) {
                        RuntimeException timeout = new RuntimeException("流式请求超时");
                        safeRecordException(logId, timeout, latency);
                        sendError(connectionId, "timeout", "流式请求超时（5分钟）");
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    safeRecordStreamResponse(logId, 200,
                            accumulator.getInputTokens(), accumulator.getOutputTokens(),
                            accumulator.toJson(), latency, accumulator.getRequestId());
                    totalIn += accumulator.getInputTokens();
                    totalOut += accumulator.getOutputTokens();

                    Map<String, Object> responseMap = accumulator.buildResponseMap();
                    List<ToolUseCall> toolCalls = adapter.extractToolUseCalls(responseMap);

                    if (!skillApplyResult.isEnabled() || toolCalls.isEmpty()) {
                        double elapsed = (System.currentTimeMillis() - totalStart) / 1000.0;
                        sendSummary(connectionId, totalIn, totalOut,
                                toolRound > 0 ? toolRound + 1 : 0, traceId, elapsed);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    if (skillApplyResult.isDiscoveryPhase()) {
                        ToolUseCall activateCall = findActivateCall(toolCalls);
                        if (activateCall != null) {
                            ActivationResult activation = skillRuntimeService.handleActivateSkills(
                                    body, activateCall, skillMap, adapter);
                            adapter.appendActivationTurn(body, responseMap, activation);
                            skillApplyResult = SkillApplyResult.activated(activation.getActivatedSkillNames());
                            toolRound++;
                            continue;
                        }
                    }

                    if (toolRound >= maxToolRounds) {
                        sendError(connectionId, "max_tool_rounds_exceeded",
                                "tool调用轮次超过限制: " + maxToolRounds);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }

                    sendProgress(connectionId, toolRound + 1, toolCalls);
                    List<ToolExecutionResult> results = skillRuntimeService.executeToolCalls(toolCalls);
                    sendToolResults(connectionId, toolRound + 1, results);

                    if (results.isEmpty()) {
                        double elapsed = (System.currentTimeMillis() - totalStart) / 1000.0;
                        sendSummary(connectionId, totalIn, totalOut, toolRound + 1, traceId, elapsed);
                        sseServiceUtil.complete(connectionId);
                        return;
                    }
                    adapter.appendToolResultTurn(body, responseMap, results);
                    toolRound++;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    safeRecordException(logId, e, elapsed(start));
                    throw new RuntimeException("流式请求被中断", e);
                } catch (Exception e) {
                    safeRecordException(logId, e, elapsed(start));
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("流式处理异常, connectionId={}", connectionId, e);
            sendError(connectionId, e.getClass().getSimpleName(), e.getMessage());
            sseServiceUtil.complete(connectionId);
        }
    }

    // ------------------------------------------------------------------ SSE event helpers

    private void sendSummary(String connectionId, int inputTokens, int outputTokens,
                              int toolRounds, String traceId, double elapsedSeconds) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("input_tokens", inputTokens);
        summary.put("output_tokens", outputTokens);
        summary.put("total_tokens", inputTokens + outputTokens);
        if (toolRounds > 0) summary.put("tool_rounds", toolRounds);
        summary.put("elapsed_seconds", elapsedSeconds);
        if (StrUtil.isNotBlank(traceId)) summary.put("x-trace-id", traceId);
        sseServiceUtil.sendEvent(connectionId, "summary", JSONUtil.toJsonStr(summary));
    }

    private void sendProgress(String connectionId, int round, List<ToolUseCall> toolCalls) {
        List<String> toolNames = new ArrayList<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolUseCall call : toolCalls) {
            toolNames.add(call.getToolName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getToolUseId());
            item.put("name", call.getToolName());
            item.put("input", call.getInput());
            item.put("status", "running");
            calls.add(item);
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("round", round);
        progress.put("tools", toolNames);
        progress.put("calls", calls);
        sseServiceUtil.sendEvent(connectionId, "progress", JSONUtil.toJsonStr(progress));
    }

    private void sendToolResults(String connectionId, int round, List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) return;
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolExecutionResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getToolUseId());
            item.put("name", r.getToolName());
            item.put("status", r.isSuccess() ? "success" : "error");
            item.put("output", r.getPayload());
            items.add(item);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("round", round);
        event.put("results", items);
        sseServiceUtil.sendEvent(connectionId, "tool_result", JSONUtil.toJsonStr(event));
    }

    private void sendError(String connectionId, String error, String message) {
        try {
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", error);
            errorData.put("message", message);
            sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(errorData));
        } catch (Exception e) {
            log.warn("发送错误事件失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ stream listener

    private EventSourceListener buildListener(String connectionId, AiProtocolAdapter adapter,
                                               AiStreamAccumulator accumulator, CountDownLatch latch) {
        return new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.info("流式连接已建立, connectionId={}", connectionId);
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                try {
                    sseServiceUtil.sendEvent(connectionId, adapter.getDeltaEventType(), data);
                } catch (Exception e) {
                    log.warn("SSE转发失败, connectionId={}, error={}", connectionId, e.getMessage());
                }
                accumulator.accumulateEvent(type, data);
                if (adapter.isDoneSignal(data)) latch.countDown();
            }

            @Override
            public void onClosed(EventSource eventSource) {
                log.info("流式连接已关闭, connectionId={}", connectionId);
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                String errorMsg = buildStreamErrorMsg(t, response);
                log.error("流式连接失败, connectionId={}, error={}", connectionId, errorMsg);
                sendError(connectionId, "stream_failure", errorMsg);
                latch.countDown();
            }
        };
    }

    private String buildStreamErrorMsg(Throwable t, Response response) {
        StringBuilder sb = new StringBuilder();
        if (response != null) {
            sb.append("HTTP ").append(response.code());
            try {
                if (response.body() != null) {
                    String body = response.body().string();
                    if (StrUtil.isNotBlank(body)) sb.append(" - ").append(body);
                }
            } catch (Exception e) {
                log.warn("读取流式错误响应体失败: {}", e.getMessage());
            }
        }
        if (t != null) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "未知流式错误";
    }

    // ------------------------------------------------------------------ record helpers

    private Long safeRecordRequest(Map<String, Object> body, Map<String, String> headers, CallRecordConfig rc) {
        try {
            return aiModelCallRecordService.recordRequest(body, headers,
                    rc.provider(), rc.endpoint(), rc.defaultModel());
        } catch (Exception e) {
            log.warn("模型调用日志记录请求阶段失败: {}", e.getMessage());
            return null;
        }
    }

    private void safeRecordResponse(Long logId, Integer httpStatus, String responseBody, int latencyMs) {
        if (logId == null) return;
        try {
            aiModelCallRecordService.recordResponse(logId, httpStatus, responseBody, latencyMs);
        } catch (Exception e) {
            log.warn("模型调用日志记录响应阶段失败: {}", e.getMessage());
        }
    }

    private void safeRecordStreamResponse(Long logId, Integer httpStatus,
                                           int inputTokens, int outputTokens,
                                           String streamEventsJson, int latencyMs, String requestId) {
        if (logId == null) return;
        try {
            aiModelCallRecordService.recordStreamResponse(logId, httpStatus,
                    inputTokens, outputTokens, streamEventsJson, latencyMs, requestId);
        } catch (Exception e) {
            log.warn("模型调用日志记录流式响应阶段失败: {}", e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable throwable, int latencyMs) {
        if (logId == null) return;
        try {
            aiModelCallRecordService.recordException(logId, throwable, latencyMs);
        } catch (Exception e) {
            log.warn("模型调用日志记录异常阶段失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ misc helpers

    private ToolUseCall findActivateCall(List<ToolUseCall> toolCalls) {
        for (ToolUseCall call : toolCalls) {
            if ("activate_skills".equals(call.getToolName())) return call;
        }
        return null;
    }

    private boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    private Object tryParseJson(Object response) {
        if (response == null) return null;
        String raw = String.valueOf(response);
        return JSONUtil.isTypeJSON(raw) ? JSONUtil.parse(raw) : raw;
    }

    private Map<String, Object> parseResponseMap(String raw) {
        if (StrUtil.isBlank(raw) || !JSONUtil.isTypeJSON(raw)) return null;
        try { return CommonUtil.getObjectMapper().readValue(raw, Map.class); }
        catch (Exception e) { return null; }
    }

    private int elapsed(long startMs) {
        return Math.toIntExact(System.currentTimeMillis() - startMs);
    }
}

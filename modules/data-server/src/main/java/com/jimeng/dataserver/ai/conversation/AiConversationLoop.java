package com.jimeng.dataserver.ai.conversation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.TraceRecorder;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.resilience.LlmCallGuard;
import com.jimeng.dataserver.ai.support.SseEventBridge;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationLoop {

    private final RequestService requestService;
    private final SkillRuntimeService skillRuntimeService;
    private final AiModelCallRecordService aiModelCallRecordService;
    private final SseEventBridge sseBridge;
    private final LlmCallGuard llmCallGuard;
    private final TraceRecorder traceRecorder;

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
        Map<String, ToolPackage> skillMap = skillApplyResult.isDiscoveryPhase()
                ? skillRuntimeService.aggregateToolPackages() : null;

        int toolRound = 0, totalIn = 0, totalOut = 0;

        while (true) {
            llmCallGuard.acquirePermission();
            long start = System.currentTimeMillis();
            Long logId = safeRecordRequest(body, headers, rc);
            try {
                RequestService.HttpResp resp;
                try {
                    resp = requestService.post(url, headers, Collections.emptyMap(), body);
                } catch (RuntimeException callEx) {
                    llmCallGuard.recordFailure();
                    throw callEx;
                }
                int latency = elapsed(start);
                safeRecordResponse(logId, resp.getStatusCode(), resp.getBody(), latency);
                recordGuardOutcome(resp.getStatusCode());
                log.info("{} 接口返回: {}", rc.provider(), resp.getBody());

                Object parsed = tryParseJson(resp.getBody());
                if (!isSuccess(resp.getStatusCode()) || !skillApplyResult.isEnabled()) {
                    boolean ok = isSuccess(resp.getStatusCode());
                    traceRecorder.recordLlm(logId, "推理·生成回答", modelOf(body, rc),
                            null, null, null, latency, ok, ok ? null : resp.getBody());
                    return parsed;
                }

                Map<String, Object> responseMap = parseResponseMap(resp.getBody());
                if (responseMap == null || responseMap.isEmpty()) return parsed;

                int[] usage = adapter.extractUsage(responseMap);
                totalIn += usage[0];
                totalOut += usage[1];

                List<ToolUseCall> toolCalls = adapter.extractToolUseCalls(responseMap);
                traceRecorder.recordLlm(logId,
                        toolCalls.isEmpty() ? "推理·生成回答" : "推理·决定调用工具",
                        modelOf(body, rc), usage[0], usage[1], null, latency, true, null);
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
        Map<String, ToolPackage> skillMap = skillApplyResult.isDiscoveryPhase()
                ? skillRuntimeService.aggregateToolPackages() : null;

        int toolRound = 0, totalIn = 0, totalOut = 0;
        long totalStart = System.currentTimeMillis();

        try {
            while (true) {
                llmCallGuard.acquirePermission();
                long start = System.currentTimeMillis();
                Long logId = safeRecordRequest(body, headers, rc);
                AiStreamAccumulator accumulator = adapter.createStreamAccumulator();
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean streamFailed = new AtomicBoolean(false);

                try {
                    EventSourceListener listener = buildListener(connectionId, adapter, accumulator, latch, streamFailed);
                    requestService.postStream(url, headers, JSONUtil.toJsonStr(body), listener);

                    boolean completed = latch.await(5, TimeUnit.MINUTES);
                    int latency = elapsed(start);

                    if (!completed) {
                        llmCallGuard.recordFailure();
                        RuntimeException timeout = new RuntimeException("流式请求超时");
                        safeRecordException(logId, timeout, latency);
                        sendError(connectionId, "timeout", "流式请求超时（5分钟）");
                        sseBridge.complete(connectionId);
                        return;
                    }

                    if (streamFailed.get()) {
                        llmCallGuard.recordFailure();
                    } else {
                        llmCallGuard.recordSuccess();
                    }

                    safeRecordStreamResponse(logId, 200,
                            accumulator.getInputTokens(), accumulator.getOutputTokens(),
                            accumulator.toJson(), latency, accumulator.getRequestId());
                    totalIn += accumulator.getInputTokens();
                    totalOut += accumulator.getOutputTokens();

                    Map<String, Object> responseMap = accumulator.buildResponseMap();
                    List<ToolUseCall> toolCalls = adapter.extractToolUseCalls(responseMap);
                    boolean hasTools = skillApplyResult.isEnabled() && !toolCalls.isEmpty();
                    traceRecorder.recordLlm(logId, hasTools ? "推理·决定调用工具" : "推理·生成回答",
                            modelOf(body, rc), accumulator.getInputTokens(), accumulator.getOutputTokens(),
                            null, latency, !streamFailed.get(), null);

                    if (!skillApplyResult.isEnabled() || toolCalls.isEmpty()) {
                        double elapsed = (System.currentTimeMillis() - totalStart) / 1000.0;
                        logFinalAnswer(connectionId, rc, adapter, responseMap, totalIn, totalOut, elapsed);
                        sendSummary(connectionId, totalIn, totalOut,
                                toolRound > 0 ? toolRound + 1 : 0, traceId, elapsed);
                        sseBridge.complete(connectionId);
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
                        sseBridge.complete(connectionId);
                        return;
                    }

                    sendProgress(connectionId, toolRound + 1, toolCalls, body, adapter);
                    List<ToolExecutionResult> results = skillRuntimeService.executeToolCalls(toolCalls);
                    sendToolResults(connectionId, toolRound + 1, results);

                    if (results.isEmpty()) {
                        double elapsed = (System.currentTimeMillis() - totalStart) / 1000.0;
                        sendSummary(connectionId, totalIn, totalOut, toolRound + 1, traceId, elapsed);
                        sseBridge.complete(connectionId);
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
            sseBridge.complete(connectionId);
        }
    }

    // ------------------------------------------------------------------ final answer log

    /**
     * 流结束时打印 LLM 最终答复全文，便于 Kibana 按 connectionId 复盘整条 RAG 链路。
     * elapsedSeconds 传 -1 表示尚未到收尾阶段（中间轮 tool_use 之间也可调用）。
     */
    private void logFinalAnswer(String connectionId, CallRecordConfig rc, AiProtocolAdapter adapter,
                                 Map<String, Object> responseMap,
                                 int totalIn, int totalOut, double elapsedSeconds) {
        String text = adapter.extractAssistantText(responseMap);
        if (text == null) return;
        if (elapsedSeconds >= 0) {
            log.info("LLM/answer connectionId={} provider={} model={} input_tokens={} output_tokens={} elapsed={}s\n{}",
                    connectionId, rc.provider(), rc.defaultModel(), totalIn, totalOut, elapsedSeconds, text);
        } else {
            log.info("LLM/answer.intermediate connectionId={} provider={} input_tokens={} output_tokens={}\n{}",
                    connectionId, rc.provider(), totalIn, totalOut, text);
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
        sseBridge.sendJson(connectionId, "summary", summary);
    }

    private void sendProgress(String connectionId, int round, List<ToolUseCall> toolCalls,
                              Map<String, Object> body, AiProtocolAdapter adapter) {
        // 工具描述就在请求体的 tools 里，按名取出一并下发，前端可显示「正在调用 X（描述）」
        Map<String, String> descMap = toolDescriptions(body, adapter);
        List<String> toolNames = new ArrayList<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolUseCall call : toolCalls) {
            toolNames.add(call.getToolName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getToolUseId());
            item.put("name", call.getToolName());
            String desc = descMap.get(call.getToolName());
            if (StrUtil.isNotBlank(desc)) item.put("desc", desc);
            item.put("input", call.getInput());
            item.put("status", "running");
            calls.add(item);
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("round", round);
        progress.put("tools", toolNames);
        progress.put("calls", calls);
        sseBridge.sendJson(connectionId, "progress", progress);
    }

    /** 从请求体 tools 里提取「工具名 → 描述」，兼容 Claude({name,description}) 与 OpenAI({function:{name,description}})。 */
    private Map<String, String> toolDescriptions(Map<String, Object> body, AiProtocolAdapter adapter) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            for (Object def : adapter.getToolsList(body)) {
                String name = adapter.getToolName(def);
                if (StrUtil.isBlank(name)) continue;
                String desc = extractToolDesc(def);
                if (StrUtil.isNotBlank(desc)) map.put(name, desc);
            }
        } catch (Exception ignore) {
            // 取不到描述不影响主流程
        }
        return map;
    }

    private String extractToolDesc(Object def) {
        if (!(def instanceof Map<?, ?> m)) return null;
        Object d = m.get("description");
        if (d instanceof String s && StrUtil.isNotBlank(s)) return s;
        Object fn = m.get("function");
        if (fn instanceof Map<?, ?> fm && fm.get("description") instanceof String s && StrUtil.isNotBlank(s)) {
            return s;
        }
        return null;
    }

    private void sendToolResults(String connectionId, int round, List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) return;
        // 先把携带 __citations__ 旁路的工具结果（如 rag.search）抽出来单独发 citations 事件，并从 payload 剥离，
        // 避免富化引用既泄漏进模型上下文（随后 appendToolResultTurn 回传）又重复出现在 tool_result.output。
        surfaceCitations(connectionId, results);
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
        sseBridge.sendJson(connectionId, "tool_result", event);
    }

    /**
     * 把工具结果里 {@link ToolExecutionResult#CITATIONS_SIDECAR_KEY} 旁路的引用抽出来单独下发 citations 事件，
     * 并从 payload 剥离（使其不进模型上下文、也不重复出现在 tool_result.output）。
     * 前端 useSSE 对 citations 不假设到达时序——存下、流结束时随消息落库，故工具轮后再发完全兼容。
     */
    private void surfaceCitations(String connectionId, List<ToolExecutionResult> results) {
        for (ToolExecutionResult r : results) {
            if (!(r.getPayload() instanceof Map<?, ?> raw)) continue;
            Object citations = raw.get(ToolExecutionResult.CITATIONS_SIDECAR_KEY);
            if (citations == null) continue;
            try {
                ((Map<?, ?>) raw).remove(ToolExecutionResult.CITATIONS_SIDECAR_KEY);
            } catch (UnsupportedOperationException e) {
                // payload 不可变时剥离失败：放弃剥离（模型会多看到这段引用），但不丢 citations 事件；打日志以便及早发现。
                log.warn("citations 旁路剥离失败（payload 不可变）tool={} connectionId={}", r.getToolName(), connectionId);
            }
            sseBridge.sendJson(connectionId, "citations", citations);
        }
    }

    private void sendError(String connectionId, String error, String message) {
        Map<String, Object> errorData = new LinkedHashMap<>();
        errorData.put("error", error);
        errorData.put("message", message);
        sseBridge.sendJson(connectionId, "error", errorData);
    }

    // ------------------------------------------------------------------ stream listener

    private EventSourceListener buildListener(String connectionId, AiProtocolAdapter adapter,
                                               AiStreamAccumulator accumulator, CountDownLatch latch,
                                               AtomicBoolean streamFailed) {
        return new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.info("流式连接已建立, connectionId={}", connectionId);
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                sseBridge.send(connectionId, adapter.getDeltaEventType(), data);
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
                streamFailed.set(true);
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

    /** 熔断计数：2xx 记成功；429/5xx 记失败；其它 4xx（客户端错误）不计入。连接级异常在调用处单独记。 */
    private void recordGuardOutcome(Integer httpStatus) {
        if (httpStatus == null) {
            return;
        }
        if (httpStatus >= 200 && httpStatus < 300) {
            llmCallGuard.recordSuccess();
        } else if (httpStatus == 429 || httpStatus >= 500) {
            llmCallGuard.recordFailure();
        }
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

    /** 取本轮实际使用的模型名：请求体 model 优先，回退调用配置的 defaultModel。 */
    private String modelOf(Map<String, Object> body, CallRecordConfig rc) {
        Object m = body == null ? null : body.get("model");
        if (m != null && StrUtil.isNotBlank(m.toString())) {
            return m.toString();
        }
        return rc == null ? null : rc.defaultModel();
    }
}

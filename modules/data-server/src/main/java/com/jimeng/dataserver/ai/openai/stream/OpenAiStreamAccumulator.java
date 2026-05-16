package com.jimeng.dataserver.ai.openai.stream;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiStreamAccumulator implements AiStreamAccumulator {

    private final List<Object> events = new ArrayList<>();
    private final StringBuilder contentBuilder = new StringBuilder();
    private final Map<Integer, ToolCallChunk> toolCallChunks = new LinkedHashMap<>();
    private String requestId;
    private String model;
    private String role = "assistant";
    private String finishReason;
    private int inputTokens;
    private int outputTokens;

    @Override
    public void accumulateEvent(String eventType, String data) {
        if (StrUtil.isBlank(data) || "[DONE]".equals(data)) return;
        events.add(data);
        if (!JSONUtil.isTypeJSON(data)) return;
        try {
            var root = JSONUtil.parseObj(data);
            if (StrUtil.isBlank(requestId)) requestId = root.getStr("id");
            if (StrUtil.isBlank(model)) model = root.getStr("model");
            var usage = root.getJSONObject("usage");
            if (usage != null) {
                inputTokens = toInt(usage.get("prompt_tokens"), usage.get("input_tokens"));
                outputTokens = toInt(usage.get("completion_tokens"), usage.get("output_tokens"));
            }
            accumulateChoiceDelta(root);
        } catch (Exception ignored) {
            // non-standard heartbeat from some proxies – safe to ignore
        }
    }

    @Override
    public Map<String, Object> buildResponseMap() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", contentBuilder.isEmpty() ? "" : contentBuilder.toString());
        List<Map<String, Object>> toolCalls = buildToolCalls();
        if (!toolCalls.isEmpty()) message.put("tool_calls", toolCalls);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", requestId);
        response.put("object", "chat.completion");
        response.put("model", model);
        response.put("choices", List.of(choice));
        response.put("usage", Map.of(
                "prompt_tokens", inputTokens,
                "completion_tokens", outputTokens,
                "total_tokens", inputTokens + outputTokens
        ));
        return response;
    }

    @Override
    public boolean hasToolUse() {
        return !toolCallChunks.isEmpty();
    }

    @Override
    public int getInputTokens() { return inputTokens; }

    @Override
    public int getOutputTokens() { return outputTokens; }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public String toJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", events);
        payload.put("usage", Map.of(
                "input_tokens", inputTokens,
                "output_tokens", outputTokens,
                "total_tokens", inputTokens + outputTokens
        ));
        return JSONUtil.toJsonStr(payload);
    }

    private void accumulateChoiceDelta(cn.hutool.json.JSONObject root) {
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices)) return;
        for (Object choiceObj : choices) {
            if (!(choiceObj instanceof Map<?, ?> choiceMap)) continue;
            Object fr = choiceMap.get("finish_reason");
            if (fr != null) finishReason = String.valueOf(fr);
            Object deltaObj = choiceMap.get("delta");
            if (!(deltaObj instanceof Map<?, ?> deltaMap)) continue;
            Object roleObj = deltaMap.get("role");
            if (roleObj != null) role = String.valueOf(roleObj);
            Object contentObj = deltaMap.get("content");
            if (contentObj != null) contentBuilder.append(contentObj);
            Object toolCallsObj = deltaMap.get("tool_calls");
            if (toolCallsObj instanceof List<?> tc) accumulateToolCalls(tc);
            Object fcObj = deltaMap.get("function_call");
            if (fcObj instanceof Map<?, ?> fc) accumulateLegacyFunctionCall(fc);
        }
    }

    private void accumulateToolCalls(List<?> toolCalls) {
        for (Object tcObj : toolCalls) {
            if (!(tcObj instanceof Map<?, ?> tcMap)) continue;
            int index = toInt(tcMap.get("index"), 0);
            ToolCallChunk chunk = toolCallChunks.computeIfAbsent(index, k -> new ToolCallChunk());
            if (tcMap.get("id") != null) chunk.id = String.valueOf(tcMap.get("id"));
            if (tcMap.get("type") != null) chunk.type = String.valueOf(tcMap.get("type"));
            if (tcMap.get("function") instanceof Map<?, ?> fn) {
                Object name = fn.get("name");
                if (name != null && StrUtil.isNotBlank(String.valueOf(name))) chunk.name = String.valueOf(name);
                Object args = fn.get("arguments");
                if (args != null) chunk.argumentsBuilder.append(args);
            }
        }
    }

    private void accumulateLegacyFunctionCall(Map<?, ?> fn) {
        ToolCallChunk chunk = toolCallChunks.computeIfAbsent(0, k -> new ToolCallChunk());
        if (StrUtil.isBlank(chunk.id)) chunk.id = requestId;
        chunk.type = "function";
        Object name = fn.get("name");
        if (name != null && StrUtil.isNotBlank(String.valueOf(name))) chunk.name = String.valueOf(name);
        Object args = fn.get("arguments");
        if (args != null) chunk.argumentsBuilder.append(args);
    }

    private List<Map<String, Object>> buildToolCalls() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolCallChunk chunk : toolCallChunks.values()) {
            if (StrUtil.isBlank(chunk.id) || StrUtil.isBlank(chunk.name)) {
                log.warn("忽略不完整的OpenAI流式tool_call, id={}, name={}", chunk.id, chunk.name);
                continue;
            }
            String arguments = chunk.argumentsBuilder.toString();
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", chunk.name);
            function.put("arguments", StrUtil.isBlank(arguments) ? "{}" : arguments);
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", chunk.id);
            toolCall.put("type", StrUtil.isBlank(chunk.type) ? "function" : chunk.type);
            toolCall.put("function", function);
            result.add(toolCall);
        }
        return result;
    }

    private int toInt(Object first, Object second) {
        Object v = first != null ? first : second;
        if (v instanceof Number n) return n.intValue();
        if (v == null || StrUtil.isBlank(String.valueOf(v))) return 0;
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private int toInt(Object v) { return toInt(v, null); }

    private static class ToolCallChunk {
        String id;
        String type;
        String name;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }
}

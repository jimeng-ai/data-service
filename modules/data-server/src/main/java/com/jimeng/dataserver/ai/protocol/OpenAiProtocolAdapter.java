package com.jimeng.dataserver.ai.protocol;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import com.jimeng.dataserver.ai.openai.stream.OpenAiStreamAccumulator;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class OpenAiProtocolAdapter implements AiProtocolAdapter {

    @Override
    @SuppressWarnings("unchecked")
    public void appendSystemContent(Map<String, Object> body, String text) {
        if (StrUtil.isBlank(text)) return;
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;

        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> rawMessage)) continue;
            Map<String, Object> message = castMap(rawMessage);
            String role = str(message.get("role"));
            if (!"system".equals(role) && !"developer".equals(role)) continue;
            Object contentObj = message.get("content");
            String existing = str(contentObj);
            message.put("content", StrUtil.isBlank(existing) ? text : existing + "\n\n" + text);
            return;
        }

        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", text);
        messages.add(0, systemMessage);
    }

    @Override
    public List<Object> getToolsList(Map<String, Object> body) {
        Object tools = body.get("tools");
        return tools instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    @Override
    public void setToolsList(Map<String, Object> body, List<Object> tools) {
        if (!tools.isEmpty()) body.put("tools", tools);
    }

    @Override
    public Map<String, Object> convertToolDef(SkillToolDefinition def) {
        return def.toOpenAiTool();
    }

    @Override
    public String getToolName(Object toolDef) {
        if (!(toolDef instanceof Map<?, ?> m)) return "";
        Object fn = m.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = fnMap.get("name");
            return name == null ? "" : String.valueOf(name).trim();
        }
        Object name = m.get("name");
        return name == null ? "" : String.valueOf(name).trim();
    }

    @Override
    public Object buildActivateSkillsToolDef() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> skillNamesField = new LinkedHashMap<>();
        skillNamesField.put("type", "array");
        skillNamesField.put("description", "需要激活的 Skill 名称列表");
        skillNamesField.put("items", items);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_names", skillNamesField);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("skill_names"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "activate_skills");
        function.put("description", "激活指定的 Skill 以获取其完整指令和工具集。"
                + "当用户的问题需要使用某个 Skill 的能力时，调用此工具并传入需要激活的 Skill 名称列表。");
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    @Override
    public void ensureToolChoiceAuto(Map<String, Object> body) {
        if (body != null && !body.containsKey("tool_choice")) body.put("tool_choice", "auto");
    }

    @Override
    public void removeToolByName(Map<String, Object> body, String name) {
        Object toolsObj = body.get("tools");
        if (!(toolsObj instanceof List<?> list)) return;
        List<Object> filtered = new ArrayList<>();
        for (Object t : list) {
            if (!(t instanceof Map<?, ?> m) || !name.equals(getFunctionName(m))) filtered.add(t);
        }
        body.put("tools", filtered);
    }

    @Override
    public List<ToolUseCall> extractToolUseCalls(Map<String, Object> responseMap) {
        if (responseMap == null) return Collections.emptyList();
        Object choicesObj = responseMap.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return Collections.emptyList();

        List<ToolUseCall> calls = new ArrayList<>();
        for (Object choiceObj : choices) {
            if (!(choiceObj instanceof Map<?, ?> choiceMap)) continue;
            Object msgObj = choiceMap.get("message");
            if (!(msgObj instanceof Map<?, ?> msgMap)) {
                msgObj = choiceMap.get("delta");
            }
            if (!(msgObj instanceof Map<?, ?> msg)) continue;
            Object tcObj = msg.get("tool_calls");
            if (!(tcObj instanceof List<?> toolCalls)) continue;
            for (Object tcRaw : toolCalls) {
                if (!(tcRaw instanceof Map<?, ?> tcMap)) continue;
                String id = str(tcMap.get("id"));
                Object fnObj = tcMap.get("function");
                if (!(fnObj instanceof Map<?, ?> fnMap)) continue;
                String toolName = str(fnMap.get("name"));
                if (StrUtil.isBlank(id) || StrUtil.isBlank(toolName)) {
                    log.warn("忽略不完整的OpenAI tool_call, id={}, name={}", id, toolName);
                    continue;
                }
                Map<String, Object> input = parseArguments(fnMap.get("arguments"));
                calls.add(new ToolUseCall(id, toolName, input));
            }
        }
        return calls;
    }

    @Override
    public int[] extractUsage(Map<String, Object> responseMap) {
        Object u = responseMap == null ? null : responseMap.get("usage");
        if (!(u instanceof Map<?, ?> usage)) return new int[]{0, 0};
        int input = toInt(usage.get("prompt_tokens"), usage.get("input_tokens"));
        int output = toInt(usage.get("completion_tokens"), usage.get("output_tokens"));
        return new int[]{input, output};
    }

    @Override
    @SuppressWarnings("unchecked")
    public void appendToolResultTurn(Map<String, Object> body, Map<String, Object> responseMap,
                                      List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) return;
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;

        Map<String, Object> assistantMsg = extractNormalizedAssistantMessage(responseMap);
        if (!assistantMsg.isEmpty()) messages.add(assistantMsg);
        if (!hasToolCalls(assistantMsg)) {
            log.warn("OpenAI assistant消息不包含有效tool_calls，跳过追加tool结果");
            return;
        }

        Set<String> validIds = extractToolCallIds(assistantMsg);
        for (ToolExecutionResult result : results) {
            if (!validIds.contains(result.getToolUseId())) {
                log.warn("跳过无匹配assistant tool_call的tool结果, id={}", result.getToolUseId());
                continue;
            }
            Map<String, Object> toolMsg = new LinkedHashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", result.getToolUseId());
            toolMsg.put("name", result.getToolName());
            toolMsg.put("content", JSONUtil.toJsonStr(result.getPayload()));
            messages.add(toolMsg);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void appendActivationTurn(Map<String, Object> body, Map<String, Object> responseMap,
                                      ActivationResult activation) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;

        Map<String, Object> assistantMsg = extractNormalizedAssistantMessage(responseMap);
        if (!assistantMsg.isEmpty()) messages.add(assistantMsg);
        Map<String, Object> toolResultBlock = activation.getToolResultBlock();
        if (!toolResultBlock.isEmpty()) messages.add(new LinkedHashMap<>(toolResultBlock));
    }

    @Override
    public Map<String, Object> buildActivationToolResultBlock(String toolUseId, String toolName,
                                                               Object payload, boolean isError) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolUseId);
        msg.put("name", toolName);
        msg.put("content", JSONUtil.toJsonStr(payload));
        return msg;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object buildAggregatedResponse(Map<String, Object> responseMap,
                                           int totalInput, int totalOutput, int toolRounds, String traceId) {
        Map<String, Object> usage = responseMap.get("usage") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) responseMap.get("usage")) : new LinkedHashMap<>();
        usage.put("prompt_tokens", totalInput);
        usage.put("completion_tokens", totalOutput);
        usage.put("total_tokens", totalInput + totalOutput);
        responseMap.put("usage", usage);
        if (StrUtil.isNotBlank(traceId)) responseMap.put("x-trace-id", traceId);
        responseMap.put("tool_rounds", toolRounds);
        return responseMap;
    }

    @Override
    public AiStreamAccumulator createStreamAccumulator() {
        return new OpenAiStreamAccumulator();
    }

    @Override
    public String getDeltaEventType() {
        return "openai-delta";
    }

    @Override
    public boolean isDoneSignal(String data) {
        return "[DONE]".equals(data);
    }

    // ---- private helpers ----

    private Map<String, Object> extractNormalizedAssistantMessage(Map<String, Object> responseMap) {
        Object choicesObj = responseMap == null ? null : responseMap.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return Collections.emptyMap();
        Object choiceObj = choices.get(0);
        if (!(choiceObj instanceof Map<?, ?> choiceMap)) return Collections.emptyMap();
        Object msgObj = choiceMap.get("message");
        if (!(msgObj instanceof Map<?, ?> rawMessage)) return Collections.emptyMap();

        Map<String, Object> msg = castMap(rawMessage);
        msg.putIfAbsent("role", "assistant");
        normalizeToolCalls(msg);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private void normalizeToolCalls(Map<String, Object> msg) {
        Object tcObj = msg.get("tool_calls");
        if (!(tcObj instanceof List<?> toolCalls) || toolCalls.isEmpty()) return;
        if (msg.get("content") == null) msg.put("content", "");
        List<Object> normalized = new ArrayList<>();
        for (Object tcRaw : toolCalls) {
            if (!(tcRaw instanceof Map<?, ?> rawTc)) continue;
            Map<String, Object> tc = castMap(rawTc);
            if (StrUtil.isBlank(str(tc.get("id")))) continue;
            Object fnObj = tc.get("function");
            if (!(fnObj instanceof Map<?, ?> rawFn)) continue;
            Map<String, Object> fn = castMap(rawFn);
            if (StrUtil.isBlank(str(fn.get("name")))) continue;
            fn.put("arguments", fn.get("arguments") == null ? "{}" : String.valueOf(fn.get("arguments")));
            tc.put("type", StrUtil.blankToDefault(str(tc.get("type")), "function"));
            tc.put("function", fn);
            normalized.add(tc);
        }
        msg.put("tool_calls", normalized);
    }

    private boolean hasToolCalls(Map<String, Object> msg) {
        if (msg == null || msg.isEmpty()) return false;
        Object tc = msg.get("tool_calls");
        return tc instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> assistantMsg) {
        Set<String> ids = new LinkedHashSet<>();
        Object tcObj = assistantMsg.get("tool_calls");
        if (!(tcObj instanceof List<?> toolCalls)) return ids;
        for (Object tcRaw : toolCalls) {
            if (!(tcRaw instanceof Map<?, ?> tc)) continue;
            String id = str(tc.get("id"));
            if (StrUtil.isNotBlank(id)) ids.add(id);
        }
        return ids;
    }

    private Map<String, Object> parseArguments(Object argumentsObj) {
        if (argumentsObj instanceof Map<?, ?> m) return castMap(m);
        String arguments = str(argumentsObj);
        if (StrUtil.isBlank(arguments) || !JSONUtil.isTypeJSON(arguments)) return Collections.emptyMap();
        try { return castMap(JSONUtil.parseObj(arguments)); }
        catch (Exception e) { return Collections.emptyMap(); }
    }

    private String getFunctionName(Map<?, ?> tool) {
        Object fn = tool.get("function");
        if (fn instanceof Map<?, ?> fnMap) return str(fnMap.get("name"));
        return str(tool.get("name"));
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private int toInt(Object first, Object second) {
        Object v = first != null ? first : second;
        if (v instanceof Number n) return n.intValue();
        if (v == null || StrUtil.isBlank(String.valueOf(v))) return 0;
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> { if (k != null) m.put(String.valueOf(k), v); });
        return m;
    }
}

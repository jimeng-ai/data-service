package com.jimeng.dataserver.ai.protocol;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.claude.stream.ClaudeStreamEventAccumulator;
import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ClaudeProtocolAdapter implements AiProtocolAdapter {

    @Override
    public void appendSystemContent(Map<String, Object> body, String text) {
        if (StrUtil.isBlank(text)) return;
        Object existing = body.get("system");
        if (existing == null) {
            body.put("system", text);
        } else if (existing instanceof String s) {
            body.put("system", StrUtil.isBlank(s) ? text : s + "\n\n" + text);
        } else if (existing instanceof List<?> blocks) {
            List<Object> list = new ArrayList<>(blocks);
            Map<String, Object> newBlock = new LinkedHashMap<>();
            newBlock.put("type", "text");
            newBlock.put("text", text);
            list.add(newBlock);
            body.put("system", list);
        } else {
            body.put("system", existing + "\n\n" + text);
        }
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
        return def.toClaudeTool();
    }

    @Override
    public String getToolName(Object toolDef) {
        if (toolDef instanceof Map<?, ?> m) {
            Object name = m.get("name");
            return name == null ? "" : String.valueOf(name).trim();
        }
        return "";
    }

    @Override
    public Object buildActivateSkillsToolDef() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "activate_skills");
        tool.put("description", "激活指定的 Skill 以获取其完整指令和工具集。"
                + "当用户的问题需要使用某个 Skill 的能力时，调用此工具并传入需要激活的 Skill 名称列表。");
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> skillNamesField = new LinkedHashMap<>();
        skillNamesField.put("type", "array");
        skillNamesField.put("description", "需要激活的 Skill 名称列表");
        skillNamesField.put("items", items);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_names", skillNamesField);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("skill_names"));
        tool.put("input_schema", schema);
        return tool;
    }

    @Override
    public void ensureToolChoiceAuto(Map<String, Object> body) {
        if (body == null || body.containsKey("tool_choice")) return;
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("type", "auto");
        body.put("tool_choice", auto);
    }

    @Override
    public void removeToolByName(Map<String, Object> body, String name) {
        Object toolsObj = body.get("tools");
        if (!(toolsObj instanceof List<?> list)) return;
        List<Object> filtered = new ArrayList<>();
        for (Object t : list) {
            if (!(t instanceof Map<?, ?> m) || !name.equals(m.get("name"))) filtered.add(t);
        }
        body.put("tools", filtered);
    }

    @Override
    public List<ToolUseCall> extractToolUseCalls(Map<String, Object> responseMap) {
        if (responseMap == null) return Collections.emptyList();
        Object contentObj = responseMap.get("content");
        if (!(contentObj instanceof List<?> contentList)) return Collections.emptyList();
        List<ToolUseCall> calls = new ArrayList<>();
        for (Object blockObj : contentList) {
            if (!(blockObj instanceof Map<?, ?> blockMap)) continue;
            if (!"tool_use".equals(blockMap.get("type"))) continue;
            String id = str(blockMap.get("id"));
            String toolName = str(blockMap.get("name"));
            Object inputObj = blockMap.get("input");
            Map<String, Object> input = inputObj instanceof Map<?, ?>
                    ? castMap((Map<?, ?>) inputObj) : Collections.emptyMap();
            calls.add(new ToolUseCall(id, toolName, input));
        }
        return calls;
    }

    @Override
    public int[] extractUsage(Map<String, Object> responseMap) {
        Object u = responseMap == null ? null : responseMap.get("usage");
        if (!(u instanceof Map<?, ?> usage)) return new int[]{0, 0};
        return new int[]{toInt(usage.get("input_tokens")), toInt(usage.get("output_tokens"))};
    }

    @Override
    public String extractAssistantText(Map<String, Object> responseMap) {
        if (responseMap == null) return null;
        Object content = responseMap.get("content");
        if (!(content instanceof List<?> blocks)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> m)) continue;
            if ("text".equals(m.get("type"))) {
                Object t = m.get("text");
                if (t != null) sb.append(t);
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void appendToolResultTurn(Map<String, Object> body, Map<String, Object> responseMap,
                                      List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) return;
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;

        Object contentObj = responseMap.get("content");
        List<Object> assistantContent = contentObj instanceof List<?>
                ? new ArrayList<>((List<?>) contentObj) : Collections.emptyList();
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", assistantContent);
        messages.add(assistantMsg);

        List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
        for (ToolExecutionResult result : results) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.getToolUseId());
            block.put("content", JSONUtil.toJsonStr(result.getPayload()));
            if (!result.isSuccess()) block.put("is_error", true);
            toolResultBlocks.add(block);
        }
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", toolResultBlocks);
        messages.add(userMsg);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void appendActivationTurn(Map<String, Object> body, Map<String, Object> responseMap,
                                      ActivationResult activation) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;

        Object contentObj = responseMap.get("content");
        List<Object> assistantContent = contentObj instanceof List<?>
                ? new ArrayList<>((List<?>) contentObj) : Collections.emptyList();
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", assistantContent);
        messages.add(assistantMsg);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(activation.getToolResultBlock()));
        messages.add(userMsg);
    }

    @Override
    public Map<String, Object> buildActivationToolResultBlock(String toolUseId, String toolName,
                                                               Object payload, boolean isError) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", JSONUtil.toJsonStr(payload));
        if (isError) block.put("is_error", true);
        return block;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object buildAggregatedResponse(Map<String, Object> responseMap,
                                           int totalInput, int totalOutput, int toolRounds, String traceId) {
        Map<String, Object> usage = responseMap.get("usage") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) responseMap.get("usage")) : new LinkedHashMap<>();
        usage.put("input_tokens", totalInput);
        usage.put("output_tokens", totalOutput);
        usage.put("total_tokens", totalInput + totalOutput);
        responseMap.put("usage", usage);
        if (StrUtil.isNotBlank(traceId)) responseMap.put("x-trace-id", traceId);
        responseMap.put("tool_rounds", toolRounds);
        return responseMap;
    }

    @Override
    public AiStreamAccumulator createStreamAccumulator() {
        return new ClaudeStreamEventAccumulator();
    }

    @Override
    public String getDeltaEventType() {
        return "claude-delta";
    }

    @Override
    public boolean isDoneSignal(String data) {
        return false;
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> { if (k != null) m.put(String.valueOf(k), v); });
        return m;
    }
}

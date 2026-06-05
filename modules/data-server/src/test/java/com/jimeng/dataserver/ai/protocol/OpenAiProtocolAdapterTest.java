package com.jimeng.dataserver.ai.protocol;

import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯函数回归网（阶段 3.2）：OpenAiProtocolAdapter 的 choices[]/tool_calls 形态抽取与系统消息拼接。
 * 与 {@link ClaudeProtocolAdapterTest} 一起锁住「加同形态新厂商只改 yml+@Bean」的前提——两套形态各自不回归。
 */
class OpenAiProtocolAdapterTest {

    private OpenAiProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiProtocolAdapter();
    }

    @Test
    void extractAssistantText_readsFirstChoiceMessageContent() {
        Map<String, Object> resp = Map.of("choices", List.of(
                Map.of("message", Map.of("content", "  hi there  "))));
        assertEquals("hi there", adapter.extractAssistantText(resp));
        assertNull(adapter.extractAssistantText(Map.of("choices", List.of())));
    }

    @Test
    void extractToolUseCalls_parsesJsonStringArguments() {
        Map<String, Object> fn = Map.of("name", "get_weather", "arguments", "{\"city\":\"SF\"}");
        Map<String, Object> tc = Map.of("id", "call_1", "type", "function", "function", fn);
        Map<String, Object> resp = Map.of("choices", List.of(
                Map.of("message", Map.of("tool_calls", List.of(tc)))));

        List<ToolUseCall> calls = adapter.extractToolUseCalls(resp);
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).getToolUseId());
        assertEquals("get_weather", calls.get(0).getToolName());
        assertEquals("SF", calls.get(0).getInput().get("city"));
    }

    @Test
    void extractToolUseCalls_skipsIncompleteCall() {
        // 缺 id 的 tool_call 应被丢弃（不完整）
        Map<String, Object> fn = Map.of("name", "x", "arguments", "{}");
        Map<String, Object> tc = Map.of("type", "function", "function", fn);
        Map<String, Object> resp = Map.of("choices", List.of(
                Map.of("message", Map.of("tool_calls", List.of(tc)))));
        assertTrue(adapter.extractToolUseCalls(resp).isEmpty());
    }

    @Test
    void extractUsage_readsPromptCompletionTokens() {
        Map<String, Object> resp = Map.of("usage", Map.of("prompt_tokens", 7, "completion_tokens", 9));
        assertEquals(7, adapter.extractUsage(resp)[0]);
        assertEquals(9, adapter.extractUsage(resp)[1]);
    }

    @Test
    void getToolName_readsNestedFunctionName() {
        assertEquals("foo", adapter.getToolName(Map.of("function", Map.of("name", "foo"))));
        assertEquals("bar", adapter.getToolName(Map.of("name", "bar")));
    }

    @Test
    void appendSystemContent_appendsToExistingSystemMessage() {
        // 回归：appendSystemContent 命中已有 system 消息时，追加必须落到 messages 列表里的那条消息上。
        // 修复前 castMap() 复制后只改副本、未写回，追加被静默丢弃。
        Map<String, Object> body = new LinkedHashMap<>();
        List<Object> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "system", "content", "base")));
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "q")));
        body.put("messages", messages);

        adapter.appendSystemContent(body, "more");

        Map<?, ?> systemMsg = (Map<?, ?>) messages.get(0);
        assertEquals("base\n\nmore", systemMsg.get("content"));
        assertEquals(2, messages.size());
    }

    @Test
    void appendSystemContent_insertsSystemMessageWhenAbsent() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Object> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "q")));
        body.put("messages", messages);

        adapter.appendSystemContent(body, "sys");

        assertEquals(2, messages.size());
        Map<?, ?> first = (Map<?, ?>) messages.get(0);
        assertEquals("system", first.get("role"));
        assertEquals("sys", first.get("content"));
    }

    @Test
    void buildAggregatedResponse_overwritesUsageAndStampsRounds() {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) adapter.buildAggregatedResponse(responseMap, 3, 4, 1, "t");

        Map<?, ?> usage = (Map<?, ?>) out.get("usage");
        assertEquals(3, usage.get("prompt_tokens"));
        assertEquals(4, usage.get("completion_tokens"));
        assertEquals(7, usage.get("total_tokens"));
        assertEquals(1, out.get("tool_rounds"));
    }
}

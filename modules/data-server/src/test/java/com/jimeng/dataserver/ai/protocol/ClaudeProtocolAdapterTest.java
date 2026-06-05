package com.jimeng.dataserver.ai.protocol;

import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
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
 * 纯函数回归网（阶段 3.2）：ClaudeProtocolAdapter 是接口 + 构造器注入，可直接 new。
 * 锁住 Anthropic content[] 形态的 tool_use / text / usage 抽取与多轮消息拼接契约。
 */
class ClaudeProtocolAdapterTest {

    private ClaudeProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ClaudeProtocolAdapter();
    }

    @Test
    void extractAssistantText_concatsTextBlocksAndTrims() {
        Map<String, Object> resp = Map.of("content", List.of(
                block("text", "text", "Hello "),
                block("tool_use", null, null),
                block("text", "text", "world  ")
        ));
        assertEquals("Hello world", adapter.extractAssistantText(resp));
    }

    @Test
    void extractAssistantText_noTextBlocks_returnsNull() {
        Map<String, Object> resp = Map.of("content", List.of(block("tool_use", null, null)));
        assertNull(adapter.extractAssistantText(resp));
        assertNull(adapter.extractAssistantText(null));
    }

    @Test
    void extractToolUseCalls_readsIdNameInput() {
        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "tu_1");
        toolUse.put("name", "search");
        toolUse.put("input", Map.of("q", "hi"));
        Map<String, Object> resp = Map.of("content", List.of(toolUse, block("text", "text", "ignored")));

        List<ToolUseCall> calls = adapter.extractToolUseCalls(resp);
        assertEquals(1, calls.size());
        assertEquals("tu_1", calls.get(0).getToolUseId());
        assertEquals("search", calls.get(0).getToolName());
        assertEquals("hi", calls.get(0).getInput().get("q"));
    }

    @Test
    void extractUsage_readsAnthropicTokenNames() {
        Map<String, Object> resp = Map.of("usage", Map.of("input_tokens", 12, "output_tokens", 34));
        assertEquals(12, adapter.extractUsage(resp)[0]);
        assertEquals(34, adapter.extractUsage(resp)[1]);
        assertEquals(0, adapter.extractUsage(Map.of())[0]);
    }

    @Test
    void getToolName_readsTopLevelName() {
        assertEquals("foo", adapter.getToolName(Map.of("name", "foo")));
        assertEquals("", adapter.getToolName("not a map"));
    }

    @Test
    void appendSystemContent_createsThenConcatenates() {
        Map<String, Object> body = new LinkedHashMap<>();
        adapter.appendSystemContent(body, "first");
        assertEquals("first", body.get("system"));
        adapter.appendSystemContent(body, "second");
        assertEquals("first\n\nsecond", body.get("system"));
        // 空白文本不改写
        adapter.appendSystemContent(body, "   ");
        assertEquals("first\n\nsecond", body.get("system"));
    }

    @Test
    void appendToolResultTurn_appendsAssistantThenUserToolResult() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", new ArrayList<>(List.of(Map.of("role", "user", "content", "hi"))));
        Map<String, Object> responseMap = Map.of("content", List.of(block("text", "text", "thinking")));
        List<ToolExecutionResult> results = List.of(new ToolExecutionResult("tu_1", "search", true, Map.of("r", 1)));

        adapter.appendToolResultTurn(body, responseMap, results);

        List<?> messages = (List<?>) body.get("messages");
        assertEquals(3, messages.size());
        assertEquals("assistant", ((Map<?, ?>) messages.get(1)).get("role"));
        Map<?, ?> userMsg = (Map<?, ?>) messages.get(2);
        assertEquals("user", userMsg.get("role"));
        Map<?, ?> trBlock = (Map<?, ?>) ((List<?>) userMsg.get("content")).get(0);
        assertEquals("tool_result", trBlock.get("type"));
        assertEquals("tu_1", trBlock.get("tool_use_id"));
    }

    @Test
    void buildAggregatedResponse_overwritesUsageAndStampsRounds() {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("usage", new LinkedHashMap<>(Map.of("input_tokens", 1)));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) adapter.buildAggregatedResponse(responseMap, 15, 28, 2, "trace-9");

        Map<?, ?> usage = (Map<?, ?>) out.get("usage");
        assertEquals(15, usage.get("input_tokens"));
        assertEquals(28, usage.get("output_tokens"));
        assertEquals(43, usage.get("total_tokens"));
        assertEquals(2, out.get("tool_rounds"));
        assertEquals("trace-9", out.get("x-trace-id"));
    }

    private Map<String, Object> block(String type, String textKey, String textVal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (textKey != null) {
            m.put(textKey, textVal);
        }
        return m;
    }
}

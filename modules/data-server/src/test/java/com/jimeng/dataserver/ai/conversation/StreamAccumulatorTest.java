package com.jimeng.dataserver.ai.conversation;

import com.jimeng.dataserver.ai.claude.stream.ClaudeStreamEventAccumulator;
import com.jimeng.dataserver.ai.openai.stream.OpenAiStreamAccumulator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯函数回归网（阶段 3.2）：两个流式累积器把分片 SSE 事件重建成「与同步响应同形态」的 Map，
 * 该 Map 随后喂给 ProtocolAdapter.extractToolUseCalls / extractAssistantText，是流式链路的关键拼接点。
 */
class StreamAccumulatorTest {

    // ---------------------------------------------------------------- Claude

    @Test
    void claude_textStream_buildsMessageWithUsage() {
        ClaudeStreamEventAccumulator acc = new ClaudeStreamEventAccumulator();
        acc.accumulateEvent("message_start",
                "{\"message\":{\"id\":\"msg_1\",\"model\":\"claude-x\",\"role\":\"assistant\","
                        + "\"usage\":{\"input_tokens\":11}}}");
        acc.accumulateEvent("content_block_start",
                "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        acc.accumulateEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}");
        acc.accumulateEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}");
        acc.accumulateEvent("content_block_stop", "{\"index\":0}");
        acc.accumulateEvent("message_delta",
                "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}");
        acc.accumulateEvent("message_stop", "{}");

        Map<String, Object> resp = acc.buildResponseMap();
        assertEquals("msg_1", resp.get("id"));
        assertEquals("assistant", resp.get("role"));
        assertEquals("end_turn", resp.get("stop_reason"));

        List<?> content = (List<?>) resp.get("content");
        Map<?, ?> textBlock = (Map<?, ?>) content.get(0);
        assertEquals("text", textBlock.get("type"));
        assertEquals("Hello world", textBlock.get("text"));

        Map<?, ?> usage = (Map<?, ?>) resp.get("usage");
        assertEquals(11, usage.get("input_tokens"));
        assertEquals(7, usage.get("output_tokens"));
        assertEquals(11, acc.getInputTokens());
        assertEquals(7, acc.getOutputTokens());
        assertFalse(acc.hasToolUse());
    }

    @Test
    void claude_toolUseStream_reassemblesInputJson() {
        ClaudeStreamEventAccumulator acc = new ClaudeStreamEventAccumulator();
        acc.accumulateEvent("message_start",
                "{\"message\":{\"id\":\"msg_2\",\"model\":\"claude-x\",\"role\":\"assistant\"}}");
        acc.accumulateEvent("content_block_start",
                "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"search\"}}");
        acc.accumulateEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\"}}");
        acc.accumulateEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"hi\\\"}\"}}");
        acc.accumulateEvent("content_block_stop", "{\"index\":0}");

        assertTrue(acc.hasToolUse());
        Map<String, Object> resp = acc.buildResponseMap();
        Map<?, ?> toolBlock = (Map<?, ?>) ((List<?>) resp.get("content")).get(0);
        assertEquals("tool_use", toolBlock.get("type"));
        assertEquals("tu_1", toolBlock.get("id"));
        assertEquals("search", toolBlock.get("name"));
        assertEquals("hi", ((Map<?, ?>) toolBlock.get("input")).get("q"));
    }

    // ---------------------------------------------------------------- OpenAI

    @Test
    void openai_textStream_buildsChatCompletionWithUsage() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accumulateEvent("message",
                "{\"id\":\"cmpl_1\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}");
        acc.accumulateEvent("message",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");

        Map<String, Object> resp = acc.buildResponseMap();
        assertEquals("cmpl_1", resp.get("id"));
        assertEquals("chat.completion", resp.get("object"));

        Map<?, ?> choice = (Map<?, ?>) ((List<?>) resp.get("choices")).get(0);
        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        assertEquals("assistant", message.get("role"));
        assertEquals("Hello world", message.get("content"));
        assertEquals("stop", choice.get("finish_reason"));

        Map<?, ?> usage = (Map<?, ?>) resp.get("usage");
        assertEquals(10, usage.get("prompt_tokens"));
        assertEquals(5, usage.get("completion_tokens"));
        assertEquals(15, usage.get("total_tokens"));
        assertFalse(acc.hasToolUse());
    }

    @Test
    void openai_toolCallStream_reassemblesArgumentsAcrossChunks() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accumulateEvent("message",
                "{\"id\":\"cmpl_2\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                        + "[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}");
        acc.accumulateEvent("message",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                        + "[{\"index\":0,\"function\":{\"arguments\":\"\\\"SF\\\"}\"}}]}}]}");

        assertTrue(acc.hasToolUse());
        Map<String, Object> resp = acc.buildResponseMap();
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) ((List<?>) resp.get("choices")).get(0)).get("message");
        Map<?, ?> toolCall = (Map<?, ?>) ((List<?>) message.get("tool_calls")).get(0);
        assertEquals("call_1", toolCall.get("id"));
        Map<?, ?> fn = (Map<?, ?>) toolCall.get("function");
        assertEquals("get_weather", fn.get("name"));
        assertEquals("{\"city\":\"SF\"}", fn.get("arguments"));
    }
}

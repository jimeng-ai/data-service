package com.jimeng.dataserver.ai.run;

import cn.hutool.json.JSONUtil;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatHistoryReconstructorTest {

    private static ChatMessage msg(long id, String role, String content, String segments) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setSegments(segments);
        return m;
    }

    private static String segOf(String prompt) {
        return JSONUtil.toJsonStr(List.of(
                Map.of("type", "text", "text", "好的，我来出图"),
                Map.of("type", "tool", "call", Map.of(
                        "id", "toolu_1", "name", "generate_image",
                        "input", Map.of("prompt", prompt),
                        "output", Map.of("urls", List.of("/data/ai/image/genimg-abc.jpg")),
                        "status", "success")),
                Map.of("type", "text", "text", "搞定！下方就是图")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rebuildsToolUseAndToolResultBlocks() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "生成一张小狗在跑步的图片", null),
                msg(2, "assistant", "搞定！下方就是图", segOf("running dog"))));

        List<Map<String, Object>> out =
                new ChatHistoryReconstructor(mapper).reconstructClaude(100L, 999L, null);

        // user → assistant(text+tool_use) → user(tool_result) → assistant(text)
        assertEquals(4, out.size(), out.toString());
        assertTrue(ChatHistoryReconstructor.validAlternation(out));
        assertEquals("assistant", out.get(out.size() - 1).get("role"));

        // 第2条 assistant 含 tool_use(generate_image)
        List<Object> aBlocks = (List<Object>) out.get(1).get("content");
        assertTrue(aBlocks.stream().anyMatch(b -> b instanceof Map<?, ?> mb
                && "tool_use".equals(mb.get("type")) && "generate_image".equals(mb.get("name"))),
                "应含 generate_image 的 tool_use 块: " + aBlocks);

        // 第3条 user 含 tool_result，且文本不回灌 URL
        List<Object> uBlocks = (List<Object>) out.get(2).get("content");
        Map<String, Object> tr = (Map<String, Object>) uBlocks.get(0);
        assertEquals("tool_result", tr.get("type"));
        assertEquals("toolu_1", tr.get("tool_use_id"));
        assertFalse(String.valueOf(tr.get("content")).contains("genimg"), "tool_result 不应回灌图片 URL");
        assertTrue(String.valueOf(tr.get("content")).contains("生成"), "应说明已生成图片");
    }

    @Test
    void pureTextAssistantStaysOneMessage() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "你好", null),
                msg(2, "assistant", "你好，有什么可以帮你？", null)));
        List<Map<String, Object>> out =
                new ChatHistoryReconstructor(mapper).reconstructClaude(1L, 9L, null);
        assertEquals(2, out.size());
        assertEquals("你好，有什么可以帮你？", out.get(1).get("content")); // 纯文字、非块数组
        assertTrue(ChatHistoryReconstructor.validAlternation(out));
    }

    @Test
    void emptyConversationFallsBackToProvidedHistory() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        List<Map<String, Object>> fallback = List.of(Map.of("role", "user", "content", "x"));
        assertSame(fallback, new ChatHistoryReconstructor(mapper).reconstructClaude(1L, 9L, fallback));
    }

    @Test
    void parseSegmentsHandlesGarbage() {
        assertTrue(ChatHistoryReconstructor.parseSegments(null).isEmpty());
        assertTrue(ChatHistoryReconstructor.parseSegments("not json").isEmpty());
        assertEquals(1, ChatHistoryReconstructor.parseSegments("[{\"type\":\"text\",\"text\":\"a\"}]").size());
    }

    @Test
    void validAlternationRejectsTwoSameRolesAndNonAssistantTail() {
        assertFalse(ChatHistoryReconstructor.validAlternation(List.of(
                Map.of("role", "user", "content", "a"), Map.of("role", "user", "content", "b"))));
        assertFalse(ChatHistoryReconstructor.validAlternation(List.of(
                Map.of("role", "user", "content", "a")))); // 末条非 assistant
        assertTrue(ChatHistoryReconstructor.validAlternation(List.of(
                Map.of("role", "user", "content", "a"), Map.of("role", "assistant", "content", "b"))));
    }
}

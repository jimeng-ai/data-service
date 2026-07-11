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

    /** exec 路径：一条「只出图 / 写文件、无收尾文字」的 assistant 轮，其 segments 含 tool + artifact 段。 */
    private static String execSeg() {
        return JSONUtil.toJsonStr(List.of(
                Map.of("type", "tool", "call", Map.of("id", "t1", "name", "generate_image", "status", "success")),
                Map.of("type", "artifact", "artifact", Map.of("filename", "report.xlsx"))));
    }

    @Test
    void flattenAssistantSummarizesToolsAndArtifacts() {
        ChatHistoryReconstructor r = new ChatHistoryReconstructor(mock(ChatMessageMapper.class));
        // 空回答文字但调过工具/出过产物：折叠出非空、含工具名与文件名的描述（而非裸空串）。
        String s = r.flattenAssistant(msg(2, "assistant", "", execSeg()));
        assertTrue(s.contains("generate_image"), s);
        assertTrue(s.contains("report.xlsx"), s);
        // 纯文字回答无工具段：原样返回文字。
        assertEquals("你好", r.flattenAssistant(msg(3, "assistant", "你好", null)));
    }

    @Test
    void reconstructFlatTextSurfacesEmptyAssistantToolTurn() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "画一张海边黄昏", null),
                msg(2, "assistant", "", execSeg())));   // 只出图、无收尾文字
        List<Map<String, Object>> out =
                new ChatHistoryReconstructor(mapper).reconstructFlatText(1L, 9L, null);
        assertEquals(2, out.size(), out.toString());
        assertEquals("user", out.get(0).get("role"));
        assertEquals("assistant", out.get(1).get("role"));
        // 关键：assistant 轮 content 非空且带工具证据（修「裸 assistant: 行」导致模型不再真调工具）。
        String c = String.valueOf(out.get(1).get("content"));
        assertFalse(c.isBlank(), "assistant content 不应为空");
        assertTrue(c.contains("generate_image"), c);
    }

    @Test
    void reconstructFlatTextEmptyFallsBack() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        List<Map<String, Object>> fb = List.of(Map.of("role", "user", "content", "x"));
        assertSame(fb, new ChatHistoryReconstructor(mapper).reconstructFlatText(1L, 9L, fb));
    }

    /** 对抗：segments 里 call/artifact 是数组/字符串/数字等非对象时，flattenAssistant 绝不能抛异常。 */
    @Test
    void flattenAssistantToleratesMalformedSegments() {
        ChatHistoryReconstructor r = new ChatHistoryReconstructor(mock(ChatMessageMapper.class));
        String malformed = JSONUtil.toJsonStr(List.of(
                Map.of("type", "tool", "call", List.of(1, 2)),          // call 是数组
                Map.of("type", "tool", "call", "oops"),                  // call 是字符串
                Map.of("type", "artifact", "artifact", List.of("x")),    // artifact 是数组
                Map.of("type", "unknown", "foo", "bar")));               // 未知段
        assertDoesNotThrow(() -> r.flattenAssistant(msg(2, "assistant", "答复", malformed)));
        assertEquals("答复", r.flattenAssistant(msg(2, "assistant", "答复", malformed))); // 畸形段被忽略，保留正文
    }

    /** 对抗：所有历史助手轮都被取消/失败过滤掉 → 只剩 user 行时，回退前端 history（别喂一堵没人应答的问题墙）。 */
    @Test
    void reconstructFlatTextFallsBackWhenNoAssistant() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                msg(1, "user", "q1", null), msg(3, "user", "q2", null), msg(5, "user", "q3", null)));
        List<Map<String, Object>> fb = List.of(
                Map.of("role", "user", "content", "q1"), Map.of("role", "assistant", "content", "a1"));
        assertSame(fb, new ChatHistoryReconstructor(mapper).reconstructFlatText(1L, 9L, fb));
    }
}

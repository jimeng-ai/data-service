package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticModelCallTest {

    private ClaudeService claude;
    private SemanticModelCall call;

    @BeforeEach
    void setUp() {
        claude = mock(ClaudeService.class);
        call = new SemanticModelCall(claude, new ConnectorProperties());
    }

    private void reply(String text) {
        when(claude.messagesInternal(any(), any()))
                .thenReturn(Map.of("content", List.of(Map.of("type", "text", "text", text))));
    }

    @Test
    @DisplayName("请求体：可变集合、带 system 与一条 user 消息、max_tokens 照传；没配模型时不下发 model")
    @SuppressWarnings("unchecked")
    void 请求体() {
        reply("{\"joins\":[]}");

        call.askJson("关系发现", 7L, "系统提示", "用户内容", 8000);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(claude).messagesInternal(body.capture(), timeout.capture());
        assertEquals("系统提示", body.getValue().get("system"));
        assertEquals(8000, body.getValue().get("max_tokens"));
        assertFalse(body.getValue().containsKey("model"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getValue().get("messages");
        assertEquals("用户内容", messages.get(0).get("content"));
        // 下游 ModelResolver / GenericChatClient 会就地改写 body 与 messages：不可变集合在那里抛一个 message 为 null 的异常。
        assertDoesNotThrow(() -> body.getValue().put("stream", false));
        assertDoesNotThrow(() -> ((List<Object>) body.getValue().get("messages")).add(Map.of()));
        assertEquals(Duration.ofSeconds(900), timeout.getValue());
    }

    @Test
    @DisplayName("配了 connector.semantic.infer-model 就下发 model，并如实报出来")
    @SuppressWarnings("unchecked")
    void 配了模型() {
        ReflectionTestUtils.setField(call, "inferModel", " claude-x ");
        reply("{}");

        call.askJson("业务文字", 7L, "s", "u", 100);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(claude).messagesInternal(body.capture(), any());
        assertEquals("claude-x", body.getValue().get("model"));
        assertEquals("claude-x", call.modelCode());
    }

    @Test
    @DisplayName("markdown 围栏和正文前的闲话都去掉")
    void 围栏与闲话() {
        reply("好的，结果如下：\n```json\n{\"joins\":[{\"object\":\"a\"}]}\n```");
        Map<String, Object> out = call.askJson("关系发现", 7L, "s", "u", 100);
        assertEquals(1, ((List<?>) out.get("joins")).size());
    }

    @Test
    @DisplayName("被 max_tokens 截断：收尾到最后一个完整条目")
    void 截断修复() {
        reply("{\"joins\":[{\"object\":\"a\",\"column\":\"b\"},{\"object\":\"c\",\"colu");
        Map<String, Object> out = call.askJson("关系发现", 7L, "s", "u", 100);
        assertEquals(1, ((List<?>) out.get("joins")).size());
    }

    @Test
    @DisplayName("完全不是 JSON：抛出去，由调用方按失败处理")
    void 不是JSON() {
        reply("抱歉，我无法完成");
        assertThrows(IllegalStateException.class, () -> call.askJson("关系发现", 7L, "s", "u", 100));
    }

    @Test
    @DisplayName("多段 text 块按顺序拼起来")
    void 多段文本() {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", "{\"a\":"));
        content.add(Map.of("type", "text", "text", "1}"));
        when(claude.messagesInternal(any(), any())).thenReturn(Map.of("content", content));
        assertEquals(1, call.askJson("x", 7L, "s", "u", 100).get("a"));
    }
}

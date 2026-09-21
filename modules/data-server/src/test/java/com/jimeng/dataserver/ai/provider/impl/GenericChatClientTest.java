package com.jimeng.dataserver.ai.provider.impl;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GenericChatClient#chatInternal}：与 chat 共用请求体默认值、adapter 选择、URL 与鉴权头，
 * 只是委托给 {@link AiConversationLoop#runInternal}（不带工具、单独超时）。
 */
class GenericChatClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(900);

    private AiConversationLoop loop;
    private AiProtocolAdapter anthropicAdapter;
    private AiProtocolAdapter openaiAdapter;
    private AiProtocolAdapter crossAdapter;

    @BeforeEach
    void setUp() {
        loop = mock(AiConversationLoop.class);
        anthropicAdapter = mock(AiProtocolAdapter.class);
        openaiAdapter = mock(AiProtocolAdapter.class);
        crossAdapter = mock(AiProtocolAdapter.class);
    }

    /** 用例可改这里的配置，验证「改了立即生效、不用重启」。 */
    private AiProviderProperties props;

    private GenericChatClient client(String upstreamProtocol, String entryProtocol) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setBaseUrl("https://api.deepseek.com/v1/");
        cfg.setApiKey("sk-test");
        cfg.getChat().setProtocol(upstreamProtocol);
        cfg.getChat().setEntryProtocol(entryProtocol);
        cfg.getChat().setModel("deepseek-flash");
        props = new AiProviderProperties();
        props.getProviders().put("deepseek", cfg);
        return new GenericChatClient("deepseek", cfg, new AiSelectionProperties(), loop,
                anthropicAdapter, openaiAdapter, crossAdapter, mock(SseServiceUtil.class), props);
    }

    private static Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("max_tokens", 16000);
        body.put("system", "推导系统提示");
        List<Object> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "orders|id|bigint")));
        body.put("messages", messages);
        return body;
    }

    @Test
    @DisplayName("★ chatInternal 委托 runInternal（带上超时），不走 runBlocking")
    @SuppressWarnings("unchecked")
    void chatInternal走runInternal() {
        Object expected = Map.of("content", List.of());
        when(loop.runInternal(any(), any(), any(), any(), any(), any(), any())).thenReturn(expected);

        Object out = client("openai", "anthropic").chatInternal(body(), "trace-1", TIMEOUT);

        assertSame(expected, out);
        ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> headerCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<AiConversationLoop.CallRecordConfig> rcCap =
                ArgumentCaptor.forClass(AiConversationLoop.CallRecordConfig.class);
        verify(loop).runInternal(bodyCap.capture(), same(crossAdapter), headerCap.capture(),
                eq("https://api.deepseek.com/v1/chat/completions"), eq("trace-1"), rcCap.capture(), eq(TIMEOUT));
        verify(loop, never()).runBlocking(any(), any(), any(), any(), any(), any());

        // prepareBody 照做：按入口协议补 model，调用方给的 max_tokens / system 不被覆盖。
        assertEquals("deepseek-flash", bodyCap.getValue().get("model"));
        assertEquals(16000, bodyCap.getValue().get("max_tokens"));
        assertEquals("推导系统提示", bodyCap.getValue().get("system"));
        assertEquals("Bearer sk-test", headerCap.getValue().get("Authorization"));
        assertEquals("deepseek", rcCap.getValue().provider());
    }

    @Test
    @DisplayName("★ Nacos 改了 api-key / model 之后立即生效，不需要重启")
    @SuppressWarnings("unchecked")
    void 配置热更新() {
        GenericChatClient c = client("openai", "anthropic");
        c.chatInternal(body(), "t", TIMEOUT);

        // 刷新前：用的是原来的 key 与地址
        ArgumentCaptor<Map<String, String>> h1 = ArgumentCaptor.forClass(Map.class);
        verify(loop).runInternal(any(), any(), h1.capture(),
                eq("https://api.deepseek.com/v1/chat/completions"), eq("t"), any(), eq(TIMEOUT));
        assertEquals("Bearer sk-test", h1.getValue().get("Authorization"));

        // 模拟 Nacos 刷新：@ConfigurationProperties 被重新绑定，值就地更新。
        // 旧实现把 ProviderConfig 在 bean 创建时固化进字段，这之后依然发旧 key —— 实测表现是
        // 「Nacos 打了 Refresh keys changed，请求仍然 401」。
        props.getProviders().get("deepseek").setApiKey("sk-rotated");
        props.getProviders().get("deepseek").setBaseUrl("https://api.deepseek.com/v2/");
        props.getProviders().get("deepseek").getChat().setModel("deepseek-next");

        c.chatInternal(body(), "t2", TIMEOUT);

        ArgumentCaptor<Map<String, Object>> b2 = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> h2 = ArgumentCaptor.forClass(Map.class);
        verify(loop).runInternal(b2.capture(), any(), h2.capture(),
                eq("https://api.deepseek.com/v2/chat/completions"), eq("t2"), any(), eq(TIMEOUT));
        assertEquals("Bearer sk-rotated", h2.getValue().get("Authorization"));
        assertEquals("deepseek-next", b2.getValue().get("model"));
    }

    @Test
    @DisplayName("同协议 provider 用原生 adapter，路径按上游协议推导")
    void 同协议用原生adapter() {
        client("anthropic", null).chatInternal(body(), null, TIMEOUT);

        verify(loop).runInternal(any(), same(anthropicAdapter), any(),
                eq("https://api.deepseek.com/v1/messages"), isNull(), any(), eq(TIMEOUT));
    }

    @Test
    @DisplayName("请求体校验与 chat 相同：messages 为空直接拒绝，一个字节都不发出去")
    void 校验照做() {
        Map<String, Object> body = body();
        body.put("messages", new ArrayList<>());

        assertThrows(ServiceException.class,
                () -> client("openai", "anthropic").chatInternal(body, null, TIMEOUT));
        verifyNoInteractions(loop);
    }

    @Test
    @DisplayName("★ SPI 默认实现抛异常，绝不静默回落到 chat()（那条路会带上内置工具、丢掉超时）")
    void spi默认不回落chat() {
        ChatClient bare = new ChatClient() {
            @Override
            public Object chat(Map<String, Object> requestBody, String traceId) {
                throw new AssertionError("chatInternal 不该回落到 chat()");
            }

            @Override
            public void chatStream(Map<String, Object> requestBody, String connectionId, String traceId) {
                throw new AssertionError("chatInternal 不该回落到 chatStream()");
            }

            @Override
            public ChatCapabilities capabilities() {
                return new ChatCapabilities("anthropic", true, "bare", "m");
            }

            @Override
            public String providerName() {
                return "bare";
            }
        };

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> bare.chatInternal(body(), null, TIMEOUT));
        assertTrue(ex.getMessage().contains("bare") && ex.getMessage().contains("chatInternal"),
                "报错里要说清是哪个实现、缺哪个方法：" + ex.getMessage());
    }
}

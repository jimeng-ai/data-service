package com.jimeng.dataserver.ai.provider.spi;

import java.time.Duration;
import java.util.Map;

/**
 * 聊天能力 SPI：阻塞与流式两种调用，由 ProviderRegistry 按 ai.provider 选实现。
 * 实现类按协议（anthropic / openai）路由到对应的 AiProtocolAdapter + AiConversationLoop。
 */
public interface ChatClient {

    Object chat(Map<String, Object> requestBody, String traceId);

    void chatStream(Map<String, Object> requestBody, String connectionId, String traceId);

    /**
     * 平台<b>自己</b>发起的单轮阻塞调用（没有用户、没有 Agent，例如语义层推导）：不注入技能与内置工具、
     * 不执行工具、不进工具循环，读超时按这一次调用给。为什么必须和 {@link #chat} 分开，见
     * {@code AiConversationLoop#runInternal}。
     *
     * <p>默认<b>抛异常而不是回落到 {@link #chat}</b>：回落等于把内置工具（含 web_search）悄悄带回平台内部调用，
     * 超时参数也被静默丢掉——调用方以为自己「不带工具、限时 N 秒」，实际两样都没有，而且不报错。
     *
     * @param readTimeout 这一次调用的读超时；null = 沿用全局 okhttp.read-timeout
     */
    default Object chatInternal(Map<String, Object> requestBody, String traceId, Duration readTimeout) {
        throw new UnsupportedOperationException("ChatClient[" + providerName() + "] 没有实现 chatInternal"
                + "（平台内部单轮调用：不带工具、单独超时）。不能回落到 chat()——那条路会注入内置工具并执行工具循环，"
                + "超时参数也会被丢掉；新增的 ChatClient 实现必须自己覆写本方法");
    }

    ChatCapabilities capabilities();

    /** 由 ProviderRegistry 用于按 ai.provider 选 bean，对应 yml providers.&lt;name&gt;。 */
    String providerName();
}

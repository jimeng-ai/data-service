package com.jimeng.dataserver.ai.provider.spi;

import java.util.Map;

/**
 * 聊天能力 SPI：阻塞与流式两种调用，由 ProviderRegistry 按 ai.provider 选实现。
 * 实现类按协议（anthropic / openai）路由到对应的 AiProtocolAdapter + AiConversationLoop。
 */
public interface ChatClient {

    Object chat(Map<String, Object> requestBody, String traceId);

    void chatStream(Map<String, Object> requestBody, String connectionId, String traceId);

    ChatCapabilities capabilities();

    /** 由 ProviderRegistry 用于按 ai.provider 选 bean，对应 yml providers.&lt;name&gt;。 */
    String providerName();
}

package com.jimeng.dataserver.ai.openai.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * OpenAI 协议入口：把 /data/openai/chat/completions 请求委托给当前激活 provider 的 chat client。
 * provider 的 chat.protocol 必须是 openai，否则 fail-fast 400 并指引到 /data/claude/messages。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService {

    private static final String EXPECTED_PROTOCOL = "openai";

    private final ProviderRegistry providerRegistry;

    public Object chatCompletions(Map<String, Object> requestBody) {
        return requireOpenAiChat().chat(requestBody, null);
    }

    public void chatCompletionsStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        requireOpenAiChat().chatStream(requestBody, connectionId, traceId);
    }

    private ChatClient requireOpenAiChat() {
        ChatClient client = providerRegistry.chat();
        ChatCapabilities caps = client.capabilities();
        if (!EXPECTED_PROTOCOL.equals(caps.protocol())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "当前 ai.provider=" + caps.providerName()
                            + " 的 chat.protocol=" + caps.protocol()
                            + "，与 /data/openai/chat/completions 期望的 openai 协议不匹配，请改用 /data/claude/messages");
        }
        return client;
    }
}

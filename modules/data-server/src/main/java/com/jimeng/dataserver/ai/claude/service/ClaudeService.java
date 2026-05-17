package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Anthropic 协议入口：把 /data/claude/messages 请求委托给当前激活 provider 的 chat client。
 * provider 的 chat.protocol 必须是 anthropic，否则 fail-fast 400 并指引到 /data/openai/...。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeService {

    private static final String EXPECTED_PROTOCOL = "anthropic";

    private final ProviderRegistry providerRegistry;

    public Object messages(Map<String, Object> requestBody) {
        ChatClient client = requireAnthropicChat();
        return client.chat(requestBody, extractTraceId());
    }

    public void messagesStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        ChatClient client = requireAnthropicChat();
        requestBody.put("stream", true);
        client.chatStream(requestBody, connectionId, traceId);
    }

    private ChatClient requireAnthropicChat() {
        ChatClient client = providerRegistry.chat();
        ChatCapabilities caps = client.capabilities();
        if (!EXPECTED_PROTOCOL.equals(caps.protocol())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "当前 ai.provider=" + caps.providerName()
                            + " 的 chat.protocol=" + caps.protocol()
                            + "，与 /data/claude/messages 期望的 anthropic 协议不匹配，请改用 /data/openai/chat/completions");
        }
        return client;
    }

    private String extractTraceId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String traceId = req.getHeader("trace-id");
            return StrUtil.isNotBlank(traceId) ? traceId : req.getHeader("x-trace-id");
        } catch (Exception e) {
            log.warn("提取trace-id失败: {}", e.getMessage());
            return null;
        }
    }
}

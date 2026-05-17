package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用聊天客户端：按 ProviderConfig.chat.protocol 路由到 anthropic 或 openai 协议路径。
 * 每个 provider 由 ProviderBeansConfig 实例化一份并注册为同名 bean。
 *
 * <p>请求体在调用进入时按协议补默认值（model、max_tokens / max_completion_tokens、system 等），
 * 之后委托给 AiConversationLoop 处理多轮 + 流式 + skill 工具循环。
 */
@Slf4j
public class GenericChatClient implements ChatClient {

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    private static final String DEFAULT_ANTHROPIC_PATH = "/messages";
    private static final String DEFAULT_OPENAI_PATH = "/chat/completions";

    private final String providerName;
    private final ProviderConfig config;
    private final AiSelectionProperties selection;
    private final AiConversationLoop conversationLoop;
    private final AiProtocolAdapter anthropicAdapter;
    private final AiProtocolAdapter openaiAdapter;
    private final SseServiceUtil sseServiceUtil;

    public GenericChatClient(String providerName,
                             ProviderConfig config,
                             AiSelectionProperties selection,
                             AiConversationLoop conversationLoop,
                             AiProtocolAdapter anthropicAdapter,
                             AiProtocolAdapter openaiAdapter,
                             SseServiceUtil sseServiceUtil) {
        this.providerName = providerName;
        this.config = config;
        this.selection = selection;
        this.conversationLoop = conversationLoop;
        this.anthropicAdapter = anthropicAdapter;
        this.openaiAdapter = openaiAdapter;
        this.sseServiceUtil = sseServiceUtil;
    }

    private String systemPrompt() {
        return selection == null ? null : selection.getSystemPrompt();
    }

    @Override
    public Object chat(Map<String, Object> requestBody, String traceId) {
        Map<String, Object> body = prepareBody(requestBody, false);
        return conversationLoop.runBlocking(body, adapter(), buildHeaders(), buildUrl(), traceId, recordConfig());
    }

    @Override
    public void chatStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        Map<String, Object> body;
        try {
            body = prepareBody(requestBody, true);
        } catch (Exception e) {
            log.warn("流式请求体构造失败 provider={} connectionId={} err={}", providerName, connectionId, e.getMessage());
            sendStreamError(connectionId, e);
            sseServiceUtil.complete(connectionId);
            return;
        }
        conversationLoop.runStream(body, adapter(), buildHeaders(), buildUrl(), connectionId, traceId, recordConfig());
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ChatCapabilities capabilities() {
        return new ChatCapabilities(config.getChat().getProtocol(),
                PROTOCOL_ANTHROPIC.equals(config.getChat().getProtocol()),
                providerName,
                config.getChat().getModel());
    }

    private Map<String, Object> prepareBody(Map<String, Object> requestBody, boolean streaming) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".api-key 未配置");
        }
        if (StrUtil.isBlank(config.getChat().getModel())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".chat.model 未配置");
        }
        String protocol = config.getChat().getProtocol();
        if (PROTOCOL_ANTHROPIC.equals(protocol)) {
            return prepareAnthropicBody(requestBody);
        }
        if (PROTOCOL_OPENAI.equals(protocol)) {
            return prepareOpenAiBody(requestBody, streaming);
        }
        throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                "providers." + providerName + ".chat.protocol 仅支持 anthropic / openai，当前=" + protocol);
    }

    private Map<String, Object> prepareAnthropicBody(Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        normalizeAnthropicMaxTokens(body);
        String sp = systemPrompt();
        if (StrUtil.isNotBlank(sp)) body.putIfAbsent("system", sp);
        body.putIfAbsent("model", config.getChat().getModel());
        body.putIfAbsent("max_tokens", config.getChat().getMaxTokens());

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareOpenAiBody(Map<String, Object> requestBody, boolean streaming) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.putIfAbsent("model", config.getChat().getModel());
        normalizeOpenAiMaxTokens(body);
        if (!body.containsKey("max_tokens") && !body.containsKey("max_completion_tokens")) {
            body.put("max_completion_tokens", config.getChat().getMaxTokens());
        }
        body.putIfAbsent("temperature", 1);
        body.put("stream", streaming || Boolean.TRUE.equals(body.get("stream")));
        if (Boolean.TRUE.equals(body.get("stream"))) {
            body.putIfAbsent("stream_options", Map.of("include_usage", true));
        }

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        prependSystemMessageIfMissing(body);
        return body;
    }

    @SuppressWarnings("unchecked")
    private void prependSystemMessageIfMissing(Map<String, Object> body) {
        String sp = systemPrompt();
        if (StrUtil.isBlank(sp)) return;
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) continue;
            Object role = msg.get("role");
            if ("system".equals(role) || "developer".equals(role)) return;
        }
        List<Object> copied = new ArrayList<>(messages.size() + 1);
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", sp);
        copied.add(systemMsg);
        copied.addAll(messages);
        body.put("messages", copied);
    }

    private void normalizeAnthropicMaxTokens(Map<String, Object> body) {
        if (body.containsKey("max_tokens")) return;
        Object camelValue = body.remove("maxTokens");
        if (camelValue != null) body.put("max_tokens", camelValue);
    }

    private void normalizeOpenAiMaxTokens(Map<String, Object> body) {
        Object maxCompletion = body.containsKey("max_completion_tokens")
                ? body.get("max_completion_tokens") : body.remove("maxCompletionTokens");
        Object maxTokens = body.containsKey("max_tokens")
                ? body.remove("max_tokens") : body.remove("maxTokens");
        body.remove("max_output_tokens");
        Object effective = maxCompletion != null ? maxCompletion : maxTokens;
        if (effective != null) body.put("max_completion_tokens", effective);
    }

    private AiProtocolAdapter adapter() {
        return PROTOCOL_ANTHROPIC.equals(config.getChat().getProtocol()) ? anthropicAdapter : openaiAdapter;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private String buildUrl() {
        String path = config.getChat().getEndpointPath();
        if (StrUtil.isBlank(path)) {
            path = PROTOCOL_ANTHROPIC.equals(config.getChat().getProtocol())
                    ? DEFAULT_ANTHROPIC_PATH : DEFAULT_OPENAI_PATH;
        }
        return StrUtil.removeSuffix(config.getBaseUrl(), "/") + path;
    }

    private AiConversationLoop.CallRecordConfig recordConfig() {
        return new AiConversationLoop.CallRecordConfig(
                providerName, buildUrl(), config.getChat().getModel());
    }

    private void sendStreamError(String connectionId, Exception e) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage());
            sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(err));
        } catch (Exception ignored) {
        }
    }
}

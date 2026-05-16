package com.jimeng.dataserver.ai.openai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService {

    private static final String PROVIDER = "openai";
    private static final String ENDPOINT = "/v1/chat/completions";

    @Value("${ai-api.openai.api-key:}")
    private String apiKey;

    @Value("${ai-api.openai.base-Url}")
    private String baseUrl;

    @Value("${ai-api.openai.model:gpt-5.3-codex}")
    private String defaultModel;

    @Value("${ai-api.openai.max-completion-tokens:${ai-api.openai.max-tokens:8192}}")
    private Integer defaultMaxCompletionTokens;

    @Value("${ai-api.openai.temperature:1}")
    private Integer defaultTemperature;

    @Value("${ai.system-prompt:}")
    private String systemPrompt;

    private final AiConversationLoop conversationLoop;
    private final OpenAiProtocolAdapter adapter;
    private final SseServiceUtil sseServiceUtil;

    public Object chatCompletions(Map<String, Object> requestBody) {
        Map<String, Object> body = buildBody(requestBody, false);
        return conversationLoop.runBlocking(body, adapter, buildHeaders(), buildUrl(), null, recordConfig());
    }

    public void chatCompletionsStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        Map<String, Object> body;
        try {
            body = buildBody(requestBody, true);
        } catch (Exception e) {
            try {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", e.getClass().getSimpleName());
                err.put("message", e.getMessage());
                sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(err));
            } catch (Exception ignored) {}
            sseServiceUtil.complete(connectionId);
            return;
        }
        conversationLoop.runStream(body, adapter, buildHeaders(), buildUrl(), connectionId, traceId, recordConfig());
    }

    private Map<String, Object> buildBody(Map<String, Object> requestBody, boolean stream) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未配置ai-api.openai.api-key");
        }
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.putIfAbsent("model", defaultModel);
        normalizeMaxTokens(body);
        if (!body.containsKey("max_tokens") && !body.containsKey("max_completion_tokens")) {
            body.put("max_completion_tokens", defaultMaxCompletionTokens);
        }
        body.putIfAbsent("temperature", defaultTemperature);
        body.put("stream", stream || Boolean.TRUE.equals(body.get("stream")));
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
        if (StrUtil.isBlank(systemPrompt)) return;
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
        systemMsg.put("content", systemPrompt);
        copied.add(systemMsg);
        copied.addAll(messages);
        body.put("messages", copied);
    }

    private void normalizeMaxTokens(Map<String, Object> body) {
        Object maxCompletion = body.containsKey("max_completion_tokens")
                ? body.get("max_completion_tokens") : body.remove("maxCompletionTokens");
        Object maxTokens = body.containsKey("max_tokens")
                ? body.remove("max_tokens") : body.remove("maxTokens");
        body.remove("max_output_tokens");
        Object effective = maxCompletion != null ? maxCompletion : maxTokens;
        if (effective != null) body.put("max_completion_tokens", effective);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private String buildUrl() {
        return StrUtil.removeSuffix(baseUrl, "/") + ENDPOINT;
    }

    private AiConversationLoop.CallRecordConfig recordConfig() {
        return new AiConversationLoop.CallRecordConfig(PROVIDER, ENDPOINT, defaultModel);
    }
}

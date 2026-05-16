package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeService {

    private static final String PROVIDER = "anthropic";
    private static final String ENDPOINT = "/v1/messages";

    @Value("${ai-api.claude.api-key}")
    private String apiKey;

    @Value("${ai-api.claude.base-Url}")
    private String baseUrl;

    @Value("${ai-api.claude.model}")
    private String defaultModel;

    @Value("${ai-api.claude.max-tokens}")
    private Integer defaultMaxTokens;

    @Value("${ai.system-prompt}")
    private String systemPrompt;

    private final AiConversationLoop conversationLoop;
    private final ClaudeProtocolAdapter adapter;

    public Object messages(Map<String, Object> requestBody) {
        Map<String, Object> body = buildBody(requestBody);
        String traceId = extractTraceId();
        return conversationLoop.runBlocking(body, adapter, buildHeaders(), buildUrl(), traceId, recordConfig());
    }

    public void messagesStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        Map<String, Object> body = buildBody(requestBody);
        body.put("stream", true);
        conversationLoop.runStream(body, adapter, buildHeaders(), buildUrl(), connectionId, traceId, recordConfig());
    }

    private Map<String, Object> buildBody(Map<String, Object> requestBody) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未配置ai-api.claude.api-key");
        }
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        normalizeMaxTokens(body);
        body.putIfAbsent("system", systemPrompt);
        body.putIfAbsent("model", defaultModel);
        body.putIfAbsent("max_tokens", defaultMaxTokens);

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        return body;
    }

    private void normalizeMaxTokens(Map<String, Object> body) {
        if (body.containsKey("max_tokens")) return;
        Object camelValue = body.remove("maxTokens");
        if (camelValue != null) body.put("max_tokens", camelValue);
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

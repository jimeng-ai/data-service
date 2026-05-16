package com.jimeng.dataserver.ai.openai.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.openai.service.OpenAiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "OpenAI消息管理", description = "OpenAI Chat Completions接口")
@RestController
@RequestMapping("/data/openai")
@RequiredArgsConstructor
@Slf4j
public class OpenAiController {

    private final OpenAiService openAiService;
    private final SseServiceUtil sseServiceUtil;

    private final ExecutorService streamExecutor =
            Executors.newCachedThreadPool();

    @Operation(summary = "OpenAI聊天对话", description = "对接OpenAI /v1/chat/completions")
    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestBody Map<String, Object> requestBody,
                                  HttpServletRequest request) {
        if (Boolean.TRUE.equals(requestBody.get("stream"))) {
            return startStream(requestBody, request);
        }
        return openAiService.chatCompletions(requestBody);
    }

    private SseEmitter startStream(Map<String, Object> requestBody, HttpServletRequest request) {
        String connectionId = UUID.randomUUID().toString();
        String traceId = extractTraceId(request);
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, 300_000L);
        streamExecutor.execute(() -> openAiService.chatCompletionsStream(requestBody, connectionId, traceId));
        return emitter;
    }

    private String extractTraceId(HttpServletRequest request) {
        if (request == null) return null;
        String traceId = request.getHeader("trace-id");
        return StrUtil.isNotBlank(traceId) ? traceId : request.getHeader("x-trace-id");
    }
}

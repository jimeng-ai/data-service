package com.jimeng.dataserver.ai.claude.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Tag(name = "Claude消息管理", description = "Claude统一消息接口（文本、图片、文档、工具）")
@RestController
@RequestMapping("/data/claude")
@RequiredArgsConstructor
public class ClaudeController {

    private final ClaudeService claudeService;
    private final SseServiceUtil sseServiceUtil;

    @Operation(summary = "Claude统一消息接口", description = "同一个接口支持文本、图片、文档和tools")
    @PostMapping("/messages")
    public Object messages(@RequestBody Map<String, Object> requestBody,
                           HttpServletRequest request) {
        if (Boolean.TRUE.equals(requestBody.get("stream"))) {
            return messagesStream(requestBody, request);
        }
        return claudeService.messages(requestBody);
    }

    private SseEmitter messagesStream(Map<String, Object> requestBody,
                                      HttpServletRequest request) {
        String connectionId = UUID.randomUUID().toString();
        // 在请求线程中提取 trace-id，避免异步线程拿不到 RequestContext
        String traceId = extractTraceId(request);
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, 300_000L);
        CompletableFuture.runAsync(() -> {
            claudeService.messagesStream(requestBody, connectionId, traceId);
        });
        return emitter;
    }

    private String extractTraceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String traceId = request.getHeader("trace-id");
        if (StrUtil.isNotBlank(traceId)) {
            return traceId;
        }
        return request.getHeader("x-trace-id");
    }
}

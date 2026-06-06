package com.jimeng.dataserver.ai.claude.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Claude消息管理", description = "Claude统一消息接口（文本、图片、文档、工具）")
@RestController
@RequestMapping("/data/claude")
@RequiredArgsConstructor
@Slf4j
public class ClaudeController {

    private final ClaudeService claudeService;
    private final SseServiceUtil sseServiceUtil;

    private final ThreadPoolTaskExecutor streamExecutor;

    @Operation(summary = "Claude统一消息接口", description = "同一个接口支持文本、图片、文档和tools")
    @PostMapping("/messages")
    public Object messages(@RequestBody Map<String, Object> requestBody,
                           HttpServletRequest request) {
        try {
            if (Boolean.TRUE.equals(requestBody.get("stream"))) {
                return startStream(requestBody, request);
            }
            return claudeService.messages(requestBody);
        } finally {
            // 本接口在【请求线程】上通过 prepareAgentContext / messages()→applyAgentContext 设置了 AgentContext。
            // 流式分支已由 MdcAsyncSupport.wrap 把上下文「拓印」给异步任务（其捕获发生在 startStream 内、本 finally 之前），
            // 故无论流式/非流式，方法返回前都必须清掉【请求线程】的 Agent ThreadLocal——否则 Tomcat 线程被线程池复用时，
            // 会把本次请求的 Agent 绑定残留给下一个请求（尤其无 agent 的请求），造成跨请求 / 跨租户串绑定。
            AgentContext.clear();
            AgentIdContext.clear();
        }
    }

    private SseEmitter startStream(Map<String, Object> requestBody, HttpServletRequest request) {
        String connectionId = UUID.randomUUID().toString();
        String traceId = extractTraceId(request);
        // 在主线程完成 Agent 上下文加载（修改 body + 设置 AgentContext ThreadLocal），
        // 然后让 MdcAsyncSupport 把 ThreadLocal 状态一并传递到 streamExecutor 线程。
        claudeService.prepareAgentContext(requestBody);
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, 300_000L);
        streamExecutor.execute(MdcAsyncSupport.wrap(connectionId,
                () -> claudeService.messagesStream(requestBody, connectionId, traceId)));
        return emitter;
    }

    private String extractTraceId(HttpServletRequest request) {
        if (request == null) return null;
        String traceId = request.getHeader("trace-id");
        return StrUtil.isNotBlank(traceId) ? traceId : request.getHeader("x-trace-id");
    }
}

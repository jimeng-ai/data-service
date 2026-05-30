package com.jimeng.dataserver.ai.rag.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.rag.model.AnswerRequest;
import com.jimeng.dataserver.ai.rag.service.answer.RagAnswerService;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "RAG-知识问答", description = "基于知识库 chunk 检索 + LLM 流式生成答案")
@RestController
@RequestMapping("/data/rag")
@RequiredArgsConstructor
public class RagAnswerController {

    private final RagAnswerService ragAnswerService;
    private final SseServiceUtil sseServiceUtil;
    private final PermissionResolver permissionResolver;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    @Operation(summary = "RAG 流式问答",
            description = "在指定知识库内检索相关 chunk，并基于命中片段让 LLM 流式生成答案。" +
                    "响应为 SSE 流（text/event-stream），按 Claude/OpenAI 风格事件持续推送增量内容，直到生成结束。" +
                    "可在 history 中带入多轮对话历史。")
    @PostMapping("/answer")
    public SseEmitter answer(@RequestBody AnswerRequest req, HttpServletRequest request) {
        // 必须在【请求线程】上鉴权：streamAnswer 跑在 streamExecutor 线程，
        // 而 AdminRequestContext 依赖 RequestContextHolder（请求级 ThreadLocal，不随 MdcAsyncSupport 传递）。
        // 显式 kbId → 校验该知识库访问权；否则按 agentId → 校验 Agent 访问权（Agent 绑定的库经此间接授权）。
        assertAnswerAccess(req);
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, 300_000L);
        String traceId = extractTraceId(request);
        streamExecutor.execute(MdcAsyncSupport.wrap(connectionId,
                () -> ragAnswerService.streamAnswer(req, connectionId, traceId)));
        return emitter;
    }

    private void assertAnswerAccess(AnswerRequest req) {
        if (req == null) return;
        if (req.getKbId() != null) {
            permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, req.getKbId());
            return;
        }
        if (StrUtil.isNotBlank(req.getAgentId())) {
            try {
                permissionResolver.assertCurrentAccess(ResourceType.AGENT, Long.parseLong(req.getAgentId().trim()));
            } catch (NumberFormatException ignore) {
                // 非实例化 agentId（如运行时虚拟会话），无实例可校验，交由后续逻辑处理
            }
        }
    }

    private String extractTraceId(HttpServletRequest request) {
        if (request == null) return null;
        String traceId = request.getHeader("trace-id");
        return StrUtil.isNotBlank(traceId) ? traceId : request.getHeader("x-trace-id");
    }
}

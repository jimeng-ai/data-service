package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 服务端自持久化 + 可重连的一轮对话入口。
 *
 * <ul>
 *   <li>{@code POST /conversations/{id}/turns} 发起生成，立即返回 runId（生成在服务端进行，不绑定本连接）。</li>
 *   <li>{@code GET /runs/{runId}/stream} 消费/重连：续播缓冲 + 实时跟随（发送与重连同一条路径）。</li>
 *   <li>{@code POST /runs/{runId}/cancel} 真取消（中断上游 LLM / 关闭沙箱上游）。</li>
 * </ul>
 */
@Tag(name = "对话-可重连生成", description = "发送后不丢、可离开、可重连续播")
@RestController
@RequestMapping("/data/admin/chat")
@RequiredArgsConstructor
public class ChatRunController {

    private final ChatRunService chatRunService;
    private final ChatConversationService chatConversationService;

    @Operation(summary = "发起一轮对话（服务端生成）", description = "落用户消息 + 助手占位，转交服务端生成，立即返回 runId。")
    @PostMapping("/conversations/{id}/turns")
    public TurnStartResponse startTurn(@PathVariable("id") Long conversationId,
                                       @RequestBody TurnStartRequest req,
                                       HttpServletRequest request) {
        return chatRunService.startTurn(conversationId, req, extractTraceId(request));
    }

    @Operation(summary = "消费/重连生成流", description = "SSE：先补播 Redis 续播缓冲、再实时跟随，遇终止帧收尾。")
    @GetMapping("/runs/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(value = "from", required = false) String from,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        assertRunAccess(runId);
        String fromId = StrUtil.isNotBlank(lastEventId) ? lastEventId : from;
        return chatRunService.attachViewer(runId, fromId);
    }

    @Operation(summary = "取消生成", description = "中断上游 LLM 调用 / 关闭到沙箱的上游请求，落成 CANCELLED。")
    @PostMapping("/runs/{runId}/cancel")
    public void cancel(@PathVariable String runId) {
        assertRunAccess(runId);
        chatRunService.cancelRun(runId);
    }

    /** 据 runId 反查会话并校验当前账号对其所属 Agent 有访问权（请求线程，鉴权依赖请求级 ThreadLocal）。 */
    private void assertRunAccess(String runId) {
        Long conversationId = chatConversationService.conversationIdOfRun(runId);
        if (conversationId == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "生成运行不存在: " + runId);
        }
        chatConversationService.requireConversationWithAccess(conversationId);
    }

    private String extractTraceId(HttpServletRequest request) {
        if (request == null) return null;
        String traceId = request.getHeader("trace-id");
        return StrUtil.isNotBlank(traceId) ? traceId : request.getHeader("x-trace-id");
    }
}

package com.jimeng.dataserver.ai.agent.builder;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.FinalizeRequest;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.FinalizeResponse;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.StartSessionResponse;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.run.ChatRunService;
import com.jimeng.persistence.entity.Agent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 对话式生成 Agent 向导后端入口。流/取消复用 run 原语（按 runId）。 */
@Tag(name = "对话式生成 Agent", description = "Agent 构建器向导")
@RestController
@RequestMapping("/data/admin/agent-builder")
@RequiredArgsConstructor
public class AgentBuilderController {

    private final AgentBuilderService builderService;
    private final AgentBuilderRunService runService;
    private final AgentBuilderFinalizeService finalizeService;
    private final ChatConversationService conversationService;
    private final ChatRunService chatRunService;

    @Operation(summary = "开一个生成会话")
    @PostMapping("/sessions")
    public StartSessionResponse startSession() {
        Agent builder = builderService.ensureBuilderAgent();
        CreateConversationRequest req = new CreateConversationRequest();
        req.setAgentId(String.valueOf(builder.getId()));
        req.setAgentName(builder.getName());
        req.setTitle("生成 Agent");
        ConversationView c = conversationService.create(req);

        StartSessionResponse resp = new StartSessionResponse();
        resp.setConversationId(c.getId());
        resp.setDraft(builderService.getDraft(c.getId(), null));
        return resp;
    }

    @Operation(summary = "发一轮（服务端生成，立即返回 runId）")
    @PostMapping("/sessions/{id}/turns")
    public TurnStartResponse turn(@PathVariable("id") Long conversationId,
                                  @RequestBody TurnRequest req,
                                  HttpServletRequest request) {
        return runService.startTurn(conversationId, req, extractTraceId(request));
    }

    @Operation(summary = "草稿落地为 DRAFT Agent")
    @PostMapping("/sessions/{id}/finalize")
    public FinalizeResponse finalizeSession(@PathVariable("id") Long conversationId,
                                            @RequestBody FinalizeRequest req) {
        conversationService.requireConversationWithAccess(conversationId);
        Long agentId = finalizeService.finalize(conversationId, req);
        FinalizeResponse resp = new FinalizeResponse();
        resp.setAgentId(agentId);
        return resp;
    }

    @Operation(summary = "读当前草稿（重连恢复预览）")
    @GetMapping("/sessions/{id}/draft")
    public Object draft(@PathVariable("id") Long conversationId) {
        conversationService.requireConversationWithAccess(conversationId);
        return builderService.getDraft(conversationId, conversationService.getBuilderDraft(conversationId));
    }

    @Operation(summary = "消费/重连生成流")
    @GetMapping("/runs/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(value = "from", required = false) String from,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        assertRunOwner(runId);
        String fromId = StrUtil.isNotBlank(lastEventId) ? lastEventId : from;
        return chatRunService.attachViewer(runId, fromId);
    }

    @Operation(summary = "取消生成")
    @PostMapping("/runs/{runId}/cancel")
    public void cancel(@PathVariable String runId) {
        assertRunOwner(runId);
        chatRunService.cancelRun(runId);
    }

    /** 据 runId 反查会话并校验当前账号有访问权（owner / agent 访问，复用会话鉴权）。 */
    private void assertRunOwner(String runId) {
        Long conversationId = conversationService.conversationIdOfRun(runId);
        if (conversationId == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "run 不存在: " + runId);
        }
        conversationService.requireConversationWithAccess(conversationId);
    }

    private String extractTraceId(HttpServletRequest request) {
        String tid = request.getHeader("X-Trace-Id");
        return StrUtil.isBlank(tid) ? UUID.randomUUID().toString() : tid;
    }
}

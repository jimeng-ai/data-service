package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.run.ChatRunService;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 对话式生成 Skill 向导后端入口。镜像 AgentBuilderController：
 * 流/取消复用 run 原语（按 runId），会话在 {@code /sessions/{id}/} 下，SSE/取消在 {@code /runs/{runId}/} 下。
 *
 * <p>额外端点（Skill-only）：
 * <ul>
 *   <li>{@code POST /sessions/{id}/test-run} — 沙箱试跑内存草稿（仅 DOER 类型）。</li>
 *   <li>{@code POST /{draftId}/finalize} — 把 DRAFT ai_skill 行翻转为 ACTIVE（按 skill 行 id）。</li>
 *   <li>{@code POST /sessions/{id}/finalize} — 同上，按构建器会话 id 查找对应草稿行再翻转。</li>
 * </ul>
 */
@Tag(name = "对话式生成 Skill", description = "Skill 构建器向导")
@RestController
@RequestMapping("/data/tenant/skills/builder")
@RequiredArgsConstructor
public class SkillBuilderController {

    private final SkillBuilderService builderService;
    private final SkillBuilderRunService runService;
    private final SkillBuilderFinalizeService finalizeService;
    private final ChatConversationService conversationService;
    private final ChatRunService chatRunService;
    private final AiSkillMapper aiSkillMapper;

    // ------------------------------------------------------------------ session

    @Operation(summary = "开一个 Skill 生成会话")
    @PostMapping("/sessions")
    public StartSessionResponse startSession() {
        Agent builder = builderService.ensureBuilderAgent();
        CreateConversationRequest req = new CreateConversationRequest();
        req.setAgentId(String.valueOf(builder.getId()));
        req.setAgentName(builder.getName());
        req.setTitle("生成 Skill");
        ConversationView c = conversationService.create(req);

        StartSessionResponse resp = new StartSessionResponse();
        resp.setConversationId(c.getId());
        resp.setDraft(runService.currentDraft(c.getId()));
        return resp;
    }

    // ------------------------------------------------------------------ turns

    @Operation(summary = "发一轮（服务端生成，立即返回 runId）")
    @PostMapping("/sessions/{id}/turns")
    public TurnStartResponse turn(@PathVariable("id") Long conversationId,
                                  @RequestBody TurnRequest req,
                                  HttpServletRequest request) {
        return runService.startTurn(conversationId, req, extractTraceId(request));
    }

    // ------------------------------------------------------------------ stream / cancel  (mirror agent-builder exactly)

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

    // ------------------------------------------------------------------ draft

    @Operation(summary = "读当前草稿（重连恢复预览）")
    @GetMapping("/sessions/{id}/draft")
    public SkillDraft draft(@PathVariable("id") Long conversationId) {
        conversationService.requireConversationWithAccess(conversationId);
        return runService.currentDraft(conversationId);
    }

    // ------------------------------------------------------------------ test-run

    @Operation(summary = "沙箱试跑内存草稿（DOER 类型）")
    @PostMapping("/sessions/{id}/test-run")
    public SseEmitter testRun(@PathVariable("id") Long conversationId,
                              @RequestParam(value = "sampleFileId", required = false) Long sampleFileId,
                              HttpServletRequest request) {
        return runService.testRun(conversationId, sampleFileId, extractTraceId(request));
    }

    // ------------------------------------------------------------------ finalize

    /**
     * 把 DRAFT ai_skill 行翻转为 ACTIVE，按 skill 行 id（推荐，前端从 draft_skill 工具回调得到该 id）。
     */
    @Operation(summary = "把指定 DRAFT skill 行翻转为 ACTIVE（按 skill id）")
    @PostMapping("/{draftId}/finalize")
    public FinalizeResponse finalize(@PathVariable Long draftId) {
        AiSkill skill = finalizeService.finalizeDraft(
                draftId,
                AdminRequestContext.requireTenantId(),
                AdminRequestContext.requireUserId());
        return toFinalizeResponse(skill);
    }

    /**
     * 把 DRAFT ai_skill 行翻转为 ACTIVE，按构建器会话 id（通过 originRef 查找对应草稿行）。
     */
    @Operation(summary = "把 DRAFT skill 行翻转为 ACTIVE（按构建器会话 id）")
    @PostMapping("/sessions/{id}/finalize")
    public FinalizeResponse finalizeSession(@PathVariable("id") Long conversationId) {
        conversationService.requireConversationWithAccess(conversationId);
        String originRef = "builder:" + conversationId;
        AiSkill draft = aiSkillMapper.selectOne(new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getOriginRef, originRef)
                .last("limit 1"));
        if (draft == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "该会话尚无关联的 skill 草稿");
        }
        AiSkill skill = finalizeService.finalizeDraft(
                draft.getId(),
                AdminRequestContext.requireTenantId(),
                AdminRequestContext.requireUserId());
        return toFinalizeResponse(skill);
    }

    // ------------------------------------------------------------------ helpers

    /** 据 runId 反查会话并校验当前账号有访问权（镜像 AgentBuilderController）。 */
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

    private FinalizeResponse toFinalizeResponse(AiSkill skill) {
        FinalizeResponse resp = new FinalizeResponse();
        resp.setSkillId(skill.getId());
        resp.setName(skill.getName());
        resp.setStatus(skill.getStatus());
        return resp;
    }

    // ------------------------------------------------------------------ inner DTOs

    @Data
    public static class StartSessionResponse {
        private Long conversationId;
        private SkillDraft draft;
    }

    @Data
    public static class FinalizeResponse {
        private Long skillId;
        private String name;
        private String status;
    }
}

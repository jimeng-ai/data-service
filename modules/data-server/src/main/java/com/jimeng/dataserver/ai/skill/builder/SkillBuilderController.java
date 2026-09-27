package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.run.ChatRunService;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.CreateSessionRequest;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.FeedbackRequest;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.PublishResult;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReportView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReviewView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.SessionView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.SkillTypeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Skill 构建器：在沙箱里原样跑 Anthropic skill-creator，帮用户新建或改进一个 skill。
 * 每个会话都只对属主可见（{@link SkillBuilderSessionService#requireOwned}）。
 */
@Tag(name = "Skill 构建器")
@RestController
@RequestMapping("/data/tenant/skills/builder")
@RequiredArgsConstructor
public class SkillBuilderController {

    private final SkillBuilderSessionService sessions;
    private final SkillBuilderRunService runs;
    private final SkillBuilderPublishService publisher;
    private final ChatConversationService conversationService;
    private final ChatRunService chatRunService;

    @Operation(summary = "开一个构建器会话（带 baseSkillId = 改进已有 skill；同一个 skill 有进行中的会话则返回它）")
    @PostMapping("/sessions")
    public SessionView create(@RequestBody(required = false) CreateSessionRequest req) {
        return sessions.create(req == null ? null : req.getBaseSkillId());
    }

    @Operation(summary = "读会话：聊天记录、当前草稿、最新评审页元数据、进行中的 run（刷新页面后恢复用）")
    @GetMapping("/sessions/{id}")
    public SessionView get(@PathVariable Long id) {
        return sessions.get(id);
    }

    @Operation(summary = "发一轮（服务端在沙箱里跑，立即返回 runId）")
    @PostMapping("/sessions/{id}/turns")
    public TurnStartResponse turn(@PathVariable Long id, @RequestBody TurnRequest req, HttpServletRequest request) {
        return runs.startTurn(id, req, traceIdOf(request));
    }

    @Operation(summary = "消费 / 重连生成流")
    @GetMapping("/runs/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(value = "from", required = false) String from,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        assertRunOwner(runId);
        return chatRunService.attachViewer(runId, StrUtil.isNotBlank(lastEventId) ? lastEventId : from);
    }

    @Operation(summary = "停止这一轮（边车 docker kill，工作区照常写回已完成的部分）")
    @PostMapping("/runs/{runId}/cancel")
    public void cancel(@PathVariable String runId) {
        assertRunOwner(runId);
        chatRunService.cancelRun(runId);
    }

    @Operation(summary = "最新一轮评审页（skill-creator 的 generate_review.py --static 产物）与已提交的反馈")
    @GetMapping("/sessions/{id}/review")
    public ReviewView review(@PathVariable Long id) {
        return sessions.review(id);
    }

    @Operation(summary = "保存评审页提交的反馈（写成该轮的 feedback.json，下一轮 skill-creator 从那里读）")
    @PutMapping("/sessions/{id}/review/feedback")
    public void feedback(@PathVariable Long id, @RequestBody FeedbackRequest req) {
        sessions.saveFeedback(id, req == null ? null : req.getIteration(), req == null ? null : req.getFeedback());
    }

    @Operation(summary = "description 触发优化报告（run_loop.py 的 --report 产物）")
    @GetMapping("/sessions/{id}/optimization")
    public ReportView optimization(@PathVariable Long id) {
        return sessions.optimizationReport(id);
    }

    @Operation(summary = "指定 skill 在产品里的运行方式（PROMPT / DOER；null = 按附带文件自动推断）")
    @PutMapping("/sessions/{id}/skill-type")
    public void skillType(@PathVariable Long id, @RequestBody(required = false) SkillTypeRequest req) {
        sessions.setSkillType(id, req == null ? null : StrUtil.emptyToNull(req.getSkillType()));
    }

    @Operation(summary = "发布（按 quick_validate 规则校验 → 版本化 bundle → 上线）")
    @PostMapping("/sessions/{id}/publish")
    public PublishResult publish(@PathVariable Long id) {
        return publisher.publish(id);
    }

    private void assertRunOwner(String runId) {
        Long conversationId = conversationService.conversationIdOfRun(runId);
        if (conversationId == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "run 不存在: " + runId);
        conversationService.requireConversationWithAccess(conversationId);
    }

    private static String traceIdOf(HttpServletRequest request) {
        String tid = request.getHeader("X-Trace-Id");
        return StrUtil.isBlank(tid) ? UUID.randomUUID().toString() : tid;
    }
}

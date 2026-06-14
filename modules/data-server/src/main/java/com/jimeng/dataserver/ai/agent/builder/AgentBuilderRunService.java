package com.jimeng.dataserver.ai.agent.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService.TurnMessageIds;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.run.ConversationRunLock;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.run.RunState;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 构建器单开的可重连一轮生成：复用 run 原语，dispatch 到 streamBuild（不进沙箱）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBuilderRunService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final ChatConversationService conversationService;
    private final ChatMessageMapper messageMapper;
    private final AgentInputFileMapper inputFileMapper;
    private final PermissionResolver permissionResolver;
    private final BuilderAttachmentService attachmentService;
    private final ConversationRunLock lock;
    private final RunRegistry runRegistry;
    private final RunFinalizer runFinalizer;
    private final RunEventTee tee;
    private final ClaudeService claudeService;
    private final AgentBuilderService builderService;
    private final ThreadPoolTaskExecutor streamExecutor;

    public TurnStartResponse startTurn(Long conversationId, TurnRequest req, String traceId) {
        String query = req == null ? null : req.getQuery();
        if (query == null || query.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "query 不能为空");
        }
        conversationService.requireConversationWithAccess(conversationId);

        List<Long> fileIds = req.getFileIds();
        // 输入文件按人私有：只允许使用自己上传的文件（防 IDOR）。
        assertOwnFiles(fileIds);

        String runId = UUID.randomUUID().toString();
        if (!lock.tryAcquire(conversationId, runId) || conversationService.hasActiveGeneration(conversationId)) {
            lock.release(conversationId, runId);
            throw new ServiceException(ExceptionCode.CONVERSATION_GENERATING, "该会话正在生成，请稍候");
        }

        TurnMessageIds ids;
        try {
            ids = conversationService.insertTurnMessages(conversationId, query, req.getAttachments(), runId);
        } catch (RuntimeException e) {
            lock.release(conversationId, runId);
            throw e;
        }

        RunState state = new RunState(System.currentTimeMillis());
        runRegistry.register(new RunHandle(runId, conversationId, ids.assistantMessageId(),
                TenantContext.get(), state));

        streamExecutor.execute(MdcAsyncSupport.wrap(runId,
                () -> streamBuild(conversationId, query, fileIds, runId, traceId)));

        return new TurnStartResponse(runId, ids.userMessageId(), ids.assistantMessageId());
    }

    /** 校验 fileIds 都属当前用户（超管放行）；非属主直接拒。 */
    private void assertOwnFiles(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return;
        for (Long id : fileIds) {
            AgentInputFile f = inputFileMapper.selectById(id);
            if (f == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "文件不存在: " + id);
            }
            permissionResolver.assertOwnerOrSuperAdmin(f.getCreateUser());
        }
    }

    /** executor 线程：构建器模型 + 仅 draft_agent 工具，走 ClaudeService 流式（与 RagAnswerService 同管线）。 */
    private void streamBuild(Long conversationId, String query, List<Long> fileIds,
                            String runId, String traceId) {
        BizTypeContext.set(BizTypeContext.AGENT_GEN);
        try {
            Agent builder = builderService.ensureBuilderAgent();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stream", true);
            body.put("messages", buildMessages(conversationId, query, fileIds));
            // 目录段先放入 system，prepareAgentContext 会把构建器 meta-prompt 前置 → 顺序：元提示词 + 目录。
            body.put("system", builderService.buildCatalogSystem());
            body.put("__agent_builder_mode__", Boolean.TRUE);     // SkillRuntimeService 据此只注入 draft_agent
            body.put("agent_id", String.valueOf(builder.getId()));
            body.put("agent_preview", Boolean.TRUE);              // 读实时构建器 Agent（无需发布快照）

            AgentIdContext.set(String.valueOf(builder.getId()));
            claudeService.prepareAgentContext(body);
            claudeService.messagesStream(body, runId, traceId);
        } catch (Exception e) {
            log.error("构建器生成失败 runId={}", runId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", String.valueOf(e.getMessage()));
            try {
                tee.teeJson(runId, "error", err);
                runFinalizer.complete(runId);
            } catch (Exception ignore) {
                // 兜底收尾失败忽略
            }
        } finally {
            runFinalizer.complete(runId);    // 幂等兜底
        }
    }

    /** 历史按纯文本；本轮 user 消息在有附件时组装成多模态 content 数组（图片块 + 文档文本块 + 提问文本块）。 */
    private List<Map<String, Object>> buildMessages(Long conversationId, String query, List<Long> fileIds) {
        List<ChatMessage> history = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getStatus, STATUS_COMPLETED)
                .orderByAsc(ChatMessage::getId));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m.getContent() == null || m.getContent().isEmpty()) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("role", ROLE_USER.equals(m.getRole()) ? ROLE_USER : ROLE_ASSISTANT);
            one.put("content", m.getContent());
            messages.add(one);
        }
        // 去掉末条「与本轮 query 相同的 user 文本」占位，改用多模态版本替换。
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if (ROLE_USER.equals(last.get("role")) && query.equals(last.get("content"))) {
                messages.remove(messages.size() - 1);
            }
        }

        List<Map<String, Object>> attachmentBlocks = attachmentService.toContentBlocks(fileIds);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", ROLE_USER);
        if (attachmentBlocks.isEmpty()) {
            userMsg.put("content", query);          // 纯文本
        } else {
            List<Map<String, Object>> content = new ArrayList<>(attachmentBlocks);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("type", "text");
            q.put("text", query);
            content.add(q);
            userMsg.put("content", content);        // 多模态数组
        }
        messages.add(userMsg);
        return messages;
    }
}

package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.builder.BuilderAttachmentService;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService.TurnMessageIds;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.run.ConversationRunLock;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.run.RunState;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.SkillBundleResolver;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Skill 构建器单开的可重连一轮生成：镜像 {@link com.jimeng.dataserver.ai.agent.builder.AgentBuilderRunService}，
 * 复用同一套 run 原语（{@link ConversationRunLock}/{@link RunRegistry}/{@link RunEventTee}/{@link RunFinalizer}）
 * 与流式管线（{@link ClaudeService#messagesStream}）。差异：
 * <ul>
 *   <li>设 {@code __skill_builder_mode__=TRUE}（而非 agent_builder），SkillRuntimeService 据此只注入 draft_skill。</li>
 *   <li>计费 biz_type 用 {@code skill_gen}（{@link BizTypeContext#SKILL_GEN}）。</li>
 *   <li>每个会话维持一份内存态 {@link SkillDraft}（含 files/脚本——DRAFT ai_skill 行不存这些），
 *       供试跑/finalize 取用。{@link DraftSkillToolExecutor} 处理一次 draft_skill 后回调
 *       {@link #mergeDraft} 合并增量。</li>
 * </ul>
 *
 * <p>另含沙箱试跑（{@link #testRun}）：把内存草稿物化到 MinIO 临时前缀，构造 SkillRef 后复用边车 dispatch 跑一轮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderRunService {

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
    private final SkillBuilderService builderService;
    private final ThreadPoolTaskExecutor streamExecutor;

    // 试跑依赖（复用 exec 的边车 dispatch 链路）。
    private final RagMinioStorageService storage;
    private final SidecarClient sidecarClient;
    private final AgentSandboxProperties sandboxProps;
    private final SseServiceUtil sseServiceUtil;

    /** 每会话一份内存草稿（含 files/脚本）。draft_skill 执行器合并增量，试跑/finalize 取用。 */
    private final ConcurrentHashMap<Long, SkillDraft> draftByConversation = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ turn / stream

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

    /** executor 线程：构建器模型 + 仅 draft_skill 工具，走 ClaudeService 流式（与 AgentBuilderRunService 同管线）。 */
    private void streamBuild(Long conversationId, String query, List<Long> fileIds,
                             String runId, String traceId) {
        BizTypeContext.set(BizTypeContext.SKILL_GEN);   // 计费按「skill 生成」记账（Task 7 运营平台展示用）
        try {
            Agent builder = builderService.ensureBuilderAgent();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stream", true);
            body.put("messages", buildMessages(conversationId, query, fileIds));
            body.put("__skill_builder_mode__", Boolean.TRUE);    // SkillRuntimeService 据此只注入 draft_skill
            body.put("agent_id", String.valueOf(builder.getId()));
            body.put("agent_preview", Boolean.TRUE);             // 读实时构建器 Agent（无需发布快照）

            AgentIdContext.set(String.valueOf(builder.getId()));
            claudeService.prepareAgentContext(body);
            claudeService.messagesStream(body, runId, traceId);
        } catch (Exception e) {
            log.error("Skill 构建器生成失败 runId={}", runId, e);
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

    // ------------------------------------------------------------------ in-memory draft

    /**
     * 合并一次 draft_skill 增量到该会话的内存草稿，并返回合并后的快照。
     * 由 {@link DraftSkillToolExecutor} 在对话循环执行 draft_skill 后回调（runId→conversationId 已解析）。
     */
    public SkillDraft mergeDraft(Long conversationId, Map<String, Object> patch) {
        SkillDraft draft = draftByConversation.computeIfAbsent(conversationId, k -> new SkillDraft());
        SkillDraftMerger.merge(draft, patch);
        return draft;
    }

    /** 取该会话当前内存草稿（试跑/finalize 用）；无则 null。 */
    public SkillDraft currentDraft(Long conversationId) {
        return draftByConversation.get(conversationId);
    }

    // ------------------------------------------------------------------ sandbox dry-run

    /**
     * 沙箱试跑：用当前内存草稿（DOER + files）跑一轮，验证脚本能处理样例输入并产出结果。
     * 不落 AgentExecRun（一次性、throwaway），直接把边车 SSE 桥接给调用方，事件与 exec 一致
     * （progress / code_output / claude-delta / tool_result / artifact / summary / error）。
     *
     * @param conversationId 会话 id（取内存草稿）
     * @param sampleFileId   样例输入文件 id（来自 POST /data/agent/files），按人私有校验；可空（无输入也能跑）
     * @param traceId        链路 id（可空）
     */
    public SseEmitter testRun(Long conversationId, Long sampleFileId, String traceId) {
        conversationService.requireConversationWithAccess(conversationId);

        SkillDraft draft = currentDraft(conversationId);
        if (draft == null
                || !SkillConst.TYPE_DOER.equalsIgnoreCase(draft.getSkillType())
                || draft.getFiles() == null || draft.getFiles().isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "草稿无可执行脚本，无需试跑");
        }
        String draftName = StrUtil.blankToDefault(draft.getName(), "draft-skill");

        // 校验样例文件属当前用户（防 IDOR），并取出其 MinIO 引用。
        AgentInputFile sample = null;
        if (sampleFileId != null) {
            sample = inputFileMapper.selectById(sampleFileId);
            if (sample == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "文件不存在: " + sampleFileId);
            }
            permissionResolver.assertOwnerOrSuperAdmin(sample.getCreateUser());
        }

        String tenantId = TenantContext.get();
        String userId = AdminRequestContext.findUserIdOrNull() == null
                ? null : String.valueOf(AdminRequestContext.findUserIdOrNull());

        // 1. 物化草稿到 MinIO 临时前缀 skills/draft/{conversationId}/，构造 SkillRef。
        String prefix = "skills/draft/" + conversationId + "/";
        SidecarRunPayload.SkillRef ref = materializeDraft(draftName, prefix, draft);

        // 2. 组 dispatch payload（一次性，不落 AgentExecRun）。
        SidecarRunPayload payload = buildDryRunPayload(tenantId, userId, traceId, draftName, ref, sample);

        // 3. 桥接边车 SSE 给调用方（镜像 AgentExecController 的连接管理）。
        String connectionId = UUID.randomUUID().toString();
        long timeoutMs = (sandboxProps.getWallClockSec() + 60L) * 1000L;
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, timeoutMs);
        streamExecutor.execute(MdcAsyncSupport.wrap(connectionId,
                () -> dispatchDryRun(payload, connectionId)));
        return emitter;
    }

    /** 把 SKILL.md（draft.body）+ files 写到 MinIO 临时前缀，列出对象并构造 SkillRef。 */
    private SidecarRunPayload.SkillRef materializeDraft(String draftName, String prefix, SkillDraft draft) {
        try {
            if (StrUtil.isNotBlank(draft.getBody())) {
                storage.putObject(prefix + "SKILL.md",
                        draft.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown");
            }
            for (Map.Entry<String, String> e : draft.getFiles().entrySet()) {
                String rel = e.getKey();
                if (StrUtil.isBlank(rel)) continue;
                // 防越界：去掉前导 / 与 ../，保证写在前缀内。
                String safeRel = rel.replace("\\", "/").replaceAll("^/+", "").replace("../", "");
                String content = e.getValue() == null ? "" : e.getValue();
                storage.putObject(prefix + safeRel,
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
            }
            List<String> objects = storage.listObjects(prefix);
            return SkillBundleResolver.toSkillRef(draftName, prefix, storage.getBucket(), objects);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "物化草稿到沙箱失败: " + e.getMessage());
        }
    }

    /** 组试跑 dispatch payload：注入草稿 skill + 样例输入 + 固定提示，LLM/limits 取沙箱配置。 */
    private SidecarRunPayload buildDryRunPayload(String tenantId, String userId, String traceId,
                                                 String draftName, SidecarRunPayload.SkillRef ref,
                                                 AgentInputFile sample) {
        SidecarRunPayload payload = new SidecarRunPayload();
        payload.setRunId("dryrun-" + UUID.randomUUID());
        payload.setTenantId(tenantId);
        payload.setUserId(userId);
        payload.setTraceId(traceId);
        payload.setPrompt("用名为「" + draftName + "」的 skill 处理输入并产出结果；"
                + "若有输入文件，请按 skill 指引完整处理后给出产物。");
        if (sample != null) {
            SidecarRunPayload.InputFile in = new SidecarRunPayload.InputFile();
            in.setObjectName(sample.getObjectName());
            in.setFilename(sample.getFilename());
            in.setBucket(sample.getBucket());
            in.setSizeBytes(sample.getSizeBytes());
            payload.setInputFiles(List.of(in));
        }
        payload.setArtifactBucket(storage.getBucket());
        payload.setSkills(List.of(ref));

        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(sandboxProps.getLlm().getBaseUrl());
        llm.setAuthToken(sandboxProps.getLlm().getAuthToken());
        llm.setModel(sandboxProps.getLlm().getModel());
        llm.setAuthScheme(sandboxProps.getLlm().getAuthScheme());
        payload.setLlm(llm);

        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(sandboxProps.getWallClockSec());
        limits.setMaxTurns(sandboxProps.getMaxTurns());
        limits.setMaxBudgetUsd(sandboxProps.getMaxBudgetUsd());
        payload.setLimits(limits);
        return payload;
    }

    /** executor 线程：调边车并把 SSE 原样桥接给调用方；产物事件直接透传（试跑不落 artifact 库）。 */
    private void dispatchDryRun(SidecarRunPayload payload, String connectionId) {
        final CountDownLatch latch = new CountDownLatch(1);
        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (type == null) return;
                tee.tee(connectionId, type, data);
            }

            @Override
            public void onClosed(EventSource es) {
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                String msg = t != null ? t.getMessage()
                        : ("sidecar http " + (response != null ? response.code() : "?"));
                log.error("skill 试跑边车流式失败 connectionId={} err={}", connectionId, msg);
                tee.tee(connectionId, "error", new JSONObject().set("message", msg).toString());
                latch.countDown();
            }
        };
        try {
            EventSource upstream = sidecarClient.run(payload, listener);
            RunHandle handle = runRegistry.get(connectionId);
            if (handle != null) handle.setUpstream(upstream);
            boolean done = latch.await(sandboxProps.getWallClockSec() + 30L, TimeUnit.SECONDS);
            if (!done) {
                tee.tee(connectionId, "error", new JSONObject().set("message", "timeout").toString());
            }
        } catch (Exception e) {
            log.error("skill 试跑调用边车异常 connectionId={}", connectionId, e);
            tee.tee(connectionId, "error", new JSONObject().set("message", String.valueOf(e.getMessage())).toString());
        } finally {
            runFinalizer.complete(connectionId);
        }
    }
}

package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService.TurnMessageIds;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.run.ChatHistoryReconstructor;
import com.jimeng.dataserver.ai.run.ConversationRunLock;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.run.RunState;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftUpdateEvent;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Skill 构建器的一轮：把用户这句话派发到边车的 skill-builder 运行形态（沙箱里原样跑 Anthropic skill-creator），
 * 流式转发给前端，结束后从工作区同步草稿。
 *
 * <p>复用对话链路的 run 原语（{@link ConversationRunLock} / {@link RunRegistry} / {@link RunEventTee} /
 * {@link RunFinalizer}），所以断线重连、多窗口续播、取消、聊天记录落库都和普通对话一样。差异：
 * <ul>
 *   <li>跑在专用的 {@code skillBuilderExecutor} 上（一轮可达一小时，不能占对话线程池）；</li>
 *   <li>limits / 模型取 {@link SkillBuilderProperties}，不动 {@code agent.sandbox.*} 的全局值；</li>
 *   <li>产物事件不转发：skill-creator 的产出都嵌在评审页里，不走 output/；</li>
 *   <li>结束时发一个 {@code draft-update}：草稿、最新评审页、触发优化报告都从工作区重新读出来。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderRunService {

    public static final String RUN_PROFILE = "skill-builder";
    /** 边车契约版本（capabilities.skillBuilderVersion）。低于它就不派发。 */
    static final int REQUIRED_SKILL_BUILDER_VERSION = 1;

    private final SkillBuilderSessionService sessions;
    private final SkillTriggerContextFactory triggerContextFactory;
    private final SkillBuilderProperties props;
    private final AgentSandboxProperties sandboxProps;
    private final SidecarClient sidecarClient;
    private final RagMinioStorageService storage;
    private final ChatConversationService conversationService;
    private final ChatHistoryReconstructor historyReconstructor;
    private final AgentInputFileMapper inputFileMapper;
    private final AiSkillMapper aiSkillMapper;
    private final PermissionResolver permissionResolver;
    private final ConversationRunLock lock;
    private final RunRegistry runRegistry;
    private final RunFinalizer runFinalizer;
    private final RunEventTee tee;
    private final UsageExtractor usageExtractor;
    private final AiModelCallRecordService recordService;
    private final ProviderRegistry providerRegistry;
    private final ThreadPoolTaskExecutor skillBuilderExecutor;

    public TurnStartResponse startTurn(Long sessionId, TurnRequest req, String traceId) {
        String query = req == null ? null : req.getQuery();
        if (StrUtil.isBlank(query)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "query 不能为空");
        }
        SkillBuilderSession session = sessions.requireActive(sessions.requireOwned(sessionId));
        Long conversationId = session.getConversationId();
        conversationService.requireConversationWithAccess(conversationId);
        List<AgentInputFile> inputs = ownInputFiles(req.getFileIds());

        String runId = UUID.randomUUID().toString();
        if (!lock.tryAcquire(conversationId, runId) || conversationService.hasActiveGeneration(conversationId)) {
            lock.release(conversationId, runId);
            throw new ServiceException(ExceptionCode.CONVERSATION_GENERATING, "构建器正在工作，请等这一轮结束");
        }
        TurnMessageIds ids;
        try {
            ids = conversationService.insertTurnMessages(conversationId, query, req.getAttachments(), runId);
        } catch (RuntimeException e) {
            lock.release(conversationId, runId);
            throw e;
        }
        runRegistry.register(new RunHandle(runId, conversationId, ids.assistantMessageId(),
                TenantContext.get(), new RunState(System.currentTimeMillis())));
        sessions.recordRun(sessionId, runId);

        Long cutoff = ids.userMessageId();
        try {
            skillBuilderExecutor.execute(MdcAsyncSupport.wrap(runId,
                    () -> dispatch(sessionId, query, inputs, runId, traceId, cutoff)));
        } catch (TaskRejectedException e) {
            log.warn("Skill 构建器执行器已满，拒绝 runId={}", runId);
            failEarly(runId, "构建器繁忙（同时在构建的会话太多），请稍后再发一次");
            runFinalizer.complete(runId);
        }
        return new TurnStartResponse(runId, ids.userMessageId(), ids.assistantMessageId());
    }

    /** 输入文件按人私有：只能用自己上传的（防 IDOR），超管放行。 */
    private List<AgentInputFile> ownInputFiles(List<Long> fileIds) {
        List<AgentInputFile> out = new ArrayList<>();
        if (fileIds == null) return out;
        for (Long id : fileIds) {
            AgentInputFile f = inputFileMapper.selectById(id);   // 租户隔离表
            if (f == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "文件不存在: " + id);
            permissionResolver.assertOwnerOrSuperAdmin(f.getCreateUser());
            out.add(f);
        }
        return out;
    }

    /** executor 线程：派发到边车并等它结束；无论怎样结束都同步草稿、记账、收尾。 */
    private void dispatch(Long sessionId, String query, List<AgentInputFile> inputs,
                          String runId, String traceId, Long cutoffMessageId) {
        BizTypeContext.set(BizTypeContext.SKILL_GEN);
        long startMs = System.currentTimeMillis();
        final String[] summaryHolder = new String[1];
        final String[] streamError = new String[1];
        String model = builderModel();
        try {
            // 归属已在请求线程上校验过；这里按 id 直接取，不依赖执行器线程上的请求上下文。
            SkillBuilderSession session = sessions.load(sessionId);
            int v = sidecarClient.skillBuilderVersion(Duration.ofSeconds(5));
            if (v < REQUIRED_SKILL_BUILDER_VERSION) {
                throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR,
                        "沙箱版本不支持 Skill 构建器（skillBuilderVersion=" + v + "），请先部署新版 jm-agent-sandbox");
            }
            SidecarRunPayload payload = buildPayload(session, query, inputs, runId, traceId, cutoffMessageId, model);

            CountDownLatch latch = new CountDownLatch(1);
            EventSourceListener listener = new EventSourceListener() {
                @Override
                public void onEvent(EventSource es, String id, String type, String data) {
                    if (type == null) return;
                    if ("artifact".equals(type)) {
                        // skill-creator 的产出嵌在评审页里；output/ 里出现东西说明模型没按约定来，记一笔即可。
                        log.info("构建器 run 产生了 output/ 产物（未转发） runId={} data={}", runId, StrUtil.maxLength(data, 200));
                        return;
                    }
                    if ("summary".equals(type)) summaryHolder[0] = data;
                    tee.tee(runId, type, data);
                }

                @Override
                public void onClosed(EventSource es) {
                    latch.countDown();
                }

                @Override
                public void onFailure(EventSource es, Throwable t, Response response) {
                    String msg = t != null ? t.getMessage() : ("sidecar http " + (response != null ? response.code() : "?"));
                    if (response != null && response.code() == 503) msg = "构建器繁忙（沙箱里正在跑别的构建），请稍后再发一次";
                    streamError[0] = msg;
                    log.error("构建器边车流式失败 runId={} err={}", runId, msg);
                    tee.tee(runId, "error", new JSONObject().set("message", msg).toString());
                    latch.countDown();
                }
            };
            EventSource upstream = sidecarClient.run(payload, listener);
            RunHandle handle = runRegistry.get(runId);
            if (handle != null) handle.setUpstream(upstream);   // 「停止」经它关闭上游，边车据此 docker kill
            if (!latch.await(props.getWallClockSec() + 60L, TimeUnit.SECONDS)) {
                streamError[0] = "timeout";
                upstream.cancel();
                tee.tee(runId, "error", new JSONObject().set("message", "这一轮超过了时间上限，已停止").toString());
            }
        } catch (Exception e) {
            streamError[0] = String.valueOf(e.getMessage());
            log.error("构建器派发异常 runId={}", runId, e);
            failEarly(runId, e instanceof ServiceException ? e.getMessage() : "构建器出错：" + e.getMessage());
        } finally {
            try {
                Boolean persisted = summaryHolder[0] == null ? null
                        : JSONUtil.parseObj(summaryHolder[0]).getBool("workspacePersisted");
                DraftUpdateEvent ev = sessions.syncAfterRun(sessionId, persisted);
                tee.teeJson(runId, "draft-update", ev);
            } catch (Exception e) {
                log.warn("构建器同步草稿失败 runId={} err={}", runId, e.getMessage());
            }
            recordUsage(sessionId, runId, summaryHolder[0], streamError[0], model, System.currentTimeMillis() - startMs);
            runFinalizer.complete(runId);
            BizTypeContext.clear();
        }
    }

    SidecarRunPayload buildPayload(SkillBuilderSession session, String query, List<AgentInputFile> inputs,
                                   String runId, String traceId, Long cutoffMessageId, String model) {
        AiSkill base = session.getBaseSkillId() == null ? null : aiSkillMapper.selectById(session.getBaseSkillId());

        SidecarRunPayload p = new SidecarRunPayload();
        p.setRunId(runId);
        p.setTenantId(session.getTenantId());
        p.setUserId(String.valueOf(session.getOwnerUserId()));
        p.setTraceId(traceId);
        p.setRunProfile(RUN_PROFILE);
        p.setPrompt(turnReminder(session, base) + "\n\n" + query);
        p.setHistory(history(session.getConversationId(), cutoffMessageId));
        p.setArtifactBucket(storage.getBucket());
        if (!inputs.isEmpty()) {
            List<SidecarRunPayload.InputFile> in = new ArrayList<>();
            for (AgentInputFile f : inputs) {
                SidecarRunPayload.InputFile x = new SidecarRunPayload.InputFile();
                x.setObjectName(f.getObjectName());
                x.setFilename(f.getFilename());
                x.setBucket(f.getBucket());
                x.setSizeBytes(f.getSizeBytes());
                in.add(x);
            }
            p.setInputFiles(in);
        }

        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(sandboxProps.getLlm().getBaseUrl());
        llm.setAuthToken(sandboxProps.getLlm().getAuthToken());
        llm.setAuthScheme(sandboxProps.getLlm().getAuthScheme());
        llm.setModel(model);
        p.setLlm(llm);

        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(props.getWallClockSec());
        limits.setMaxTurns(props.getMaxTurns());
        limits.setMaxBudgetUsd(props.getMaxBudgetUsd());
        p.setLimits(limits);

        SidecarRunPayload.Workspace ws = new SidecarRunPayload.Workspace();
        ws.setBucket(storage.getBucket());
        ws.setPrefix(session.getWorkspacePrefix());
        p.setWorkspace(ws);

        SidecarRunPayload.SkillBuilder sb = new SidecarRunPayload.SkillBuilder();
        sb.setTriggerEval(triggerContextFactory.build(base == null ? null : base.getName()));
        sb.setSkillTypeOverride(session.getSkillTypeOverride());
        sb.setCliMaxWorkers(props.getCliMaxWorkers());
        p.setSkillBuilder(sb);
        return p;
    }

    /**
     * 每轮放在用户消息前的精简提醒。与边车 skillBuilderContract.ts 的系统提示讲的是同一套约定：
     * dev 关 egress 时中转供应商可能把整个 system 换掉（边车 CLAUDE.md「系统提示词的送达」），
     * 放在用户消息里的这几句总能到达模型。改约定两处一起改。
     */
    static String turnReminder(SkillBuilderSession session, AiSkill base) {
        StringBuilder sb = new StringBuilder("【Skill 构建器·本轮提醒】按 skill-creator 的流程推进（先用 Skill 工具加载 skill-creator）。")
                .append("被构建的 skill 放在 /work/skill/<skill-name>/，工作区放在 /work/skill/<skill-name>-workspace/；")
                .append("评审页一律用 generate_review.py 的 --static 写到 <workspace>/iteration-N/review.html（不要起服务器），")
                .append("用户在右侧「评审」页查看并提交反馈（保存为同目录 feedback.json）。")
                .append("容器没有外网，本轮结束即回收：所有脚本在本轮内前台跑完。全程用简体中文和用户交流。");
        if (base != null) {
            sb.append("\n本会话是在改进已有 skill「").append(base.getName()).append("」（当前 v").append(base.getVersion())
                    .append("，已放在 /work/skill/").append(base.getName()).append("/）：动手前先快照当基线，发布时 name 保持不变。");
        }
        if (session.getSkillTypeOverride() != null) {
            sb.append("\n用户指定了这个 skill 在产品里的运行方式：")
                    .append("PROMPT".equals(session.getSkillTypeOverride())
                            ? "对话内注入（只用 SKILL.md，不要依赖附带文件）。" : "沙箱执行（可以附带脚本与参考资料）。");
        }
        return sb.toString();
    }

    private List<SidecarRunPayload.History> history(Long conversationId, Long cutoffMessageId) {
        List<Map<String, Object>> flat = historyReconstructor.reconstructFlatText(conversationId, cutoffMessageId, null);
        if (flat == null) return null;
        List<SidecarRunPayload.History> out = new ArrayList<>();
        for (Map<String, Object> h : flat) {
            if (h == null || h.get("role") == null || h.get("content") == null) continue;
            SidecarRunPayload.History one = new SidecarRunPayload.History();
            one.setRole("assistant".equals(String.valueOf(h.get("role"))) ? "assistant" : "user");
            one.setContent(String.valueOf(h.get("content")));
            out.add(one);
        }
        return out;
    }

    private String builderModel() {
        return StrUtil.blankToDefault(props.getModel(), sandboxProps.getLlm().getModel());
    }

    /** 派发前就失败（版本不对、执行器满、参数异常）：记进 RunState，让这条助手消息落成 FAILED 并带原因，而不是空气泡。 */
    private void failEarly(String runId, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        tee.teeJson(runId, "error", err);
        RunHandle h = runRegistry.get(runId);
        if (h != null && h.getState().getTerminalStatus() == null) {
            h.getState().setError(message);
            h.getState().markTerminal("FAILED");
        }
    }

    /**
     * 用量记账（ai_model_call_log，biz_type=skill_gen）。只含 CLI 主会话 + 子 agent 的用量——容器里的
     * `claude -p` 子进程（DOER 触发测试、改写 description）与 PROMPT 触发测试的直连调用不在 summary.usage 里，
     * 这是已知的计量缺口（见设计文档「已知限制」）。
     */
    private void recordUsage(Long sessionId, String runId, String summaryJson, String streamError, String model, long latencyMs) {
        if (summaryJson == null) return;
        try {
            JSONObject usageJson = JSONUtil.parseObj(summaryJson).getJSONObject("usage");
            if (usageJson == null) return;
            NormalizedUsage usage = usageExtractor.extract(usageJson);
            if (usage.getInputTokens() == null && usage.getOutputTokens() == null) return;
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("biz_type", BizTypeContext.SKILL_GEN);
            note.put("builder_session_id", String.valueOf(sessionId));
            note.put("run_id", runId);
            recordService.recordComputedCall(providerRegistry.activeProvider(), "sandbox:skill-builder", model,
                    BizTypeContext.SKILL_GEN, usage, streamError == null ? 200 : 500,
                    (int) Math.min(latencyMs, Integer.MAX_VALUE), note, null);
        } catch (Exception e) {
            log.warn("构建器用量记账失败 runId={} err={}", runId, e.getMessage());
        }
    }
}

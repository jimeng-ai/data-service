package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentExecRequest;
import com.jimeng.dataserver.ai.agent.exec.service.AgentExecService;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.TurnStartResponse;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService.TurnMessageIds;
import com.jimeng.dataserver.ai.rag.model.AnswerRequest;
import com.jimeng.dataserver.ai.rag.service.answer.RagAnswerService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端自持久化 + 可重连的一轮对话编排。
 *
 * <ul>
 *   <li>{@link #startTurn} 在请求线程鉴权/抢「每会话单活跃」锁/落两条消息/注册 run 句柄，再把生成转交
 *       {@code streamExecutor}，立即返回 runId——生成从此与浏览器连接解耦。</li>
 *   <li>{@link #attachViewer} 给「首次发送」和「切走重连/多窗口」用同一条续播路径：起一个 {@link RunReplayPump}
 *       从 Redis Stream 补播 + 实时跟随。</li>
 *   <li>{@link #cancelRun} 取消上游、走正常收尾落成 CANCELLED。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRunService {

    /** 观众 emitter 超时：足够看完一次（生成上限分钟级）的长生成；超时由泵在下次推送时感知并收尾。 */
    private static final long VIEWER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ChatConversationService chatConversationService;
    private final ConversationRunLock lock;
    private final RunRegistry runRegistry;
    private final RunFinalizer runFinalizer;
    private final RunEventTee tee;
    private final RunReplayPump pump;
    private final SseServiceUtil sseServiceUtil;
    private final RagAnswerService ragAnswerService;
    private final ChatHistoryReconstructor chatHistoryReconstructor;
    private final AgentExecService agentExecService;
    private final AgentInputFileMapper inputFileMapper;
    private final AgentRuntimeService agentRuntimeService;
    private final SkillTenantService skillTenantService;
    private final ThreadPoolTaskExecutor streamExecutor;
    private final ThreadPoolTaskExecutor runPumpExecutor;

    /**
     * 「本会话曾经传过文件 → 之后每一轮都走沙箱」这条判据的回看窗口，单位是<b>轮</b>（user 消息条数）。
     *
     * <h3>默认 0 = 不限，与改造前逐字一致</h3>
     * 本仓库 push main 即部署生产，所以默认值只能是「现状」。要收窄由运维按实测数据显式配。
     *
     * <h3>为什么不直接拍一个 N</h3>
     * 这条判据存在的理由是：后续轮次可能要引用先前上传的文件，而对话平面够不到它们。
     * 代价是纯查库的一轮也要起容器、拉附件。两边都是真的，取舍点在「隔多少轮之后就不会再提那个文件了」——
     * 那是个<b>经验值</b>，没有实测分布就是瞎拍。而拍错的方向是不对称的：
     * 多走一次沙箱只是慢，少走一次沙箱是模型看不见用户刚传的文件、还不报错。
     *
     * <p>（这条判据原先最严重的后果是「Agent 突然不会查数据库了」——那已经由沙箱平面接上
     * conn_* 工具解决，见 {@code docs/sandbox-connector-plane.md}。这里剩下的纯粹是成本。）
     */
    @Value("${chat.sandbox-stickiness-turns:0}")
    private int sandboxStickinessTurns;

    // ------------------------------------------------------------------ 发起一轮

    public TurnStartResponse startTurn(Long conversationId, TurnStartRequest req, String traceId) {
        if (req == null || StrUtil.isBlank(req.getQuery())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "query 不能为空");
        }
        // 请求线程鉴权（PermissionResolver 依赖请求级 ThreadLocal，不能放到 executor 线程）。
        chatConversationService.requireConversationWithAccess(conversationId);

        String runId = UUID.randomUUID().toString();
        if (!lock.tryAcquire(conversationId, runId) || chatConversationService.hasActiveGeneration(conversationId)) {
            lock.release(conversationId, runId);
            throw new ServiceException(ExceptionCode.CONVERSATION_GENERATING, "该会话正在生成回复，请稍候");
        }

        TurnMessageIds ids;
        try {
            ids = chatConversationService.insertTurnMessages(
                    conversationId, req.getQuery(), req.getAttachments(), runId);
        } catch (RuntimeException e) {
            lock.release(conversationId, runId);
            throw e;
        }

        // 先注册句柄再派发：tee/观众据此判定 run 在跑。
        RunState state = new RunState(System.currentTimeMillis());
        runRegistry.register(new RunHandle(runId, conversationId, ids.assistantMessageId(),
                TenantContext.get(), state));

        boolean exec = decideExec(conversationId, req.getFileIds(), req.getAgentId(), req.isPreview());
        // cutoff=本轮 user 消息 id：重建历史时排除本轮 user+assistant 占位消息（本轮 query 由 buildClaudeBody 单独追加）。
        Long cutoffMessageId = ids.userMessageId();
        streamExecutor.execute(MdcAsyncSupport.wrap(runId,
                () -> dispatchGeneration(runId, conversationId, req, exec, traceId, cutoffMessageId)));

        return new TurnStartResponse(runId, ids.userMessageId(), ids.assistantMessageId());
    }

    /** 在 executor 线程上跑生成（rag 或 exec）；事件经 tee 进 Redis+RunState，收尾统一走 RunFinalizer。 */
    private void dispatchGeneration(String runId, Long conversationId, TurnStartRequest req,
                                    boolean exec, String traceId, Long cutoffMessageId) {
        try {
            if (exec) {
                agentExecService.streamExec(toExecRequest(conversationId, req, cutoffMessageId), runId, traceId);
            } else {
                ragAnswerService.streamAnswer(toAnswerRequest(req, conversationId, cutoffMessageId), runId, traceId);
            }
        } catch (Exception e) {
            log.error("生成派发异常 runId={}", runId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", String.valueOf(e.getMessage()));
            tee.teeJson(runId, "error", err);
            // 把失败记到 RunState，否则 finally 的 finalize 会把这条助手消息落成 COMPLETED(空)，
            // 前端实时只闪一下 error 事件、刷新后却是空气泡。记为 FAILED+error 才能持久显示原因。
            // 适用于「主干在写入任何内容前就抛出」的早失败，如模型已下线/解析失败。
            RunHandle h = runRegistry.get(runId);
            if (h != null && h.getState().getTerminalStatus() == null) {
                h.getState().setError(String.valueOf(e.getMessage()));
                h.getState().markTerminal("FAILED");
            }
        } finally {
            // 兜底收尾：生成主干正常路径已自行 finalize（幂等），此处覆盖「主干在 complete 前抛出」的情形。
            runFinalizer.complete(runId);
        }
    }

    /**
     * 选执行平面：代码执行 Agent（沙箱）还是对话/RAG（JVM 内）。
     *
     * <p>原判据只有"有没有文件"。后果是：Agent 自己说了不算——一个绑了 DOER 技能
     * （带可执行脚本、只能在沙箱里跑）的 Agent，用户不传附件就永远够不到自己的技能，
     * 而且【不报错】：工具不存在，模型自己编一个答案。
     *
     * <p>现在加一条 OR：Agent 绑了任何 DOER 技能 → 也走沙箱。这不是完整的执行平面统一
     * （payload 仍缺 modelParams、多知识库仍被压成一个），但它让"Agent 的能力决定它在哪跑"
     * 这件事第一次成立。
     *
     * <p>判定顺序是刻意的：先看文件（一次内存判断 + 一次 count），技能查询只在前两者都为假时
     * 才发生，避免给纯对话路径平添一次查询。
     */
    private boolean decideExec(Long conversationId, List<Long> fileIds, String agentId, boolean preview) {
        if (fileIds != null && !fileIds.isEmpty()) {
            logRoute(conversationId, true, "本轮带附件");
            return true;
        }
        if (hasHistoricalInput(conversationId)) {
            logRoute(conversationId, true, "本会话此前传过文件（窗口="
                    + (sandboxStickinessTurns <= 0 ? "不限" : sandboxStickinessTurns + " 轮") + "）");
            return true;
        }
        boolean doer = hasSandboxOnlySkill(agentId, preview);
        logRoute(conversationId, doer, doer ? "Agent 绑定了只能在沙箱执行的技能（DOER）" : "无附件、无 DOER 技能");
        return doer;
    }

    /**
     * 本会话此前有没有传过文件。窗口 &lt;= 0 时看整条会话（默认，与改造前一致）；
     * 配了正数就只看最近 N 轮之内登记的输入文件。
     *
     * <p>按 id 降序取最近 N 条 user 消息、拿其中最老那条的 id 作下界，再问输入文件表有没有更新的行。
     * 用消息 id 而不是时间戳：雪花 id 单调且与消息顺序同源，时间戳会被时钟回拨影响。
     */
    private boolean hasHistoricalInput(Long conversationId) {
        LambdaQueryWrapper<AgentInputFile> q = new LambdaQueryWrapper<AgentInputFile>()
                .eq(AgentInputFile::getConversationId, conversationId);
        if (sandboxStickinessTurns > 0) {
            // 取不到下界（会话短于 N 轮）说明整条会话都在窗口内，不加条件。
            java.util.Date since = chatConversationService.userTurnCutoff(conversationId, sandboxStickinessTurns);
            if (since != null) {
                q.ge(AgentInputFile::getCreateTime, since);
            }
        }
        Long n = inputFileMapper.selectCount(q);
        return n != null && n > 0;
    }

    /**
     * 把路由判定写进日志。
     *
     * <p>这条判定过去是完全不可见的：用户只知道「Agent 的行为变了」，排查的人也没有任何痕迹可循，
     * 而它决定了整轮跑在哪个平面。想要收窄上面那个窗口，先得有这行日志攒出来的分布。
     */
    private void logRoute(Long conversationId, boolean exec, String reason) {
        log.info("对话路由 conversationId={} plane={} reason={}",
                conversationId, exec ? "sandbox" : "conversation", reason);
    }

    /** 该 Agent 是否绑定了必须在沙箱里执行的技能（DOER）。解析失败一律返回 false：
     *  路由判定出错时回落到旧行为（走对话平面），而不是把所有对话推进沙箱。 */
    private boolean hasSandboxOnlySkill(String agentId, boolean preview) {
        if (agentId == null || agentId.isBlank()) return false;
        try {
            Long id = Long.valueOf(agentId.trim());
            AgentRuntimeView view = agentRuntimeService.byId(id, preview);
            if (view == null) return false;
            java.util.Set<Long> allowed = view.getAllowedSkillIds();
            // allowedSkillIds==null 表示"无绑定信息"（老快照）。此时按旧行为处理：
            // 不因为无从判断就把会话推进沙箱——那会让所有历史 Agent 突然换平面。
            if (allowed == null || allowed.isEmpty()) return false;
            return !skillTenantService.listActiveDoerForRun(
                    TenantContext.get(), AdminRequestContext.findUserIdOrNull(), allowed).isEmpty();
        } catch (Exception e) {
            log.warn("判定 agent={} 是否需要沙箱平面失败，回落到对话平面: {}", agentId, e.getMessage());
            return false;
        }
    }

    private AgentExecRequest toExecRequest(Long conversationId, TurnStartRequest req, Long cutoffMessageId) {
        AgentExecRequest er = new AgentExecRequest();
        er.setAgentId(req.getAgentId());
        er.setConversationId(conversationId);
        er.setQuery(req.getQuery());
        er.setFileIds(req.getFileIds());
        er.setPreview(req.isPreview());
        // 用已落库 segments 重建【纯文本】历史：让模型看见自己上一轮调过工具 / 出过图 / 写过文件（否则空的
        // assistant 轮 → 沙箱裸 `assistant:` 行，模型改用文字叙述而不再真调工具）。重建失败 / 为空安全回退
        // 到前端纯文字 history。
        List<Map<String, Object>> history = chatHistoryReconstructor.reconstructFlatText(
                conversationId, cutoffMessageId, req.getHistory());
        if (history != null) {
            List<AgentExecRequest.History> hs = new ArrayList<>();
            for (Map<String, Object> h : history) {
                if (h == null) continue;
                Object role = h.get("role");
                Object content = h.get("content");
                // 跳过缺字段项，且绝不用 String.valueOf(null)——它会把缺失渲染成字面量 "null" 污染上下文
                // （回退的前端 history 可能缺 role/content）。角色也归一到 user/assistant。
                if (role == null || content == null) continue;
                AgentExecRequest.History one = new AgentExecRequest.History();
                one.setRole("assistant".equals(String.valueOf(role)) ? "assistant" : "user");
                one.setContent(String.valueOf(content));
                hs.add(one);
            }
            er.setHistory(hs);
        }
        return er;
    }

    private AnswerRequest toAnswerRequest(TurnStartRequest req, Long conversationId, Long cutoffMessageId) {
        // 用已落库 segments 重建带 tool_use/tool_result 的真实历史(让模型看见自己调过 generate_image 等工具，
        // 修多轮「只叙述不真出图」)；重建失败/为空安全回退到前端纯文字 history。
        List<Map<String, Object>> history = chatHistoryReconstructor.reconstructClaude(
                conversationId, cutoffMessageId, req.getHistory());
        return AnswerRequest.builder()
                .agentId(req.getAgentId())
                .kbId(req.getKbId())
                .query(req.getQuery())
                .topK(req.getTopK())
                .rerank(req.getRerank())
                .history(history)
                .preview(req.isPreview())
                .build();
    }

    // ------------------------------------------------------------------ 消费 / 取消

    /** 起一个续播观众。发送与重连同路：fromId 为空/0 从头补播，否则从该 stream id 之后跟随。 */
    public SseEmitter attachViewer(String runId, String fromId) {
        String viewerKey = runId + ":" + UUID.randomUUID();
        SseEmitter emitter = sseServiceUtil.getConnection(viewerKey, VIEWER_TIMEOUT_MS);
        runPumpExecutor.execute(MdcAsyncSupport.wrap(viewerKey, () -> pump.pump(runId, viewerKey, fromId)));
        return emitter;
    }

    public void cancelRun(String runId) {
        RunHandle h = runRegistry.get(runId);
        if (h != null) {
            h.cancel();
        }
        // 句柄不在（已结束 / 不在本机）：无需处理；遗留 GENERATING 由 OrphanRunReconciler 兜底。
    }
}

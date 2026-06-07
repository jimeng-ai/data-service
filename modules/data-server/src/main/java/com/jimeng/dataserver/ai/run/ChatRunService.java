package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
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
    private final AgentExecService agentExecService;
    private final AgentInputFileMapper inputFileMapper;
    private final ThreadPoolTaskExecutor streamExecutor;
    private final ThreadPoolTaskExecutor runPumpExecutor;

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

        boolean exec = decideExec(conversationId, req.getFileIds());
        streamExecutor.execute(MdcAsyncSupport.wrap(runId,
                () -> dispatchGeneration(runId, conversationId, req, exec, traceId)));

        return new TurnStartResponse(runId, ids.userMessageId(), ids.assistantMessageId());
    }

    /** 在 executor 线程上跑生成（rag 或 exec）；事件经 tee 进 Redis+RunState，收尾统一走 RunFinalizer。 */
    private void dispatchGeneration(String runId, Long conversationId, TurnStartRequest req,
                                    boolean exec, String traceId) {
        try {
            if (exec) {
                agentExecService.streamExec(toExecRequest(conversationId, req), runId, traceId);
            } else {
                ragAnswerService.streamAnswer(toAnswerRequest(req), runId, traceId);
            }
        } catch (Exception e) {
            log.error("生成派发异常 runId={}", runId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", String.valueOf(e.getMessage()));
            tee.teeJson(runId, "error", err);
        } finally {
            // 兜底收尾：生成主干正常路径已自行 finalize（幂等），此处覆盖「主干在 complete 前抛出」的情形。
            runFinalizer.complete(runId);
        }
    }

    /** 本轮带文件、或会话历史上传过文件 → 走代码执行 Agent；否则走对话/RAG。与前端原 mode 判定一致，但由服务端裁决。 */
    private boolean decideExec(Long conversationId, List<Long> fileIds) {
        if (fileIds != null && !fileIds.isEmpty()) return true;
        Long n = inputFileMapper.selectCount(new LambdaQueryWrapper<AgentInputFile>()
                .eq(AgentInputFile::getConversationId, conversationId));
        return n != null && n > 0;
    }

    private AgentExecRequest toExecRequest(Long conversationId, TurnStartRequest req) {
        AgentExecRequest er = new AgentExecRequest();
        er.setAgentId(req.getAgentId());
        er.setConversationId(conversationId);
        er.setQuery(req.getQuery());
        er.setFileIds(req.getFileIds());
        er.setPreview(req.isPreview());
        if (req.getHistory() != null) {
            List<AgentExecRequest.History> hs = new ArrayList<>();
            for (Map<String, Object> h : req.getHistory()) {
                AgentExecRequest.History one = new AgentExecRequest.History();
                one.setRole(String.valueOf(h.get("role")));
                one.setContent(String.valueOf(h.get("content")));
                hs.add(one);
            }
            er.setHistory(hs);
        }
        return er;
    }

    private AnswerRequest toAnswerRequest(TurnStartRequest req) {
        return AnswerRequest.builder()
                .agentId(req.getAgentId())
                .kbId(req.getKbId())
                .query(req.getQuery())
                .topK(req.getTopK())
                .rerank(req.getRerank())
                .history(req.getHistory())
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

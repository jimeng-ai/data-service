package com.jimeng.dataserver.ai.run;

import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.support.SseEventBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 生成收尾：把助手占位消息落成终态 + 写终止帧通知实时观众 + 释放会话锁 + 摘除句柄。
 *
 * <p>由生成主干（{@code AiConversationLoop} / {@code AgentExecService}）在原先调用 {@code sseBridge.complete}
 * 的所有终止点改调 {@link #complete(String)}。幂等：{@code registry.remove} 已摘除则后续调用 no-op。
 * 无句柄（调试台 {@code /claude/messages} / {@code /rag/answer} 直连）时回退到旧的结束直发 SSE。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunFinalizer {

    private final RunRegistry runRegistry;
    private final RunEventTee tee;
    private final ConversationRunLock lock;
    private final ChatConversationService chatConversationService;
    private final SseEventBridge sseBridge;

    public void complete(String runId) {
        RunHandle h = runRegistry.remove(runId);
        if (h == null) {
            // 调试台直连：无 run 句柄，按旧行为结束直发 SSE（无注册 emitter 则为 no-op）。
            sseBridge.complete(runId);
            return;
        }
        try {
            RunState s = h.getState();
            s.normalizeRunningTools();
            String status = h.isCancelled() ? "CANCELLED"
                    : (s.getTerminalStatus() != null ? s.getTerminalStatus() : "COMPLETED");
            chatConversationService.finalizeAssistant(h.getAssistantMessageId(), status,
                    s.getContent(), s.getSegmentsJsonOrNull(), s.getCitationsJsonOrNull(),
                    s.getElapsedMs(), s.getErrorMessage());
            tee.terminate(runId, status);
        } catch (Exception e) {
            log.error("生成收尾失败 runId={}", runId, e);
        } finally {
            lock.release(h.getConversationId(), runId);
        }
    }
}

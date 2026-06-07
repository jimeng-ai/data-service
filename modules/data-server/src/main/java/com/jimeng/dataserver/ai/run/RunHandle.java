package com.jimeng.dataserver.ai.run;

import lombok.Getter;
import okhttp3.sse.EventSource;

/**
 * 一次生成运行的在途句柄。仅活在产生它的实例 JVM 内（{@link RunRegistry}），
 * 进程重启即丢——重启遗留的 GENERATING 消息由 OrphanRunReconciler 兜底刷成 FAILED。
 */
@Getter
public class RunHandle {

    private final String runId;
    private final Long conversationId;
    private final Long assistantMessageId;
    private final String tenantId;
    private final RunState state;

    /** postStream 返回后回填：取消时 {@link EventSource#cancel()} 真正中断上游 LLM 调用 / 关闭到边车的上游请求。 */
    private volatile EventSource upstream;
    private volatile boolean cancelled;

    public RunHandle(String runId, Long conversationId, Long assistantMessageId, String tenantId, RunState state) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.assistantMessageId = assistantMessageId;
        this.tenantId = tenantId;
        this.state = state;
    }

    public void setUpstream(EventSource upstream) {
        this.upstream = upstream;
    }

    /** 标记取消并中断上游。上游断开后会走正常收尾路径，finalize 据 cancelled 落成 CANCELLED。 */
    public void cancel() {
        this.cancelled = true;
        EventSource up = this.upstream;
        if (up != null) {
            up.cancel();
        }
    }
}

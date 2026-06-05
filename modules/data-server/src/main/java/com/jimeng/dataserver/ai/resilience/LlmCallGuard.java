package com.jimeng.dataserver.ai.resilience;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM 调用熔断器（手写、轻量、全局单例）。
 *
 * <p>防雪崩：上游 LLM 网关整体故障时，连续失败达阈值即"跳闸"，后续调用立即快速失败，
 * 而不是每个都傻等 180s/5min 超时、占住有界流式线程池（{@code StreamExecutorConfig}，上限 200）。
 * 否则故障期线程池会被等待中的请求占满，连带拖垮同 JVM 的 web/admin/RAG。
 *
 * <p><b>默认关闭</b>（{@code ai.resilience.circuit-breaker.enabled=false}）：部署后运行时行为零变化，
 * 需在 Nacos 显式开启并观察后再放量。阈值保守——只在"连续 N 次"硬失败时跳闸，正常抖动不会误触发。
 * 只对【连接级异常 / HTTP 429 / 5xx】计失败；其它 4xx（客户端错误）不计入。
 *
 * <p>状态机：CLOSED →(连续失败≥阈值)→ OPEN →(冷却到点)→ HALF_OPEN →(探测成功)→ CLOSED /（探测失败）→ OPEN。
 */
@Slf4j
@Component
public class LlmCallGuard {

    @Value("${ai.resilience.circuit-breaker.enabled:false}")
    private boolean enabled;

    /** 连续失败多少次跳闸 */
    @Value("${ai.resilience.circuit-breaker.failure-threshold:10}")
    private int failureThreshold;

    /** 跳闸后熔断多久（毫秒），到点放一个探测请求 */
    @Value("${ai.resilience.circuit-breaker.open-ms:15000}")
    private long openMs;

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openUntil = 0L;

    /**
     * 调用 LLM 前调用。若熔断已打开且未到探测时间，抛 {@link ServiceException} 让本次调用快速失败。
     */
    public synchronized void acquirePermission() {
        if (!enabled) {
            return;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() >= openUntil) {
                state = State.HALF_OPEN;
                log.warn("LLM 熔断器进入 HALF_OPEN，放行一个探测请求");
            } else {
                throw new ServiceException(ExceptionCode.SERVICE_UNAVAILABLE,
                        "上游模型服务暂不可用（熔断中），请稍后重试");
            }
        }
    }

    public synchronized void recordSuccess() {
        if (!enabled) {
            return;
        }
        consecutiveFailures = 0;
        if (state != State.CLOSED) {
            log.info("LLM 熔断器恢复 CLOSED");
            state = State.CLOSED;
        }
    }

    public synchronized void recordFailure() {
        if (!enabled) {
            return;
        }
        consecutiveFailures++;
        // HALF_OPEN 下探测失败立即重新打开；CLOSED 下累计到阈值打开。
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openUntil = System.currentTimeMillis() + openMs;
            log.warn("LLM 熔断器跳闸 OPEN：连续失败 {} 次，熔断 {}ms", consecutiveFailures, openMs);
        }
    }
}

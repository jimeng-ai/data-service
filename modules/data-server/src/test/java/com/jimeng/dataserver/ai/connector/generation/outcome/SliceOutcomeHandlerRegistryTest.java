package com.jimeng.dataserver.ai.connector.generation.outcome;

import com.jimeng.dataserver.ai.connector.generation.GenerationReasonCode;
import com.jimeng.dataserver.ai.connector.generation.SliceRunKind;
import com.jimeng.dataserver.ai.connector.generation.SliceRunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceOutcomeHandlerRegistryTest {

    private final SliceOutcomeHandlerRegistry registry = new SliceOutcomeHandlerRegistry();

    @Test
    @DisplayName("注册表完整且唯一覆盖 SliceOutcomeKind")
    void 完整覆盖所有枚举() {
        assertEquals(Set.of(SliceOutcomeKind.values()), registry.kinds());
        for (SliceOutcomeKind kind : SliceOutcomeKind.values()) {
            assertEquals(kind, registry.handler(kind).kind());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SliceOutcomeHandlerRegistry(java.util.List.of(
                        registry.handler(SliceOutcomeKind.PROGRESS),
                        registry.handler(SliceOutcomeKind.PROGRESS))));
    }

    @Test
    @DisplayName("PROGRESS 清零两个 streak")
    void progress清零streak() {
        SliceOutcomeDecision decision = decide(SliceOutcomeKind.PROGRESS, 3, 4, 1, 3);
        assertEquals(SliceOutcomeAction.CONTINUE, decision.action());
        assertEquals(0, decision.sliceFailStreak());
        assertEquals(0, decision.noCallbackStreak());
    }

    @Test
    @DisplayName("NO_CALLBACK 第一次按 NO_PROGRESS 退避，连续第二次才降级")
    void noCallback连续2次() {
        SliceOutcomeDecision first = decide(SliceOutcomeKind.NO_CALLBACK, 0, 0, 0, 3);
        assertEquals(SliceOutcomeAction.RETRY, first.action());
        assertEquals(1, first.sliceFailStreak());
        assertEquals(1, first.noCallbackStreak());
        assertEquals(15, first.backoffSeconds());

        SliceOutcomeDecision second = decide(SliceOutcomeKind.NO_CALLBACK, 0,
                first.sliceFailStreak(), first.noCallbackStreak(), 3);
        assertEquals(SliceOutcomeAction.FALLBACK, second.action());
        assertEquals(GenerationReasonCode.NO_CALLBACK, second.reasonCode());
        assertEquals("降级原因：回调没有到达 data-service，请检查 callback-base-url 或 sandbox 版本。", second.note());
    }

    @Test
    @DisplayName("option B：已有 DONE 时 degradeOrInterrupt 一律中断")
    void done大于0不回落() {
        for (SliceOutcomeKind kind : Set.of(SliceOutcomeKind.SANDBOX_REJECTED,
                SliceOutcomeKind.SANDBOX_AUTH, SliceOutcomeKind.SANDBOX_UNAVAILABLE,
                SliceOutcomeKind.BUSY_EXHAUSTED)) {
            SliceOutcomeDecision decision = decide(kind, 1, 0, 0, 3);
            assertEquals(SliceOutcomeAction.INTERRUPT, decision.action(), kind.name());
            assertFalse(decision.note().startsWith("降级原因："), decision.note());
        }
    }

    @Test
    @DisplayName("option B：DONE 为 0 才回落且说明使用固定格式")
    void done为0才回落() {
        SliceOutcomeDecision decision = decide(SliceOutcomeKind.SANDBOX_UNAVAILABLE, 0, 0, 0, 3);
        assertEquals(SliceOutcomeAction.FALLBACK, decision.action());
        assertEquals(GenerationReasonCode.SANDBOX_UNAVAILABLE, decision.reasonCode());
        assertEquals("降级原因：sandbox 拒绝服务（未配置服务 token）。", decision.note());
    }

    @Test
    @DisplayName("NO_PROGRESS 指数退避封顶 300 秒，达到上限按 option B 停止")
    void noProgress退避和上限() {
        SliceOutcomeDecision retry = registry.decide(new SliceOutcomeContext(
                SliceOutcomeKind.NO_PROGRESS, result("timeout"), 0, 0, 5, 0, 10, 200));
        assertEquals(SliceOutcomeAction.RETRY, retry.action());
        assertEquals(300, retry.backoffSeconds());
        assertEquals(6, retry.sliceFailStreak());

        SliceOutcomeDecision fallback = decide(SliceOutcomeKind.NO_PROGRESS, 0, 2, 0, 3);
        assertEquals(SliceOutcomeAction.FALLBACK, fallback.action());
        assertTrue(fallback.note().contains("timeout"), fallback.note());
        assertEquals(GenerationReasonCode.NO_PROGRESS, fallback.reasonCode());

        SliceOutcomeDecision interrupt = decide(SliceOutcomeKind.NO_PROGRESS, 4, 2, 0, 3);
        assertEquals(SliceOutcomeAction.INTERRUPT, interrupt.action());
    }

    @Test
    @DisplayName("不可降级结局的动作与原因码固定")
    void 固定结局() {
        assertEquals(SliceOutcomeAction.ABORT, decide(SliceOutcomeKind.ABORTED, 0, 0, 0, 3).action());
        assertEquals(GenerationReasonCode.SHUTDOWN,
                decide(SliceOutcomeKind.SHUTDOWN, 0, 0, 0, 3).reasonCode());
        SliceOutcomeDecision mismatch = decide(SliceOutcomeKind.MODEL_MISMATCH, 0, 0, 0, 3);
        assertEquals(SliceOutcomeAction.FAIL, mismatch.action());
        assertEquals(GenerationReasonCode.MODEL_MISMATCH, mismatch.reasonCode());
        SliceOutcomeDecision budget = decide(SliceOutcomeKind.CLI_BUDGET, 0, 0, 0, 3);
        assertEquals(SliceOutcomeAction.INTERRUPT, budget.action());
        assertEquals(GenerationReasonCode.CLI_BUDGET, budget.reasonCode());
    }

    @Test
    @DisplayName("NO_PROGRESS 不把任意上游异常文本写进管理台说明")
    void noProgress原因只接受技术码() {
        SliceRunResult unsafe = new SliceRunResult(SliceRunKind.ERROR, true, false, null, null,
                "failed", "connection failed with sk-secret-value", null, 1, Set.of());
        SliceOutcomeDecision decision = registry.decide(new SliceOutcomeContext(
                SliceOutcomeKind.NO_PROGRESS, unsafe, 0, 0, 2, 0, 3, 15));

        assertEquals(SliceOutcomeAction.FALLBACK, decision.action());
        assertFalse(decision.note().contains("sk-secret-value"), decision.note());
        assertTrue(decision.note().contains("unknown"), decision.note());
    }

    private SliceOutcomeDecision decide(SliceOutcomeKind kind, int doneTables,
                                        int failStreak, int callbackStreak, int maxRetries) {
        return registry.decide(new SliceOutcomeContext(kind, result("timeout"), 0, doneTables,
                failStreak, callbackStreak, maxRetries, 15));
    }

    private static SliceRunResult result(String error) {
        return new SliceRunResult(SliceRunKind.ERROR, true, false, null, null,
                "failed", error, null, 0, Set.of());
    }
}

package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SliceOutcomeClassifierTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("按 6.9 表自上而下覆盖每种结局")
    void 按规格表分类(String label, SliceRunResult result, int progress, SliceOutcomeKind expected) {
        assertEquals(expected, SliceOutcomeClassifier.classify(result, progress), label);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of("ABORTED", result(SliceRunKind.ABORTED, false, false, null, null, null, 0), 9,
                        SliceOutcomeKind.ABORTED),
                Arguments.of("SHUTDOWN", result(SliceRunKind.SHUTDOWN, false, false, null, null, null, 0), 9,
                        SliceOutcomeKind.SHUTDOWN),
                Arguments.of("MODEL_MISMATCH", result(SliceRunKind.MODEL_MISMATCH, true, false, null, null, null, 0), 9,
                        SliceOutcomeKind.MODEL_MISMATCH),
                Arguments.of("SANDBOX_REJECTED", result(SliceRunKind.SANDBOX_REJECTED, false, false, 400, null, null, 0), 9,
                        SliceOutcomeKind.SANDBOX_REJECTED),
                Arguments.of("SANDBOX_AUTH", result(SliceRunKind.SANDBOX_AUTH, false, false, 401, null, null, 0), 9,
                        SliceOutcomeKind.SANDBOX_AUTH),
                Arguments.of("SANDBOX_UNAVAILABLE", result(SliceRunKind.SANDBOX_UNAVAILABLE, false, false, 503, null, null, 0), 9,
                        SliceOutcomeKind.SANDBOX_UNAVAILABLE),
                Arguments.of("BUSY_EXHAUSTED", result(SliceRunKind.BUSY_EXHAUSTED, false, false, 503, null, null, 0), 9,
                        SliceOutcomeKind.BUSY_EXHAUSTED),
                Arguments.of("CLI_BUDGET", result(SliceRunKind.COMPLETED, true, false, null, "failed",
                                "result:error_max_budget_usd", 1), 9, SliceOutcomeKind.CLI_BUDGET),
                Arguments.of("PROGRESS summary失败仍优先", result(SliceRunKind.COMPLETED, true, true, null, "failed",
                                "timeout", 0), 1, SliceOutcomeKind.PROGRESS),
                Arguments.of("NO_CALLBACK", result(SliceRunKind.COMPLETED, true, false, null, "success", null, 0), 0,
                        SliceOutcomeKind.NO_CALLBACK),
                Arguments.of("NO_PROGRESS", result(SliceRunKind.ERROR, false, true, null, null, null, 0), 0,
                        SliceOutcomeKind.NO_PROGRESS));
    }

    @Test
    @DisplayName("PROGRESS 必须在 NO_CALLBACK 与 NO_PROGRESS 前判定")
    void progress优先于无回调与无进展() {
        SliceRunResult noCallbackAndTimedOut = result(SliceRunKind.ERROR, true, true,
                null, null, "timeout", 0);
        assertEquals(SliceOutcomeKind.PROGRESS,
                SliceOutcomeClassifier.classify(noCallbackAndTimedOut, 1));
    }

    @Test
    @DisplayName("CLI budget 比 PROGRESS 优先")
    void cli预算比进展优先() {
        SliceRunResult budget = result(SliceRunKind.COMPLETED, true, false, null,
                "failed", "result:error_max_budget_usd", 3);
        assertEquals(SliceOutcomeKind.CLI_BUDGET, SliceOutcomeClassifier.classify(budget, 2));
    }

    @Test
    @DisplayName("dispatcher 内部 BUSY 与 DUPLICATE_RUN 不得漏到结局注册表")
    void 内部结局泄漏时failFast() {
        assertThrows(IllegalArgumentException.class,
                () -> SliceOutcomeClassifier.classify(result(SliceRunKind.BUSY, false, false,
                        503, null, null, 0), 0));
        assertThrows(IllegalArgumentException.class,
                () -> SliceOutcomeClassifier.classify(result(SliceRunKind.DUPLICATE_RUN, false, false,
                        409, null, null, 0), 0));
    }

    private static SliceRunResult result(SliceRunKind kind, boolean started, boolean timedOut,
                                         Integer httpStatus, String summaryStatus,
                                         String summaryError, int callbacks) {
        return new SliceRunResult(kind, started, timedOut, httpStatus, null, summaryStatus, summaryError,
                null, callbacks, Set.of());
    }
}

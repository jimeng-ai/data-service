package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeKind;

/** Ordered pure classifier from design section 6.9; call before settling DISPATCHED rows. */
public final class SliceOutcomeClassifier {

    private static final String CLI_BUDGET_ERROR = "result:error_max_budget_usd";

    private SliceOutcomeClassifier() {
    }

    public static SliceOutcomeKind classify(SliceRunResult result, int progress) {
        if (result == null || progress < 0) {
            throw new IllegalArgumentException("result is required and progress must not be negative");
        }
        return switch (result.sseKind()) {
            case ABORTED -> SliceOutcomeKind.ABORTED;
            case SHUTDOWN -> SliceOutcomeKind.SHUTDOWN;
            case MODEL_MISMATCH -> SliceOutcomeKind.MODEL_MISMATCH;
            case SANDBOX_REJECTED -> SliceOutcomeKind.SANDBOX_REJECTED;
            case SANDBOX_AUTH -> SliceOutcomeKind.SANDBOX_AUTH;
            case SANDBOX_UNAVAILABLE -> SliceOutcomeKind.SANDBOX_UNAVAILABLE;
            case BUSY_EXHAUSTED -> SliceOutcomeKind.BUSY_EXHAUSTED;
            case BUSY, DUPLICATE_RUN -> throw new IllegalArgumentException(
                    "dispatcher-internal run kind leaked: " + result.sseKind());
            case COMPLETED, ERROR -> classifyCompleted(result, progress);
        };
    }

    private static SliceOutcomeKind classifyCompleted(SliceRunResult result, int progress) {
        if (CLI_BUDGET_ERROR.equals(result.summaryError())) {
            return SliceOutcomeKind.CLI_BUDGET;
        }
        if (progress > 0) {
            return SliceOutcomeKind.PROGRESS;
        }
        if (result.started() && result.callbacks() == 0) {
            return SliceOutcomeKind.NO_CALLBACK;
        }
        return SliceOutcomeKind.NO_PROGRESS;
    }
}

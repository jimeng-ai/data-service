package com.jimeng.dataserver.ai.connector.generation.outcome;

import com.jimeng.dataserver.ai.connector.generation.GenerationReasonCode;

/** Pure O5-facing decision; no database writes happen in an O4 handler. */
public record SliceOutcomeDecision(
        SliceOutcomeAction action,
        GenerationReasonCode reasonCode,
        String note,
        int sliceFailStreak,
        int noCallbackStreak,
        int backoffSeconds
) {
    public SliceOutcomeDecision {
        if (action == null || sliceFailStreak < 0 || noCallbackStreak < 0 || backoffSeconds < 0) {
            throw new IllegalArgumentException("invalid outcome decision");
        }
    }
}

package com.jimeng.dataserver.ai.connector.generation.outcome;

import com.jimeng.dataserver.ai.connector.generation.SliceRunResult;

/** Immutable input to pure outcome decisions. */
public record SliceOutcomeContext(
        SliceOutcomeKind kind,
        SliceRunResult result,
        int progress,
        int doneTables,
        int sliceFailStreak,
        int noCallbackStreak,
        int maxRetries,
        int retryBackoffSeconds
) {
    public SliceOutcomeContext {
        if (kind == null || result == null) {
            throw new IllegalArgumentException("kind and result are required");
        }
        if (progress < 0 || doneTables < 0 || sliceFailStreak < 0 || noCallbackStreak < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (maxRetries < 1 || retryBackoffSeconds < 1) {
            throw new IllegalArgumentException("retry limits must be positive");
        }
    }
}

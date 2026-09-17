package com.jimeng.dataserver.ai.connector.generation.outcome;

/** Final, database-progress-aware slice outcomes from design section 6.9. */
public enum SliceOutcomeKind {
    ABORTED,
    SHUTDOWN,
    MODEL_MISMATCH,
    SANDBOX_REJECTED,
    SANDBOX_AUTH,
    SANDBOX_UNAVAILABLE,
    BUSY_EXHAUSTED,
    CLI_BUDGET,
    PROGRESS,
    NO_CALLBACK,
    NO_PROGRESS
}

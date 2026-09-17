package com.jimeng.dataserver.ai.connector.generation;

/** Sandbox/SSE transport result before database progress is composed into the final outcome. */
public enum SliceRunKind {
    COMPLETED,
    ERROR,
    BUSY,
    DUPLICATE_RUN,
    ABORTED,
    SHUTDOWN,
    MODEL_MISMATCH,
    SANDBOX_REJECTED,
    SANDBOX_AUTH,
    SANDBOX_UNAVAILABLE,
    BUSY_EXHAUSTED
}

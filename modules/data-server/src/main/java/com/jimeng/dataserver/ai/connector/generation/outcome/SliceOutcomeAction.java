package com.jimeng.dataserver.ai.connector.generation.outcome;

/** O5-facing action; persistence remains owned by the orchestrator transaction. */
public enum SliceOutcomeAction {
    ABORT,
    CONTINUE,
    RETRY,
    INTERRUPT,
    FAIL,
    FALLBACK
}

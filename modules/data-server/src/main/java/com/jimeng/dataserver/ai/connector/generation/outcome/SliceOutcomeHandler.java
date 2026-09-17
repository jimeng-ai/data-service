package com.jimeng.dataserver.ai.connector.generation.outcome;

/** One pure strategy per final outcome. */
public interface SliceOutcomeHandler {
    SliceOutcomeKind kind();

    SliceOutcomeDecision handle(SliceOutcomeContext context);
}

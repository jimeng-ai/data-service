package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One slice's transport-layer facts. Database progress is deliberately absent and must be read before settlement.
 */
public record SliceRunResult(
        SliceRunKind sseKind,
        boolean started,
        boolean timedOut,
        Integer httpStatus,
        Integer retryAfter,
        String summaryStatus,
        String summaryError,
        NormalizedUsage usage,
        int callbacks,
        Set<String> modelsSeen
) {
    public SliceRunResult {
        modelsSeen = modelsSeen == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(modelsSeen));
    }

    public SliceRunResult as(SliceRunKind kind) {
        return new SliceRunResult(kind, started, timedOut, httpStatus, retryAfter, summaryStatus,
                summaryError, usage, callbacks, modelsSeen);
    }

    public SliceRunResult withCallbacks(int count) {
        return new SliceRunResult(sseKind, started, timedOut, httpStatus, retryAfter, summaryStatus,
                summaryError, usage, count, modelsSeen);
    }

    public SliceRunResult asTimedOut() {
        return new SliceRunResult(SliceRunKind.ERROR, started, true, httpStatus, retryAfter, summaryStatus,
                summaryError, usage, callbacks, modelsSeen);
    }

    public static SliceRunResult aborted() {
        return empty(SliceRunKind.ABORTED);
    }

    public static SliceRunResult shutdown() {
        return empty(SliceRunKind.SHUTDOWN);
    }

    private static SliceRunResult empty(SliceRunKind kind) {
        return new SliceRunResult(kind, false, false, null, null, null, null, null, 0, Set.of());
    }
}

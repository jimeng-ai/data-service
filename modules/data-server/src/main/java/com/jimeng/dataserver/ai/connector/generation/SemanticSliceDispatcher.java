package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import okhttp3.sse.EventSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns every attempt, retry, wait and cancellation inside one semantic slice. */
@Component
public class SemanticSliceDispatcher {

    static final String PROMPT = "开始本片工作。先调用 get_run_scope 读取本片范围。";
    private static final long CHECKPOINT_MILLIS = 30_000L;

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final AdminAuthService authService;
    private final SidecarClient sidecarClient;
    private final SemanticGenerationHeartbeat heartbeat;
    private final SemanticSliceUsageRecorder usageRecorder;
    private final DispatchRuntime runtime;

    @Autowired
    public SemanticSliceDispatcher(ConnectorSemanticGenerationMapper generationMapper,
                                   AdminAuthService authService,
                                   SidecarClient sidecarClient,
                                   SemanticGenerationHeartbeat heartbeat,
                                   SemanticSliceUsageRecorder usageRecorder) {
        this(generationMapper, authService, sidecarClient, heartbeat, usageRecorder, new SystemDispatchRuntime());
    }

    SemanticSliceDispatcher(ConnectorSemanticGenerationMapper generationMapper,
                            AdminAuthService authService,
                            SidecarClient sidecarClient,
                            SemanticGenerationHeartbeat heartbeat,
                            SemanticSliceUsageRecorder usageRecorder,
                            DispatchRuntime runtime) {
        this.generationMapper = generationMapper;
        this.authService = authService;
        this.sidecarClient = sidecarClient;
        this.heartbeat = heartbeat;
        this.usageRecorder = usageRecorder;
        this.runtime = runtime;
    }

    public SliceRunResult runSlice(ConnectorSemanticGeneration generation,
                                   int sliceNo,
                                   SemanticSlice slice,
                                   SemanticDispatchLimits limits) {
        requireArguments(generation, sliceNo, slice, limits);
        if (heartbeat.lost()) {
            return SliceRunResult.aborted();
        }
        int attempt = 0;
        int busyRetries = 0;
        int duplicateRuns = 0;
        while (true) {
            attempt++;
            String runId = "semgen-" + generation.getId() + "-s" + sliceNo + "-a" + attempt;
            if (!beginAttempt(generation, runId, limits.llmModel())) {
                return SliceRunResult.aborted();
            }
            long startedAt = runtime.nowMillis();
            SliceRunResult result;
            try {
                result = dispatchAttempt(generation, sliceNo, runId, limits);
                CallbackSnapshot callbacks = callbacks(generation, runId);
                if (!callbacks.owned()) {
                    result = SliceRunResult.aborted();
                } else {
                    result = result.withCallbacks(callbacks.count());
                }
            } finally {
                clearRunId(generation, runId);
            }
            long latencyMs = Math.max(0L, runtime.nowMillis() - startedAt);

            if (result.sseKind() == SliceRunKind.BUSY) {
                busyRetries++;
                if (busyRetries > limits.busyMaxRetries()) {
                    return result.as(SliceRunKind.BUSY_EXHAUSTED);
                }
                SliceRunResult stop = sleepWithHeartbeat(backoffMillis(limits.retryBackoffSeconds(), busyRetries));
                if (stop != null) {
                    return stop;
                }
                continue;
            }
            if (result.sseKind() == SliceRunKind.DUPLICATE_RUN) {
                duplicateRuns++;
                if (duplicateRuns == 1) {
                    continue;
                }
                busyRetries++;
                if (busyRetries > limits.busyMaxRetries()) {
                    return result.as(SliceRunKind.BUSY_EXHAUSTED);
                }
                SliceRunResult stop = sleepWithHeartbeat(backoffMillis(limits.retryBackoffSeconds(), busyRetries));
                if (stop != null) {
                    return stop;
                }
                continue;
            }
            if (result.sseKind() == SliceRunKind.ABORTED || result.sseKind() == SliceRunKind.SHUTDOWN) {
                return result;
            }
            if (shouldRecordUsage(result)
                    && !usageRecorder.record(generation, sliceNo, runId, limits.llmModel(), result, latencyMs)) {
                return SliceRunResult.aborted();
            }
            return result;
        }
    }

    private SliceRunResult dispatchAttempt(ConnectorSemanticGeneration generation,
                                           int sliceNo,
                                           String runId,
                                           SemanticDispatchLimits limits) {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder(limits.llmModel());
        EventSource source;
        try {
            String token = authService.mintSemanticAgentToken(generation.getTriggeredBy(),
                    generation.getTenantId(), generation.getId(), generation.getConnectorId(), sliceNo, runId,
                    (limits.wallClockSeconds() + 120L) * 1000L);
            source = sidecarClient.run(payload(generation, sliceNo, runId, token, limits), recorder.listener(latch));
        } catch (RuntimeException failure) {
            SliceRunKind kind = causedByIOException(failure) ? SliceRunKind.BUSY : SliceRunKind.ERROR;
            return new SliceRunResult(kind, false, false, null, null, null, null,
                    null, 0, java.util.Set.of());
        }

        long deadline = safeAdd(runtime.nowMillis(), (limits.wallClockSeconds() + 30L) * 1000L);
        try {
            while (latch.getCount() > 0) {
                long remaining = deadline - runtime.nowMillis();
                if (remaining <= 0) {
                    source.cancel();
                    return recorder.timeout();
                }
                long waitMillis = Math.min(CHECKPOINT_MILLIS, Math.max(1L, remaining));
                if (runtime.await(latch, waitMillis)) {
                    break;
                }
                if (heartbeat.lost()) {
                    source.cancel();
                    return SliceRunResult.aborted();
                }
                if (runtime.nowMillis() >= deadline) {
                    source.cancel();
                    return recorder.timeout();
                }
            }
        } catch (InterruptedException interrupted) {
            source.cancel();
            Thread.currentThread().interrupt();
            return SliceRunResult.shutdown();
        } finally {
            if (latch.getCount() > 0) {
                source.cancel();
            }
        }
        if (recorder.modelMismatch()) {
            source.cancel();
        }
        return recorder.result();
    }

    private SidecarRunPayload payload(ConnectorSemanticGeneration generation,
                                      int sliceNo,
                                      String runId,
                                      String token,
                                      SemanticDispatchLimits limits) {
        SidecarRunPayload payload = new SidecarRunPayload();
        payload.setRunId(runId);
        payload.setTenantId(generation.getTenantId());
        payload.setUserId(String.valueOf(generation.getTriggeredBy()));
        payload.setPrompt(PROMPT);
        payload.setRunProfile("semantic-layer");

        SidecarRunPayload.SemanticContext semantic = new SidecarRunPayload.SemanticContext();
        semantic.setCallbackBaseUrl(limits.callbackBaseUrl());
        semantic.setAccessToken(token);
        semantic.setGenerationId(String.valueOf(generation.getId()));
        semantic.setConnectorId(String.valueOf(generation.getConnectorId()));
        semantic.setSliceNo(sliceNo);
        payload.setSemanticContext(semantic);

        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(limits.llmBaseUrl());
        llm.setAuthToken(limits.llmAuthToken());
        llm.setModel(limits.llmModel());
        llm.setAuthScheme(limits.llmAuthScheme());
        payload.setLlm(llm);

        SidecarRunPayload.Limits payloadLimits = new SidecarRunPayload.Limits();
        payloadLimits.setWallClockSec(limits.wallClockSeconds());
        payloadLimits.setMaxTurns(limits.maxTurns());
        payloadLimits.setMaxBudgetUsd(limits.maxBudgetUsd());
        payload.setLimits(payloadLimits);
        return payload;
    }

    private boolean beginAttempt(ConnectorSemanticGeneration generation, String runId, String model) {
        return generationMapper.update(null, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getCurrentRunId, runId)
                .set(ConnectorSemanticGeneration::getCurrentRunCallbacks, 0)
                .set(ConnectorSemanticGeneration::getModel, model)
                .eq(ConnectorSemanticGeneration::getId, generation.getId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                .eq(ConnectorSemanticGeneration::getStatus, "RUNNING")) > 0;
    }

    private CallbackSnapshot callbacks(ConnectorSemanticGeneration generation, String runId) {
        ConnectorSemanticGeneration live = generationMapper.selectById(generation.getId());
        boolean owned = live != null
                && generation.getOwnerToken() != null
                && generation.getOwnerToken().equals(live.getOwnerToken())
                && "RUNNING".equals(live.getStatus())
                && runId.equals(live.getCurrentRunId());
        int count = owned && live.getCurrentRunCallbacks() != null ? live.getCurrentRunCallbacks() : 0;
        return new CallbackSnapshot(owned, count);
    }

    private void clearRunId(ConnectorSemanticGeneration generation, String runId) {
        generationMapper.update(null, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                .eq(ConnectorSemanticGeneration::getId, generation.getId())
                .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                .eq(ConnectorSemanticGeneration::getStatus, "RUNNING")
                .eq(ConnectorSemanticGeneration::getCurrentRunId, runId));
    }

    private SliceRunResult sleepWithHeartbeat(long totalMillis) {
        long remaining = totalMillis;
        try {
            while (remaining > 0) {
                if (heartbeat.lost()) {
                    return SliceRunResult.aborted();
                }
                long chunk = Math.min(CHECKPOINT_MILLIS, remaining);
                runtime.sleep(chunk);
                remaining -= chunk;
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SliceRunResult.shutdown();
        }
    }

    private static long backoffMillis(int baseSeconds, int retry) {
        long multiplier = 1L << Math.min(30, Math.max(0, retry - 1));
        return Math.min(300_000L, baseSeconds * 1000L * multiplier);
    }

    private static boolean shouldRecordUsage(SliceRunResult result) {
        return result.started() || result.timedOut() || result.usage() != null;
    }

    private static boolean causedByIOException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void requireArguments(ConnectorSemanticGeneration generation,
                                         int sliceNo,
                                         SemanticSlice slice,
                                         SemanticDispatchLimits limits) {
        if (generation == null || generation.getId() == null || generation.getConnectorId() == null
                || generation.getTriggeredBy() == null || generation.getOwnerToken() == null
                || sliceNo < 1 || slice == null || slice.isEmpty() || limits == null) {
            throw new IllegalArgumentException("complete generation, positive sliceNo, non-empty slice and limits required");
        }
    }

    private record CallbackSnapshot(boolean owned, int count) {
    }

    /** Time/wait seam for deterministic cancellation and backoff tests. */
    public interface DispatchRuntime {
        long nowMillis();

        boolean await(CountDownLatch latch, long millis) throws InterruptedException;

        void sleep(long millis) throws InterruptedException;
    }

    private static final class SystemDispatchRuntime implements DispatchRuntime {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean await(CountDownLatch latch, long millis) throws InterruptedException {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    }
}

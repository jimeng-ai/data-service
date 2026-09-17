package com.jimeng.dataserver.ai.connector.generation;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe, bounded SSE recorder for one sandbox attempt.
 *
 * <p>It intentionally discards transcript-like events and exception messages. Only summary facts, model names,
 * transport status, Retry-After and tool error counts survive.
 */
public final class SliceRunRecorder {

    private final String configuredModel;
    private final UsageExtractor usageExtractor;
    private final Object monitor = new Object();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final Set<String> modelsSeen = new LinkedHashSet<>();

    private volatile CountDownLatch latch;
    private SliceRunKind kind;
    private boolean started;
    private boolean timedOut;
    private Integer httpStatus;
    private Integer retryAfter;
    private String summaryStatus;
    private String summaryError;
    private String summaryErrorMessage;
    private Integer toolRounds;
    private NormalizedUsage usage;
    private int toolErrorCount;
    private String transportErrorType;

    public SliceRunRecorder(String configuredModel) {
        this(configuredModel, new UsageExtractor());
    }

    SliceRunRecorder(String configuredModel, UsageExtractor usageExtractor) {
        this.configuredModel = configuredModel;
        this.usageExtractor = usageExtractor;
    }

    public EventSourceListener listener(CountDownLatch completionLatch) {
        if (completionLatch == null) {
            throw new IllegalArgumentException("completionLatch must not be null");
        }
        synchronized (monitor) {
            if (latch != null) {
                throw new IllegalStateException("listener already created");
            }
            latch = completionLatch;
        }
        return new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                recordEvent(type, data);
            }

            @Override
            public void onClosed(EventSource eventSource) {
                finish(SliceRunKind.ERROR);
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable throwable, Response response) {
                recordFailure(throwable, response);
            }
        };
    }

    private void recordEvent(String type, String data) {
        if (completed.get()) {
            return;
        }
        synchronized (monitor) {
            if (completed.get()) {
                return;
            }
            started = true;
            try {
                if ("summary".equals(type)) {
                    JSONObject summary = parseObject(data);
                    summaryStatus = summary.getStr("status");
                    summaryError = summary.getStr("error");
                    summaryErrorMessage = bounded(summary.getStr("errorMessage"), 500);
                    toolRounds = summary.getInt("toolRounds", null);
                    JSONObject usageJson = summary.getJSONObject("usage");
                    usage = usageJson == null ? null : usageExtractor.extract(usageJson);
                    // finalText and every other transcript-like field are deliberately ignored.
                    finishLocked(SliceRunKind.COMPLETED);
                } else if ("error".equals(type)) {
                    parseObject(data); // Validate the envelope without retaining its message.
                    finishLocked(SliceRunKind.ERROR);
                } else if ("claude-delta".equals(type)) {
                    recordModel(parseObject(data));
                } else if ("tool_result".equals(type)) {
                    recordToolErrors(parseObject(data));
                }
            } catch (RuntimeException ignored) {
                if ("summary".equals(type) || "error".equals(type)) {
                    finishLocked(SliceRunKind.ERROR);
                }
            }
        }
    }

    private void recordModel(JSONObject event) {
        if (!"message_start".equals(event.getStr("type"))) {
            return;
        }
        JSONObject message = event.getJSONObject("message");
        String actualModel = message == null ? null : message.getStr("model");
        if (actualModel == null || actualModel.isBlank()) {
            return;
        }
        modelsSeen.add(safeModelName(actualModel));
        if (!actualModel.equals(configuredModel)) {
            finishLocked(SliceRunKind.MODEL_MISMATCH);
        }
    }

    private void recordToolErrors(JSONObject event) {
        JSONArray results = event.getJSONArray("results");
        if (results == null) {
            return;
        }
        for (Object item : results) {
            if (item instanceof JSONObject result && "error".equals(result.getStr("status"))) {
                toolErrorCount++;
            }
        }
    }

    private void recordFailure(Throwable throwable, Response response) {
        if (completed.get()) {
            return;
        }
        synchronized (monitor) {
            if (completed.get()) {
                return;
            }
            if (response != null) {
                httpStatus = response.code();
                retryAfter = positiveInt(response.header("Retry-After"));
            }
            if (throwable != null) {
                transportErrorType = throwable.getClass().getSimpleName();
            }
            finishLocked(classifyFailure(throwable));
        }
    }

    private SliceRunKind classifyFailure(Throwable throwable) {
        if (started) {
            return SliceRunKind.ERROR;
        }
        if (Integer.valueOf(409).equals(httpStatus)) {
            return SliceRunKind.DUPLICATE_RUN;
        }
        if (Integer.valueOf(400).equals(httpStatus)) {
            return SliceRunKind.SANDBOX_REJECTED;
        }
        if (Integer.valueOf(401).equals(httpStatus) || Integer.valueOf(403).equals(httpStatus)) {
            return SliceRunKind.SANDBOX_AUTH;
        }
        if (Integer.valueOf(503).equals(httpStatus)) {
            return retryAfter == null ? SliceRunKind.SANDBOX_UNAVAILABLE : SliceRunKind.BUSY;
        }
        if (throwable instanceof IOException) {
            return SliceRunKind.BUSY;
        }
        return SliceRunKind.ERROR;
    }

    public SliceRunResult timeout() {
        synchronized (monitor) {
            timedOut = true;
            if (!completed.get()) {
                finishLocked(SliceRunKind.ERROR);
            }
            return snapshot();
        }
    }

    public SliceRunResult result() {
        synchronized (monitor) {
            return snapshot();
        }
    }

    public boolean modelMismatch() {
        synchronized (monitor) {
            return kind == SliceRunKind.MODEL_MISMATCH;
        }
    }

    public int toolErrorCount() {
        synchronized (monitor) {
            return toolErrorCount;
        }
    }

    public String transportErrorType() {
        synchronized (monitor) {
            return transportErrorType;
        }
    }

    public String summaryErrorMessage() {
        synchronized (monitor) {
            return summaryErrorMessage;
        }
    }

    public Integer toolRounds() {
        synchronized (monitor) {
            return toolRounds;
        }
    }

    private SliceRunResult snapshot() {
        SliceRunKind snapshotKind = kind == null ? SliceRunKind.ERROR : kind;
        return new SliceRunResult(snapshotKind, started, timedOut, httpStatus, retryAfter, summaryStatus,
                summaryError, usage, 0, modelsSeen);
    }

    private void finish(SliceRunKind resultKind) {
        synchronized (monitor) {
            finishLocked(resultKind);
        }
    }

    private void finishLocked(SliceRunKind resultKind) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        kind = resultKind;
        CountDownLatch completionLatch = latch;
        if (completionLatch != null) {
            completionLatch.countDown();
        }
    }

    private static JSONObject parseObject(String data) {
        return JSONUtil.parseObj(data == null ? "{}" : data);
    }

    private static Integer positiveInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String safeModelName(String value) {
        StringBuilder safe = new StringBuilder(Math.min(120, value.length()));
        for (int i = 0; i < value.length() && safe.length() < 120; i++) {
            char c = value.charAt(i);
            safe.append(c == ',' || Character.isISOControl(c) ? '_' : c);
        }
        return safe.toString();
    }
}

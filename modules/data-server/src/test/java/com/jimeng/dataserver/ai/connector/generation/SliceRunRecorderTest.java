package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SliceRunRecorderTest {

    private final EventSource source = mock(EventSource.class);

    @Test
    @DisplayName("summary 只保留结局与归一化 usage，不保留 finalText")
    void summary只保留安全字段() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        listener.onEvent(source, null, "summary", """
                {"status":"failed","error":"result:error_max_turns","errorMessage":"轮次用尽",
                 "finalText":"customer secret transcript",
                 "toolRounds":12,
                 "usage":{"input_tokens":11,"output_tokens":7,
                          "cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
                """);

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        SliceRunResult result = recorder.result();
        assertEquals(SliceRunKind.COMPLETED, result.sseKind());
        assertTrue(result.started());
        assertEquals("failed", result.summaryStatus());
        assertEquals("result:error_max_turns", result.summaryError());
        NormalizedUsage usage = result.usage();
        assertEquals(11, usage.getInputTokens());
        assertEquals(7, usage.getOutputTokens());
        assertEquals(3, usage.getCacheReadTokens());
        assertEquals(2, usage.getCacheWriteTokens());
        assertFalse(result.toString().contains("customer secret transcript"), result.toString());
    }

    @Test
    @DisplayName("message_start 记录实际模型，模型不符立即结束，迟到事件丢弃")
    void 模型不符立即结束且迟到事件无效() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        listener.onEvent(source, null, "claude-delta",
                "{\"type\":\"message_start\",\"message\":{\"model\":\"deepseek-v4-pro\"}}");
        listener.onEvent(source, null, "summary",
                "{\"status\":\"success\",\"usage\":{\"input_tokens\":999}}");

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        SliceRunResult result = recorder.result();
        assertEquals(SliceRunKind.MODEL_MISMATCH, result.sseKind());
        assertEquals(java.util.Set.of("deepseek-v4-pro"), result.modelsSeen());
        assertNull(result.summaryStatus());
        assertNull(result.usage());
    }

    @Test
    @DisplayName("同模型去重、tool_result 只累计 error 数")
    void 模型去重与工具错误计数() {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        String start = "{\"type\":\"message_start\",\"message\":{\"model\":\"deepseek-flash\"}}";
        listener.onEvent(source, null, "claude-delta", start);
        listener.onEvent(source, null, "claude-delta", start);
        listener.onEvent(source, null, "tool_result", """
                {"results":[{"status":"error"},{"status":"success"},{"status":"error"}]}
                """);
        listener.onClosed(source);

        assertEquals(java.util.Set.of("deepseek-flash"), recorder.result().modelsSeen());
        assertEquals(2, recorder.toolErrorCount());
        assertEquals(SliceRunKind.ERROR, recorder.result().sseKind());
    }

    @Test
    @DisplayName("503 带 Retry-After 且未开始判 BUSY")
    void 未开始503带retryAfter判busy() {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        listener.onFailure(source, null, response(503, "17"));

        SliceRunResult result = recorder.result();
        assertEquals(SliceRunKind.BUSY, result.sseKind());
        assertEquals(503, result.httpStatus());
        assertEquals(17, result.retryAfter());
        assertFalse(result.started());
    }

    @Test
    @DisplayName("准入后首个事件前 IOException 判 BUSY")
    void 首事件前io判busy() {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        listener.onFailure(source, new IOException("connection refused"), null);

        assertEquals(SliceRunKind.BUSY, recorder.result().sseKind());
        assertEquals("IOException", recorder.transportErrorType());
        assertFalse(recorder.result().toString().contains("connection refused"));
    }

    @Test
    @DisplayName("HTTP 409、400、401、403、503 无 Retry-After 映射正确")
    void http分类() {
        assertEquals(SliceRunKind.DUPLICATE_RUN, failHttp(409, null).sseKind());
        assertEquals(SliceRunKind.SANDBOX_REJECTED, failHttp(400, null).sseKind());
        assertEquals(SliceRunKind.SANDBOX_AUTH, failHttp(401, null).sseKind());
        assertEquals(SliceRunKind.SANDBOX_AUTH, failHttp(403, null).sseKind());
        assertEquals(SliceRunKind.SANDBOX_UNAVAILABLE, failHttp(503, null).sseKind());
    }

    @Test
    @DisplayName("summary、onClosed、onFailure 并发终止只由第一次生效")
    void 终止只生效一次() {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        EventSourceListener listener = recorder.listener(latch);

        listener.onEvent(source, null, "summary", "{\"status\":\"success\"}");
        listener.onFailure(source, new IOException("late secret"), null);
        listener.onClosed(source);

        assertEquals(0L, latch.getCount());
        assertEquals(SliceRunKind.COMPLETED, recorder.result().sseKind());
        assertEquals("success", recorder.result().summaryStatus());
        assertNull(recorder.transportErrorType());
    }

    private SliceRunResult failHttp(int code, String retryAfter) {
        CountDownLatch latch = new CountDownLatch(1);
        SliceRunRecorder recorder = new SliceRunRecorder("deepseek-flash");
        recorder.listener(latch).onFailure(source, null, response(code, retryAfter));
        return recorder.result();
    }

    private static Response response(int code, String retryAfter) {
        Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url("http://localhost/sandbox/run").build())
                .protocol(Protocol.HTTP_1_1)
                .message("test")
                .code(code);
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter);
        }
        return builder.build();
    }
}

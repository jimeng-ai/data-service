package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticSliceDispatcherTest {

    private ConnectorSemanticGenerationMapper generationMapper;
    private AdminAuthService authService;
    private SidecarClient sidecarClient;
    private SemanticGenerationHeartbeat heartbeat;
    private SemanticSliceUsageRecorder usageRecorder;
    private FakeRuntime runtime;
    private SemanticSliceDispatcher dispatcher;
    private ConnectorSemanticGeneration generation;
    private ConnectorSemanticGeneration live;
    private EventSource eventSource;
    private SemanticSlice slice;
    private SemanticDispatchLimits limits;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGeneration.class);
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        authService = mock(AdminAuthService.class);
        sidecarClient = mock(SidecarClient.class);
        heartbeat = mock(SemanticGenerationHeartbeat.class);
        usageRecorder = mock(SemanticSliceUsageRecorder.class);
        runtime = new FakeRuntime();
        dispatcher = new SemanticSliceDispatcher(generationMapper, authService, sidecarClient,
                heartbeat, usageRecorder, runtime);
        generation = generation();
        live = generation();
        eventSource = mock(EventSource.class);
        slice = new SemanticSlice(List.of(table("orders"), table("users")), 1200, 4);
        limits = limits(1, 15);

        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectById(10L)).thenReturn(live);
        when(authService.mintSemanticAgentToken(anyLong(), any(), anyLong(), anyLong(), anyInt(), any(), anyLong()))
                .thenReturn("narrow-callback-token");
        when(usageRecorder.record(any(), anyInt(), any(), any(), any(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("每 attempt 以 owner/status CAS 写 runId，铸窄 token 并下发固定 payload")
    @SuppressWarnings("unchecked")
    void casToken与payload契约() {
        answerSummary("success", null);

        SliceRunResult result = dispatcher.runSlice(generation, 3, slice, limits);

        assertEquals(SliceRunKind.COMPLETED, result.sseKind());
        ArgumentCaptor<SidecarRunPayload> payload = ArgumentCaptor.forClass(SidecarRunPayload.class);
        verify(sidecarClient).run(payload.capture(), any());
        SidecarRunPayload sent = payload.getValue();
        assertEquals("semgen-10-s3-a1", sent.getRunId());
        assertEquals("tenant-a", sent.getTenantId());
        assertEquals("99", sent.getUserId());
        assertEquals("开始本片工作。先调用 get_run_scope 读取本片范围。", sent.getPrompt());
        assertEquals("semantic-layer", sent.getRunProfile());
        assertEquals("https://gateway/data", sent.getSemanticContext().getCallbackBaseUrl());
        assertEquals("narrow-callback-token", sent.getSemanticContext().getAccessToken());
        assertEquals("10", sent.getSemanticContext().getGenerationId());
        assertEquals("20", sent.getSemanticContext().getConnectorId());
        assertEquals(3, sent.getSemanticContext().getSliceNo());
        assertEquals(120, sent.getLimits().getWallClockSec());
        assertEquals(26, sent.getLimits().getMaxTurns());
        assertEquals(50.0, sent.getLimits().getMaxBudgetUsd());
        assertFalse(sent.toString().contains("deepseek-secret"), sent.toString());
        assertFalse(sent.toString().contains("narrow-callback-token"), sent.toString());
        verify(authService).mintSemanticAgentToken(99L, "tenant-a", 10L, 20L,
                3, "semgen-10-s3-a1", 240_000L);

        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> wrappers =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(generationMapper, times(2)).update(eq(null), wrappers.capture());
        LambdaUpdateWrapper<ConnectorSemanticGeneration> begin = wrappers.getAllValues().get(0);
        begin.getSqlSegment();
        assertTrue(begin.getParamNameValuePairs().containsValue("owner-a"), begin.getParamNameValuePairs().toString());
        assertTrue(begin.getParamNameValuePairs().containsValue("RUNNING"), begin.getParamNameValuePairs().toString());
        assertTrue(begin.getParamNameValuePairs().containsValue("semgen-10-s3-a1"), begin.getParamNameValuePairs().toString());
        assertTrue(begin.getSqlSet().contains("current_run_callbacks"), begin.getSqlSet());
        assertTrue(wrappers.getAllValues().get(1).getSqlSet().contains("current_run_id"),
                wrappers.getAllValues().get(1).getSqlSet());
        verify(usageRecorder).record(eq(generation), eq(3), eq("semgen-10-s3-a1"),
                eq("deepseek-flash"), any(), anyLong());
    }

    @Test
    @DisplayName("503 带 Retry-After 同片换 attempt，指数退避后重派")
    void busy重派() {
        final int[] calls = {0};
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), calls[0] == 0 ? 0 : 2);
            EventSourceListener listener = invocation.getArgument(1);
            if (calls[0]++ == 0) {
                listener.onFailure(eventSource, null, response(503, "2"));
            } else {
                listener.onEvent(eventSource, null, "summary", "{\"status\":\"success\"}");
            }
            return eventSource;
        });

        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);

        assertEquals(SliceRunKind.COMPLETED, result.sseKind());
        assertEquals(2, result.callbacks());
        assertEquals(List.of(15_000L), runtime.sleeps);
        ArgumentCaptor<SidecarRunPayload> payloads = ArgumentCaptor.forClass(SidecarRunPayload.class);
        verify(sidecarClient, times(2)).run(payloads.capture(), any());
        assertEquals(List.of("semgen-10-s1-a1", "semgen-10-s1-a2"),
                payloads.getAllValues().stream().map(SidecarRunPayload::getRunId).toList());
    }

    @Test
    @DisplayName("繁忙次数用尽返回 BUSY_EXHAUSTED，准入失败不计 missing usage")
    void busy耗尽() {
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), 0);
            EventSourceListener listener = invocation.getArgument(1);
            listener.onFailure(eventSource, new IOException("refused secret"), null);
            return eventSource;
        });

        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);

        assertEquals(SliceRunKind.BUSY_EXHAUSTED, result.sseKind());
        verify(sidecarClient, times(2)).run(any(), any());
        assertEquals(List.of(15_000L), runtime.sleeps);
        verify(usageRecorder, never()).record(any(), anyInt(), any(), any(), any(), anyLong());
        assertFalse(result.toString().contains("refused secret"), result.toString());
    }

    @Test
    @DisplayName("409 只立即重派一次，第二次按 BUSY 退避")
    void duplicate只立即重派一次() {
        final int[] calls = {0};
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), 0);
            EventSourceListener listener = invocation.getArgument(1);
            if (calls[0]++ < 2) {
                listener.onFailure(eventSource, null, response(409, null));
            } else {
                listener.onEvent(eventSource, null, "summary", "{\"status\":\"success\"}");
            }
            return eventSource;
        });

        assertEquals(SliceRunKind.COMPLETED, dispatcher.runSlice(generation, 1, slice, limits).sseKind());
        verify(sidecarClient, times(3)).run(any(), any());
        assertEquals(List.of(15_000L), runtime.sleeps);
    }

    @Test
    @DisplayName("503 无 Retry-After、400、401 都不重试")
    void 非繁忙http不重试() {
        assertEquals(SliceRunKind.SANDBOX_UNAVAILABLE, runHttp(503).sseKind());
        resetSidecar();
        assertEquals(SliceRunKind.SANDBOX_REJECTED, runHttp(400).sseKind());
        resetSidecar();
        assertEquals(SliceRunKind.SANDBOX_AUTH, runHttp(401).sseKind());
    }

    @Test
    @DisplayName("墙钟加 30 秒到期主动 cancel，等待检查间隔不超过 30 秒")
    void timeout主动取消() {
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            setLiveRun(((SidecarRunPayload) invocation.getArgument(0)).getRunId(), 0);
            return eventSource;
        });

        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);

        assertTrue(result.timedOut());
        assertEquals(SliceRunKind.ERROR, result.sseKind());
        verify(eventSource, atLeastOnce()).cancel();
        assertEquals(5, runtime.waits.size());
        assertTrue(runtime.waits.stream().allMatch(ms -> ms <= 30_000L), runtime.waits.toString());
        verify(usageRecorder).record(eq(generation), eq(1), eq("semgen-10-s1-a1"),
                eq("deepseek-flash"), any(), anyLong());
    }

    @Test
    @DisplayName("message_start 模型不符立即 cancel，迟到 summary 不覆盖")
    void modelMismatch取消() {
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), 0);
            EventSourceListener listener = invocation.getArgument(1);
            listener.onEvent(eventSource, null, "claude-delta",
                    "{\"type\":\"message_start\",\"message\":{\"model\":\"deepseek-v4-pro\"}}");
            listener.onEvent(eventSource, null, "summary", "{\"status\":\"success\"}");
            return eventSource;
        });

        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);

        assertEquals(SliceRunKind.MODEL_MISMATCH, result.sseKind());
        verify(eventSource).cancel();
        assertEquals(java.util.Set.of("deepseek-v4-pro"), result.modelsSeen());
        assertEquals(null, result.summaryStatus());
    }

    @Test
    @DisplayName("等待期间 heartbeat lost 取消且返回 ABORTED")
    void heartbeatLost取消() {
        when(heartbeat.lost()).thenReturn(false, true);
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            setLiveRun(((SidecarRunPayload) invocation.getArgument(0)).getRunId(), 0);
            return eventSource;
        });

        assertEquals(SliceRunKind.ABORTED, dispatcher.runSlice(generation, 1, slice, limits).sseKind());
        verify(eventSource, atLeastOnce()).cancel();
        verify(usageRecorder, never()).record(any(), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("等待线程中断 cancel、恢复中断位并返回 SHUTDOWN")
    void interrupted取消() {
        runtime.interruptAwait = true;
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            setLiveRun(((SidecarRunPayload) invocation.getArgument(0)).getRunId(), 0);
            return eventSource;
        });

        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);

        assertEquals(SliceRunKind.SHUTDOWN, result.sseKind());
        assertTrue(Thread.currentThread().isInterrupted());
        verify(eventSource, atLeastOnce()).cancel();
    }

    @Test
    @DisplayName("开始 CAS 为 0 立即 ABORTED，不铸 token、不调用 sidecar")
    void beginCas失败() {
        when(generationMapper.update(any(), any())).thenReturn(0);

        assertEquals(SliceRunKind.ABORTED, dispatcher.runSlice(generation, 1, slice, limits).sseKind());
        verify(authService, never()).mintSemanticAgentToken(anyLong(), any(), anyLong(), anyLong(),
                anyInt(), any(), anyLong());
        verify(sidecarClient, never()).run(any(), any());
    }

    private void answerSummary(String status, String error) {
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), 1);
            EventSourceListener listener = invocation.getArgument(1);
            String errorJson = error == null ? "" : ",\"error\":\"" + error + "\"";
            listener.onEvent(eventSource, null, "summary",
                    "{\"status\":\"" + status + "\"" + errorJson + "}");
            return eventSource;
        });
    }

    private SliceRunResult runHttp(int code) {
        when(sidecarClient.run(any(), any())).thenAnswer(invocation -> {
            SidecarRunPayload payload = invocation.getArgument(0);
            setLiveRun(payload.getRunId(), 0);
            ((EventSourceListener) invocation.getArgument(1)).onFailure(eventSource, null, response(code, null));
            return eventSource;
        });
        SliceRunResult result = dispatcher.runSlice(generation, 1, slice, limits);
        verify(sidecarClient).run(any(), any());
        return result;
    }

    private void resetSidecar() {
        org.mockito.Mockito.reset(sidecarClient);
    }

    private void setLiveRun(String runId, int callbacks) {
        live.setCurrentRunId(runId);
        live.setCurrentRunCallbacks(callbacks);
    }

    private static ConnectorSemanticGeneration generation() {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(10L);
        generation.setTenantId("tenant-a");
        generation.setConnectorId(20L);
        generation.setTriggeredBy(99L);
        generation.setOwnerToken("owner-a");
        generation.setStatus("RUNNING");
        return generation;
    }

    private static ConnectorSemanticGenerationTable table(String name) {
        ConnectorSemanticGenerationTable table = new ConnectorSemanticGenerationTable();
        table.setObjectName(name);
        return table;
    }

    private static SemanticDispatchLimits limits(int busyRetries, int backoff) {
        ConnectorProperties.SemanticAgent properties = new ConnectorProperties.SemanticAgent();
        properties.setCallbackBaseUrl("https://gateway/data");
        properties.getLlm().setBaseUrl("https://llm.example/anthropic");
        properties.getLlm().setAuthToken("deepseek-secret");
        properties.getLlm().setModel("deepseek-flash");
        properties.getLlm().setAuthScheme("api-key");
        properties.setSliceWallClockSec(120);
        properties.setSliceMaxTurnsPerTable(3);
        properties.setSliceMaxBudgetUsd(50);
        properties.setBusyMaxRetries(busyRetries);
        properties.setRetryBackoffSec(backoff);
        return SemanticDispatchLimits.from(properties, 2);
    }

    private static Response response(int code, String retryAfter) {
        Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url("http://localhost/sandbox/run").build())
                .protocol(Protocol.HTTP_1_1).message("test").code(code);
        if (retryAfter != null) builder.header("Retry-After", retryAfter);
        return builder.build();
    }

    private static final class FakeRuntime implements SemanticSliceDispatcher.DispatchRuntime {
        private long now;
        private boolean interruptAwait;
        private final List<Long> waits = new ArrayList<>();
        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public boolean await(CountDownLatch latch, long millis) throws InterruptedException {
            if (interruptAwait) throw new InterruptedException("shutdown");
            waits.add(millis);
            if (latch.getCount() == 0) return true;
            now += millis;
            return latch.getCount() == 0;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }
}

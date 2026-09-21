package com.jimeng.dataserver.ai.conversation;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.TraceRecorder;
import com.jimeng.dataserver.ai.image.ImageGenClient;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.resilience.LlmCallGuard;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型流式调用的<b>重试</b>与 <b>provider failover</b>。
 *
 * <p>这两件事过去都不存在：上游一个 503 就让整轮报废，而失败在 {@code ai_model_call_log}
 * 里还被记成 {@code http_status=200, call_status=1}。本类钉住的正是那三条判断——
 * 什么该重试、什么绝不能重试、失败到底被记成了什么。
 */
class AiConversationLoopRetryTest {

    private RequestService requestService;
    private SkillRuntimeService skillRuntimeService;
    private AiModelCallRecordService recordService;
    private RunRegistry runRegistry;
    private LlmCallGuard llmCallGuard;
    private RunEventTee tee;
    private AiConversationLoop loop;
    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();

    @BeforeEach
    void setUp() {
        requestService = mock(RequestService.class);
        skillRuntimeService = mock(SkillRuntimeService.class);
        recordService = mock(AiModelCallRecordService.class);
        tee = mock(RunEventTee.class);
        runRegistry = mock(RunRegistry.class);
        llmCallGuard = mock(LlmCallGuard.class);
        loop = new AiConversationLoop(requestService, skillRuntimeService, recordService, tee,
                mock(RunFinalizer.class), runRegistry, llmCallGuard, mock(TraceRecorder.class),
                new com.jimeng.dataserver.ai.web.WebSearchProperties(), mock(ImageGenClient.class));
        ReflectionTestUtils.setField(loop, "maxToolRounds", 3);
        ReflectionTestUtils.setField(loop, "maxCallAttempts", 3);
        // 退避设 0：测试要验的是「重试了几次、打到了哪」，不是真的等 1s+2s。
        ReflectionTestUtils.setField(loop, "retryBackoffBaseMs", 0L);
        when(recordService.recordRequest(any(), any(), anyString(), anyString(), anyString())).thenReturn(1L);
        when(skillRuntimeService.applySkillContext(any(), any())).thenReturn(SkillApplyResult.disabled());
    }

    private Map<String, Object> body() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("messages", new ArrayList<>(List.of(Map.of("role", "user", "content", "hi"))));
        return b;
    }

    private AiConversationLoop.UpstreamTarget target(String name, String url, String model) {
        return new AiConversationLoop.UpstreamTarget(name, url, Map.of(), adapter,
                new AiConversationLoop.CallRecordConfig(name, url, model), model, null);
    }

    /** 让 postStream 的第 n 次调用回一个 HTTP 错误；成功则只 onClosed（空回答，够走完循环）。 */
    private void stubStream(int[] statusPerCall) {
        AtomicInteger n = new AtomicInteger();
        when(requestService.postStream(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            EventSourceListener l = inv.getArgument(3);
            EventSource es = mock(EventSource.class);
            int i = n.getAndIncrement();
            int status = i < statusPerCall.length ? statusPerCall[i] : 200;
            if (status == 200) {
                l.onClosed(es);
            } else {
                l.onFailure(es, null, httpResponse(status));
            }
            return es;
        });
    }

    /** okhttp3.Response 是 final，mock 不了，按真实 builder 造一个。 */
    private static Response httpResponse(int code) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://upstream/v1/messages").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 503 ? "Service is too busy" : "error")
                .body(ResponseBody.create("{\"error\":\"upstream\"}", MediaType.parse("application/json")))
                .build();
    }

    @Test
    @DisplayName("★ 503 会重试，第 2 次成功就不再打第 3 次")
    void 五百零三重试后成功() {
        stubStream(new int[]{503, 200});

        loop.runStream(body(), List.of(target("deepseek", "http://a", "deepseek-chat")), "conn-1", "t1");

        verify(requestService, times(2)).postStream(anyString(), any(), anyString(), any());
        // 失败那次必须带着真实状态码落库，而不是 200。
        verify(recordService).recordStreamResponse(eq(1L), eq(503), anyInt(), anyInt(),
                any(), any(), any(), eq("HTTP_503"), anyString());
        // 成功那次记 200 且无错误码。
        verify(recordService).recordStreamResponse(eq(1L), eq(200), anyInt(), anyInt(),
                any(), any(), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("★ 401 不重试——请求本身有问题，重试只会把一次失败变成三次")
    void 四零一不重试() {
        stubStream(new int[]{401});

        loop.runStream(body(), List.of(target("deepseek", "http://a", "deepseek-chat")), "conn-2", "t2");

        verify(requestService, times(1)).postStream(anyString(), any(), anyString(), any());
        verify(recordService).recordStreamResponse(eq(1L), eq(401), anyInt(), anyInt(),
                any(), any(), any(), eq("HTTP_401"), anyString());
    }

    @Test
    @DisplayName("★ 主 provider 重试用尽 → 切备用，且请求真的打到备用地址、带备用的模型名")
    void 主挂了切备用() {
        // 主家 3 次全 503，备用第 1 次就通。
        stubStream(new int[]{503, 503, 503, 200});
        Map<String, Object> b = body();

        loop.runStream(b, List.of(
                target("deepseek", "http://primary", "deepseek-chat"),
                target("volcengine", "http://fallback", "deepseek-v3-250324")), "conn-3", "t3");

        // 主家 3 次 + 备用 1 次
        verify(requestService, times(3)).postStream(eq("http://primary"), any(), anyString(), any());
        verify(requestService, times(1)).postStream(eq("http://fallback"), any(), anyString(), any());
        // 切家必须同时换模型名，否则换来的是 400 而不是一次成功的兜底。
        assertEquals("deepseek-v3-250324", b.get("model"));
    }

    @Test
    @DisplayName("★ 用户点了停止：既不重试也不切备用，更不报错")
    void 用户停止不重试也不切换() {
        RunHandle handle = mock(RunHandle.class);
        when(handle.isCancelled()).thenReturn(true);
        when(runRegistry.get("conn-4")).thenReturn(handle);
        stubStream(new int[]{503, 200});

        loop.runStream(body(), List.of(
                target("deepseek", "http://primary", "deepseek-chat"),
                target("volcengine", "http://fallback", "deepseek-v3")), "conn-4", "t4");

        verify(requestService, times(1)).postStream(anyString(), any(), anyString(), any());
        verify(requestService, never()).postStream(eq("http://fallback"), any(), anyString(), any());
        // 取消不该被当成 stream_failure 推给前端
        verify(tee, never()).teeJson(anyString(), eq("error"), any());
    }

    @Test
    @DisplayName("单目标旧签名仍然等价：不配备用就只打一家")
    void 旧签名单目标() {
        stubStream(new int[]{200});

        loop.runStream(body(), adapter, Map.of(), "http://only", "conn-5", "t5",
                new AiConversationLoop.CallRecordConfig("p", "http://only", "m"));

        verify(requestService, times(1)).postStream(eq("http://only"), any(), anyString(), any());
    }
}

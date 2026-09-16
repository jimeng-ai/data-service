package com.jimeng.dataserver.ai.conversation;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.TraceRecorder;
import com.jimeng.dataserver.ai.image.ImageGenClient;
import com.jimeng.dataserver.ai.protocol.AnthropicOverOpenAiAdapter;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.resilience.LlmCallGuard;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import com.jimeng.dataserver.ai.web.WebSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiConversationLoop#runInternal}：平台自己发起的单轮调用必须<b>不带任何工具</b>、只调一轮、超时按调用给。
 *
 * <p>这一组钉的是一次真实故障的两个根因：语义层推导走的是 runBlocking，请求体里被塞进了
 * activate_skills + 5 个内置工具和技能目录（客户的表名列名可能被模型拿去 web_search），
 * 而且只能用全局 180 秒读超时，41 张表的推导必然 SocketTimeoutException。
 *
 * <p>setUp 把内置工具的三个开关<b>全部打开</b>：单测里 {@code @Value} 不生效，skillInstallEnabled 默认就是 false，
 * 不打开的话「上游请求体里没有 tools」这条断言是空转的。最后一条用例用 runBlocking 反证这个配置确实会注入工具。
 */
class AiConversationLoopInternalTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(900);

    private static final String TOOL_USE_RESP =
            "{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":["
                    + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"web_search\",\"input\":{\"q\":\"orders cid\"}}],"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    private static final String FINAL_RESP =
            "{\"id\":\"msg_2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Done\"}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":8}}";

    private RequestService requestService;
    private SkillRuntimeService skillRuntimeService;
    private AiModelCallRecordService recordService;
    private LlmCallGuard llmCallGuard;
    private TraceRecorder traceRecorder;
    private AiConversationLoop loop;
    private final ClaudeProtocolAdapter claude = new ClaudeProtocolAdapter();

    @BeforeEach
    void setUp() {
        requestService = mock(RequestService.class);
        skillRuntimeService = mock(SkillRuntimeService.class);
        recordService = mock(AiModelCallRecordService.class);
        llmCallGuard = mock(LlmCallGuard.class);
        traceRecorder = mock(TraceRecorder.class);
        ImageGenClient imageGenClient = mock(ImageGenClient.class);
        when(imageGenClient.enabled()).thenReturn(true);
        WebSearchProperties web = new WebSearchProperties();
        web.setBaseUrl("https://search.example.com");
        web.setApiKey("k");

        loop = new AiConversationLoop(requestService, skillRuntimeService, recordService, mock(RunEventTee.class),
                mock(RunFinalizer.class), mock(RunRegistry.class), llmCallGuard, traceRecorder, web, imageGenClient);
        ReflectionTestUtils.setField(loop, "maxToolRounds", 3);
        ReflectionTestUtils.setField(loop, "skillInstallEnabled", true);
        when(recordService.recordRequest(any(), any(), anyString(), anyString(), anyString())).thenReturn(1L);
    }

    private AiConversationLoop.CallRecordConfig rc() {
        return new AiConversationLoop.CallRecordConfig("anthropic", "/v1/messages", "claude-x");
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-flash");
        body.put("max_tokens", 16000);
        body.put("system", "推导系统提示");
        List<Object> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "orders|id|bigint")));
        body.put("messages", messages);
        return body;
    }

    private void upstreamReturns(int status, String respBody) {
        when(requestService.post(any(), any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(status, respBody));
    }

    /** 真正发给上游的请求体（toUpstreamBody 之后）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> upstreamBody() {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(requestService).post(anyString(), any(), any(), cap.capture(), any());
        return cap.getValue();
    }

    @Test
    @DisplayName("★ 不套技能上下文、不注入内置工具：上游请求体里没有 tools / tool_choice，system 原样")
    void 不带任何工具() {
        upstreamReturns(200, FINAL_RESP);

        loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        Map<String, Object> sent = upstreamBody();
        assertFalse(sent.containsKey("tools"), "平台内部调用的请求体里出现了 tools：" + sent.get("tools"));
        assertFalse(sent.containsKey("tool_choice"), "平台内部调用的请求体里出现了 tool_choice");
        assertEquals("推导系统提示", sent.get("system"), "system 里被混进了别的东西（技能目录？）");
        verify(skillRuntimeService, never()).applySkillContext(any(), any());
        verify(skillRuntimeService, never()).executeToolCalls(any());
        verifyNoInteractions(skillRuntimeService);
    }

    @Test
    @DisplayName("超时原样交给 RequestService 的单次超时重载，不走 4 参（全局超时）版本")
    void 超时传给RequestService() {
        upstreamReturns(200, FINAL_RESP);

        loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        verify(requestService).post(eq("http://llm"), any(), any(), any(), eq(TIMEOUT));
        verify(requestService, never()).post(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 模型回了 tool_use 也不执行、不叫第二轮：tool_use 块原样返回给调用方")
    void tool_use不执行() {
        when(requestService.post(any(), any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(200, TOOL_USE_RESP),
                        new RequestService.HttpResp(200, FINAL_RESP));

        Object out = loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        verify(requestService, times(1)).post(any(), any(), any(), any(), any());
        verifyNoInteractions(skillRuntimeService);
        List<?> content = (List<?>) ((Map<?, ?>) out).get("content");
        assertEquals("tool_use", ((Map<?, ?>) content.get(0)).get("type"));
    }

    @Test
    @DisplayName("调用方自己放的 tools 原样发出：不替它加内置工具，也不替它补 tool_choice")
    void 调用方的tools不增不减() {
        Map<String, Object> body = body();
        List<Object> own = new ArrayList<>();
        own.add(new LinkedHashMap<>(Map.of("name", "emit_json", "input_schema", Map.of("type", "object"))));
        body.put("tools", own);
        upstreamReturns(200, FINAL_RESP);

        loop.runInternal(body, claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        Map<String, Object> sent = upstreamBody();
        List<?> tools = (List<?>) sent.get("tools");
        assertEquals(1, tools.size(), "内置工具被加进了调用方的 tools：" + tools);
        assertEquals("emit_json", ((Map<?, ?>) tools.get(0)).get("name"));
        assertFalse(sent.containsKey("tool_choice"));
    }

    @Test
    @DisplayName("熔断、调用记录、trace 与 runBlocking 单轮分支一致（调用日志与计费靠调用记录）")
    void 熔断与调用记录() {
        upstreamReturns(200, FINAL_RESP);

        loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        verify(llmCallGuard).acquirePermission();
        verify(llmCallGuard).recordSuccess();
        verify(llmCallGuard, never()).recordFailure();
        verify(recordService).recordRequest(any(), any(), eq("anthropic"), eq("/v1/messages"), eq("claude-x"));
        verify(recordService).recordResponse(eq(1L), eq(200), eq(FINAL_RESP), anyInt());
        verify(traceRecorder).recordUserMessage("orders|id|bigint");
        verify(traceRecorder).recordLlm(eq(1L), eq("推理·生成回答"), eq("deepseek-flash"),
                isNull(), isNull(), isNull(), anyLong(), eq(true), isNull());
    }

    @Test
    @DisplayName("上游连接级异常（读超时）：记熔断失败、记异常调用，异常照常抛给调用方")
    void 连接异常() {
        RuntimeException boom = new RuntimeException(new SocketTimeoutException("timeout"));
        when(requestService.post(any(), any(), any(), any(), any())).thenThrow(boom);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT));

        assertSame(boom, ex);
        assertInstanceOf(SocketTimeoutException.class, ex.getCause());
        verify(llmCallGuard).recordFailure();
        verify(recordService).recordException(eq(1L), same(boom), anyInt());
    }

    @Test
    @DisplayName("上游 5xx 不抛：记熔断失败、trace 记失败，错误体原样返回（与 runBlocking 一致）")
    void 上游5xx() {
        String err = "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}";
        upstreamReturns(503, err);

        Object out = loop.runInternal(body(), claude, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        verify(llmCallGuard).recordFailure();
        verify(traceRecorder).recordLlm(eq(1L), anyString(), any(), isNull(), isNull(), isNull(),
                anyLong(), eq(false), eq(err));
        assertTrue(out instanceof Map<?, ?> m && m.containsKey("error"), "错误体被吞了：" + out);
    }

    @Test
    @DisplayName("跨协议（deepseek-flash 走 openai 上游）：发出去是 openai 形状，回来是 anthropic 形状，思考过程不进 text")
    void 跨协议转换点照常() {
        AnthropicOverOpenAiAdapter cross = new AnthropicOverOpenAiAdapter(new ClaudeProtocolAdapter());
        String upstream = "{\"id\":\"x\",\"model\":\"deepseek-flash\",\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"objects\\\":[]}\","
                + "\"reasoning_content\":\"先看 orders 表……\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":50,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":40}}}";
        upstreamReturns(200, upstream);

        Object out = loop.runInternal(body(), cross, Map.of(), "http://llm", "trace-1", rc(), TIMEOUT);

        Map<String, Object> sent = upstreamBody();
        List<?> msgs = (List<?>) sent.get("messages");
        assertEquals("system", ((Map<?, ?>) msgs.get(0)).get("role"), "system 没有被搬进 openai 的 messages");
        assertFalse(sent.containsKey("tools"));
        Map<?, ?> resp = (Map<?, ?>) out;
        List<?> content = (List<?>) resp.get("content");
        assertEquals(1, content.size(), "content 里多出了块：" + content);
        assertEquals("{\"objects\":[]}", ((Map<?, ?>) content.get(0)).get("text"));
        assertEquals(50, ((Map<?, ?>) resp.get("usage")).get("output_tokens"));
    }

    @Test
    @DisplayName("反证：同样的开关下 runBlocking 确实会注入内置工具——上面「没有 tools」的断言不是空转")
    @SuppressWarnings("unchecked")
    void 反证runBlocking会注入() {
        when(skillRuntimeService.applySkillContext(any(), any())).thenReturn(SkillApplyResult.disabled());
        when(requestService.post(any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(200, FINAL_RESP));

        loop.runBlocking(body(), claude, Map.of(), "http://llm", "trace-1", rc());

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(requestService).post(anyString(), any(), any(), cap.capture());
        List<?> tools = (List<?>) cap.getValue().get("tools");
        assertNotNull(tools, "runBlocking 没注入工具——setUp 的开关没打开，本类其它断言都成了空转");
        assertEquals(5, tools.size(), "web_search / web_fetch / skill 搜索 / skill 安装 / generate_image：" + tools);
        assertTrue(cap.getValue().containsKey("tool_choice"));
    }
}

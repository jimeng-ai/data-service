package com.jimeng.dataserver.ai.conversation;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.TraceRecorder;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.resilience.LlmCallGuard;
import com.jimeng.dataserver.ai.support.SseEventBridge;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 3.2：用 mock 的 RequestService 给 AiConversationLoop.runBlocking 加 happy-path 与 maxToolRounds 超限用例。
 * adapter 用真实 ClaudeProtocolAdapter（纯函数），只 mock 有副作用的协作者。
 */
class AiConversationLoopTest {

    private RequestService requestService;
    private SkillRuntimeService skillRuntimeService;
    private AiModelCallRecordService recordService;
    private SseEventBridge sseBridge;
    private LlmCallGuard llmCallGuard;
    private TraceRecorder traceRecorder;
    private AiConversationLoop loop;
    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();

    private static final String TOOL_USE_RESP =
            "{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":["
                    + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"tool1\",\"input\":{\"q\":\"hi\"}}],"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    private static final String FINAL_RESP =
            "{\"id\":\"msg_2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Done\"}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":8}}";

    @BeforeEach
    void setUp() {
        requestService = mock(RequestService.class);
        skillRuntimeService = mock(SkillRuntimeService.class);
        recordService = mock(AiModelCallRecordService.class);
        sseBridge = mock(SseEventBridge.class);
        llmCallGuard = mock(LlmCallGuard.class);
        traceRecorder = mock(TraceRecorder.class);
        loop = new AiConversationLoop(requestService, skillRuntimeService, recordService, sseBridge, llmCallGuard, traceRecorder);
        ReflectionTestUtils.setField(loop, "maxToolRounds", 3);
        when(recordService.recordRequest(any(), any(), anyString(), anyString(), anyString())).thenReturn(1L);
    }

    private AiConversationLoop.CallRecordConfig rc() {
        return new AiConversationLoop.CallRecordConfig("anthropic", "/v1/messages", "claude-x");
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", new ArrayList<>(List.of(Map.of("role", "user", "content", "hi"))));
        return body;
    }

    @Test
    void runBlocking_happyPath_executesOneToolRoundThenAggregates() {
        when(skillRuntimeService.applySkillContext(any(), any()))
                .thenReturn(SkillApplyResult.activated(List.of("s1")));
        when(skillRuntimeService.executeToolCalls(any()))
                .thenReturn(List.of(new ToolExecutionResult("tu_1", "tool1", true, Map.of("ok", true))));
        when(requestService.post(any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(200, TOOL_USE_RESP),
                        new RequestService.HttpResp(200, FINAL_RESP));

        Object result = loop.runBlocking(body(), adapter, Map.of(), "http://llm", "trace-1", rc());

        // 两轮 LLM 调用：第一轮 tool_use，第二轮无工具 → 聚合返回
        verify(requestService, times(2)).post(any(), any(), any(), any());
        verify(skillRuntimeService, times(1)).executeToolCalls(any());

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.get("tool_rounds"));
        Map<?, ?> usage = (Map<?, ?>) map.get("usage");
        assertEquals(15, usage.get("input_tokens"));   // 10 + 5
        assertEquals(28, usage.get("output_tokens"));  // 20 + 8
        assertEquals(43, usage.get("total_tokens"));
    }

    @Test
    void runBlocking_skillsDisabled_returnsFirstResponseWithoutLooping() {
        when(skillRuntimeService.applySkillContext(any(), any()))
                .thenReturn(SkillApplyResult.disabled());
        when(requestService.post(any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(200, FINAL_RESP));

        loop.runBlocking(body(), adapter, Map.of(), "http://llm", "trace-2", rc());

        verify(requestService, times(1)).post(any(), any(), any(), any());
        verify(skillRuntimeService, org.mockito.Mockito.never()).executeToolCalls(any());
    }

    @Test
    void runBlocking_exceedingMaxToolRounds_throws() {
        ReflectionTestUtils.setField(loop, "maxToolRounds", 1);
        when(skillRuntimeService.applySkillContext(any(), any()))
                .thenReturn(SkillApplyResult.activated(List.of("s1")));
        when(skillRuntimeService.executeToolCalls(any()))
                .thenReturn(List.of(new ToolExecutionResult("tu_1", "tool1", true, Map.of("ok", true))));
        // 每轮都返回 tool_use，逼近上限
        when(requestService.post(any(), any(), any(), any()))
                .thenReturn(new RequestService.HttpResp(200, TOOL_USE_RESP));

        assertThrows(ServiceException.class,
                () -> loop.runBlocking(body(), adapter, Map.of(), "http://llm", "trace-3", rc()));

        // round0 执行一次工具后 toolRound=1；round1 命中 >=maxToolRounds(1) 抛出，故只执行一次
        verify(skillRuntimeService, times(1)).executeToolCalls(any());
        verify(recordService).recordException(any(), any(), anyInt());
    }
}

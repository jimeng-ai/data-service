package com.jimeng.dataserver.ai.claude.service;

import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.model.ModelResolver;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ClaudeService#messagesInternal}：平台内部调用不套 Agent 上下文，按 model 路由后走 chatInternal。
 */
class ClaudeServiceInternalTest {

    @Test
    @DisplayName("★ 不套 Agent 上下文，按 model 路由后走 chatInternal 并带上超时，不走 chat")
    void 不套Agent上下文() {
        ModelResolver resolver = mock(ModelResolver.class);
        AgentRuntimeService agents = mock(AgentRuntimeService.class);
        ChatClient client = mock(ChatClient.class);
        ClaudeService service = new ClaudeService(resolver, agents);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-flash");
        // 放一个 agent_id 只为反证：messages() 会拿它去 agentRuntimeService.byId 加载 Agent，messagesInternal 一次都不能碰。
        body.put("agent_id", 42L);
        List<Object> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "hi")));
        body.put("messages", messages);
        Duration timeout = Duration.ofSeconds(900);
        Object resp = Map.of("content", List.of());
        when(resolver.resolve(same(body), eq("anthropic"))).thenReturn(client);
        when(client.chatInternal(same(body), any(), eq(timeout))).thenReturn(resp);

        assertSame(resp, service.messagesInternal(body, timeout));

        verifyNoInteractions(agents);
        verify(client).chatInternal(same(body), isNull(), eq(timeout));
        verify(client, never()).chat(any(), any());
    }
}

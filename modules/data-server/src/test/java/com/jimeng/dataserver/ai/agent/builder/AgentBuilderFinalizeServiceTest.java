package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.FinalizeRequest;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.persistence.entity.Agent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentBuilderFinalizeServiceTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ChatConversationService convSvc = mock(ChatConversationService.class);
    private final AgentBuilderFinalizeService svc =
            new AgentBuilderFinalizeService(agentService, convSvc);

    private BuilderDraft draft() {
        BuilderDraft d = new BuilderDraft();
        d.setName("售后客服");
        d.setDescription("处理退货");
        d.setSystemPrompt("你是售后客服…");
        d.setModel("claude-sonnet-4-6");
        d.setPresetQuestions(List.of("怎么退货"));
        return d;
    }

    @Test
    void finalize_createsDraftAgent_writesKbConfig() {
        FinalizeRequest req = new FinalizeRequest();
        req.setDraft(draft());
        req.setKbIds(List.of(5L));
        req.setTopK(8);
        when(agentService.create(any(Agent.class))).thenAnswer(inv -> {
            Agent a = inv.getArgument(0);
            a.setId(999L);
            return a;
        });

        Long agentId = svc.finalize(100L, req);

        assertEquals(999L, agentId);
        ArgumentCaptor<Agent> cap = ArgumentCaptor.forClass(Agent.class);
        verify(agentService).create(cap.capture());
        Agent created = cap.getValue();
        assertEquals("售后客服", created.getName());
        assertEquals("DRAFT", created.getStatus());
        assertNotNull(created.getCode());                     // 生成了 code
        assertTrue(created.getKbConfig().contains("\"kbIds\""));
        assertTrue(created.getKbConfig().contains("8"));      // topK
    }

    @Test
    void finalize_missingNameOrPrompt_throws() {
        BuilderDraft d = new BuilderDraft();
        d.setName("有名无人设");
        FinalizeRequest req = new FinalizeRequest();
        req.setDraft(d);
        assertThrows(RuntimeException.class, () -> svc.finalize(100L, req));
        verify(agentService, never()).create(any());
    }

    @Test
    void finalize_nullDraft_fallsBackToConversationSnapshot() {
        when(convSvc.getBuilderDraft(100L)).thenReturn(
                "{\"name\":\"快照名\",\"systemPrompt\":\"人设\",\"model\":\"claude-sonnet-4-6\"}");
        when(agentService.create(any(Agent.class))).thenAnswer(inv -> {
            Agent a = inv.getArgument(0); a.setId(1L); return a;
        });
        FinalizeRequest req = new FinalizeRequest();   // draft 为空
        svc.finalize(100L, req);
        ArgumentCaptor<Agent> cap = ArgumentCaptor.forClass(Agent.class);
        verify(agentService).create(cap.capture());
        assertEquals("快照名", cap.getValue().getName());
    }
}

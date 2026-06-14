package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.model.ModelCatalogService;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftAgentToolExecutorTest {

    private final ChatConversationService convSvc = mock(ChatConversationService.class);
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final RunEventTee tee = mock(RunEventTee.class);
    private final DraftAgentToolExecutor exec =
            new DraftAgentToolExecutor(new BuilderDraftMerger(), convSvc, catalog, tee);

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void supports_onlyDraftAgent() {
        assertTrue(exec.supports("draft_agent"));
        assertFalse(exec.supports("rag_search"));
    }

    @Test
    void execute_mergesPersistsAndTees() {
        MDC.put(MdcAsyncSupport.MDC_CONNECTION_ID, "run-1");
        when(convSvc.conversationIdOfRun("run-1")).thenReturn(100L);
        when(convSvc.getBuilderDraft(100L)).thenReturn(null);
        when(catalog.isValidModel("claude-sonnet-4-6")).thenReturn(true);
        when(catalog.maxTempOf("claude-sonnet-4-6")).thenReturn(1.0);

        Object out = exec.execute("draft_agent", Map.of(
                "name", "客服助手",
                "model", "claude-sonnet-4-6"));

        // 落库
        verify(convSvc).saveBuilderDraft(eq(100L), contains("客服助手"));
        // 推 SSE
        verify(tee).teeJson(eq("run-1"), eq("draft-update"), any());
        // ack 含更新字段
        assertTrue(out.toString().contains("name"));
    }

    @Test
    void execute_invalidModel_throws() {
        MDC.put(MdcAsyncSupport.MDC_CONNECTION_ID, "run-1");
        when(convSvc.conversationIdOfRun("run-1")).thenReturn(100L);
        when(convSvc.getBuilderDraft(100L)).thenReturn(null);
        when(catalog.isValidModel("gpt-9")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> exec.execute("draft_agent", Map.of("model", "gpt-9")));
        assertTrue(ex.getMessage().contains("可选"));
        verify(convSvc, never()).saveBuilderDraft(anyLong(), anyString());
    }
}

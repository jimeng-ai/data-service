package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.TurnRequest;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService.TurnMessageIds;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.run.ChatHistoryReconstructor;
import com.jimeng.dataserver.ai.run.ConversationRunLock;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.ai.skill.LogCapture;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 构建器一轮跑完之后的收尾（{@code dispatch} 的 finally）：执行器桩成同步执行，边车版本不够让派发前就失败，直接走到同步草稿那一步。
 */
class SkillBuilderRunServiceTest {

    private static final String FAKE = "sk-live-FAKE-0123456789";

    @Test
    @DisplayName("★ 第 4 轮（续）B：一轮结束后同步草稿失败——WARN 只记异常类名，不记异常的 message（读工作区出错时它可能带着文件名，文件名里可能有值）")
    void aFailedDraftSyncIsLoggedWithoutItsMessage() throws Exception {
        SkillBuilderSessionService sessions = mock(SkillBuilderSessionService.class);
        SkillBuilderSession s = new SkillBuilderSession();
        s.setId(1L);
        s.setConversationId(9L);
        s.setOwnerUserId(7L);
        when(sessions.requireOwned(1L)).thenReturn(s);
        when(sessions.requireActive(s)).thenReturn(s);
        when(sessions.load(1L)).thenReturn(s);
        when(sessions.syncAfterRun(eq(1L), any())).thenThrow(new IllegalStateException("workspace broke near scripts/" + FAKE + ".py"));
        ChatConversationService conversations = mock(ChatConversationService.class);
        when(conversations.insertTurnMessages(eq(9L), anyString(), any(), anyString())).thenReturn(new TurnMessageIds(101L, 102L));
        ConversationRunLock lock = mock(ConversationRunLock.class);
        when(lock.tryAcquire(eq(9L), anyString())).thenReturn(true);
        SidecarClient sidecar = mock(SidecarClient.class);
        when(sidecar.skillBuilderVersion(any())).thenReturn(0);   // 版本不够：派发前就失败，直接走到 finally 里同步草稿
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
        RunFinalizer finalizer = mock(RunFinalizer.class);
        SkillBuilderRunService svc = new SkillBuilderRunService(sessions, mock(SkillTriggerContextFactory.class), new SkillBuilderProperties(),
                new AgentSandboxProperties(), sidecar, mock(RagMinioStorageService.class), conversations, mock(ChatHistoryReconstructor.class),
                mock(AgentInputFileMapper.class), mock(AiSkillMapper.class), mock(PermissionResolver.class), lock, mock(RunRegistry.class),
                finalizer, mock(RunEventTee.class), mock(UsageExtractor.class), mock(AiModelCallRecordService.class),
                mock(ProviderRegistry.class), executor);
        TurnRequest req = new TurnRequest();
        req.setQuery("把退款流程改一下");

        try (LogCapture logs = LogCapture.everything()) {
            svc.startTurn(1L, req, "trace-1");

            assertThat(logs.warnings()).anySatisfy(m -> assertThat(m).contains("构建器同步草稿失败").contains("IllegalStateException"));
            assertThat(logs.all()).noneMatch(m -> m.contains(FAKE));
        }
        verify(sessions).syncAfterRun(eq(1L), any());   // 确实走到了这一步
        verify(finalizer).complete(anyString());          // 失败了也照常收尾
    }
}

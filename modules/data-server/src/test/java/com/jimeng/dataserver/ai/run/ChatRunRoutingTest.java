package com.jimeng.dataserver.ai.run;

import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.agent.exec.service.AgentExecService;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.answer.RagAnswerService;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ChatRunService#decideExec}：这一轮跑在沙箱平面还是对话平面。
 *
 * <h3>为什么要钉住它</h3>
 * 这是<b>所有对话</b>的分流点，改错了不会报错，只是行为变了。其中第二条判据
 * 「本会话此前传过文件 → 之后每一轮都走沙箱」过去是<b>不可配置也不可见</b>的：
 * 用户只知道「Agent 的行为变了」，排查的人没有任何痕迹。
 *
 * <p>本类最重要的一条是<b>默认值 0 必须与改造前逐字一致</b>——本仓库 push main 即部署生产。
 */
class ChatRunRoutingTest {

    private ChatConversationService conversations;
    private AgentInputFileMapper inputFiles;
    private SkillTenantService skills;
    private AgentRuntimeService agents;
    private ChatRunService svc;

    @BeforeEach
    void setUp() {
        conversations = mock(ChatConversationService.class);
        inputFiles = mock(AgentInputFileMapper.class);
        skills = mock(SkillTenantService.class);
        agents = mock(AgentRuntimeService.class);
        svc = new ChatRunService(
                conversations, mock(ConversationRunLock.class), mock(RunRegistry.class),
                mock(RunFinalizer.class), mock(RunEventTee.class), mock(RunReplayPump.class),
                mock(SseServiceUtil.class), mock(RagAnswerService.class),
                mock(ChatHistoryReconstructor.class), mock(AgentExecService.class),
                inputFiles, agents, skills,
                mock(ThreadPoolTaskExecutor.class), mock(ThreadPoolTaskExecutor.class));
        window(0);
    }

    private void window(int turns) {
        ReflectionTestUtils.setField(svc, "sandboxStickinessTurns", turns);
    }

    private boolean decide(List<Long> fileIds) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(svc, "decideExec", 1L, fileIds, null, false));
    }

    /** 让输入文件表「有行」或「没行」。窗口生效时带时间条件的那次查询同样落到这里。 */
    private void historicalFiles(long count) {
        when(inputFiles.selectCount(any())).thenReturn(count);
    }

    @Test
    @DisplayName("本轮带附件 → 沙箱平面")
    void 本轮带附件() {
        assertTrue(decide(List.of(7L)));
    }

    @Test
    @DisplayName("★ 默认窗口 0：会话此前传过文件就走沙箱——与改造前逐字一致")
    void 默认不收窄() {
        historicalFiles(1L);

        assertTrue(decide(null));
        // 默认不该去问会话服务要时间下界：窗口关着的时候多一次查询是白花的。
        org.mockito.Mockito.verify(conversations, org.mockito.Mockito.never())
                .userTurnCutoff(any(), anyInt());
    }

    @Test
    @DisplayName("配了窗口：向会话服务要时间下界，并用它过滤输入文件")
    void 窗口生效() {
        window(3);
        when(conversations.userTurnCutoff(eq(1L), eq(3))).thenReturn(new Date(1_000_000L));
        historicalFiles(0L);   // 窗口内没有文件

        assertFalse(decide(null));
        org.mockito.Mockito.verify(conversations).userTurnCutoff(eq(1L), eq(3));
    }

    @Test
    @DisplayName("★ 会话短于窗口时下界为 null = 不收窄，而不是「没有文件」")
    void 下界为空不收窄() {
        window(3);
        when(conversations.userTurnCutoff(any(), anyInt())).thenReturn(null);
        historicalFiles(1L);

        assertTrue(decide(null), "下界取不到表示整条会话都在窗口内，应当照旧走沙箱");
    }

    @Test
    @DisplayName("无附件、无 DOER 技能 → 对话平面")
    void 纯对话走对话平面() {
        historicalFiles(0L);

        assertFalse(decide(null));
    }
}

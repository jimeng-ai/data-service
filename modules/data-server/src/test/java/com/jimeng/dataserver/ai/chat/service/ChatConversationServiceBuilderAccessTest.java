package com.jimeng.dataserver.ai.chat.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.ChatConversationMapper;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隐藏的 Skill 构建器 Agent 对所有成员放行、其余 Agent 照旧按 RBAC——豁免必须窄到只有这一个 code。
 * （此前普通成员连构建器会话都建不了：RBAC 没人会给一个隐藏 Agent 授权。）
 */
class ChatConversationServiceBuilderAccessTest {

    private static final long AGENT_ID = 2104000000000000001L;

    private static Agent agent(String code) {
        Agent a = new Agent();
        a.setId(AGENT_ID);
        a.setCode(code);
        return a;
    }

    private static CreateConversationRequest req() {
        CreateConversationRequest r = new CreateConversationRequest();
        r.setAgentId(String.valueOf(AGENT_ID));
        r.setAgentName("x");
        r.setTitle("t");
        return r;
    }

    private record Fixture(ChatConversationService svc, AgentMapper agents, ChatConversationMapper conversations) {}

    private static Fixture denyingRbac() {
        PermissionResolver perm = mock(PermissionResolver.class);
        doThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无权访问该资源"))
                .when(perm).assertCurrentAccess(eq(ResourceType.AGENT), anyLong());
        AgentMapper agents = mock(AgentMapper.class);
        ChatConversationMapper conversations = mock(ChatConversationMapper.class);
        return new Fixture(new ChatConversationService(conversations, mock(ChatMessageMapper.class), perm, agents),
                agents, conversations);
    }

    @Test
    @DisplayName("RBAC 拒绝 + 是 __skill_builder__ → 放行（成员能建构建器会话）")
    void builderAgentIsExempt() {
        Fixture f = denyingRbac();
        when(f.agents().selectById(AGENT_ID)).thenReturn(agent("__skill_builder__"));
        assertDoesNotThrow(() -> f.svc().create(req()));
        verify(f.conversations()).insert(any(com.jimeng.persistence.entity.ChatConversation.class));
    }

    @Test
    @DisplayName("RBAC 拒绝 + 其它 Agent（包括 Agent 构建器）→ 照旧拒绝")
    void otherAgentsStillDenied() {
        for (String code : new String[]{"__agent_builder__", "sales-bot", null}) {
            Fixture f = denyingRbac();
            when(f.agents().selectById(AGENT_ID)).thenReturn(agent(code));
            assertThrows(ServiceException.class, () -> f.svc().create(req()), "code=" + code);
            verify(f.conversations(), never()).insert(any(com.jimeng.persistence.entity.ChatConversation.class));
        }
        Fixture missing = denyingRbac();
        when(missing.agents().selectById(AGENT_ID)).thenReturn(null);
        assertThrows(ServiceException.class, () -> missing.svc().create(req()), "Agent 不存在也照旧拒绝");
    }

    @Test
    @DisplayName("RBAC 放行时不多查一次 Agent（正常路径零额外开销）")
    void allowedPathDoesNotQueryAgent() {
        PermissionResolver perm = mock(PermissionResolver.class);
        AgentMapper agents = mock(AgentMapper.class);
        ChatConversationService svc = new ChatConversationService(
                mock(ChatConversationMapper.class), mock(ChatMessageMapper.class), perm, agents);
        assertDoesNotThrow(() -> svc.create(req()));
        verify(agents, never()).selectById(anyLong());
    }
}

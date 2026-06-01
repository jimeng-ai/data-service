package com.jimeng.dataserver.ai.chat.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.AppendMessageRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationDetail;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.MessageView;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.ChatConversation;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.ChatConversationMapper;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 对话会话 / 消息持久化。
 *
 * <p>{@code chat_conversation} / {@code chat_message} 均在多租户拦截器白名单内，
 * 因此所有 CRUD 自动按当前租户隔离；这里不显式拼 {@code tenant_id}。
 */
@Service
@RequiredArgsConstructor
public class ChatConversationService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_LEN = 60;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final PermissionResolver permissionResolver;

    // ------------------------------------------------------------------ 会话

    public ConversationView create(CreateConversationRequest req) {
        if (req == null || !StringUtils.hasText(req.getAgentId())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "agentId 不能为空");
        }
        // 会话的父资源是 Agent：成员只能对被授权的 Agent 建会话/读写其历史，挡住「凭已知 id 直连未授权 Agent」。
        assertAgentAccess(req.getAgentId());
        ChatConversation c = new ChatConversation();
        c.setAgentId(req.getAgentId().trim());
        c.setAgentName(StrUtil.blankToDefault(req.getAgentName(), "Agent"));
        c.setTitle(normalizeTitle(req.getTitle(), DEFAULT_TITLE));
        c.setLastMessageAt(new Date());
        conversationMapper.insert(c);
        return toView(c, 0L);
    }

    public List<ConversationView> list() {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
                .orderByDesc(ChatConversation::getLastMessageAt)
                .orderByDesc(ChatConversation::getId);
        return conversationMapper.selectList(wrapper).stream()
                .map(c -> toView(c, null))
                .toList();
    }

    public ConversationDetail detail(Long id) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        List<ChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .orderByAsc(ChatMessage::getCreateTime)
                        .orderByAsc(ChatMessage::getId));
        ConversationDetail detail = new ConversationDetail();
        detail.setConversation(toView(c, (long) messages.size()));
        detail.setMessages(messages.stream().map(this::toMessageView).toList());
        return detail;
    }

    public ConversationView rename(Long id, String title) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        c.setTitle(normalizeTitle(title, c.getTitle()));
        conversationMapper.updateById(c);
        return toView(c, null);
    }

    public void delete(Long id) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        // 逻辑删除会话与其消息
        conversationMapper.deleteById(id);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, id));
    }

    // ------------------------------------------------------------------ 消息

    @Transactional
    public MessageView appendMessage(Long conversationId, AppendMessageRequest req) {
        ChatConversation c = requireConversation(conversationId);
        assertAgentAccess(c.getAgentId());
        if (req == null || !StringUtils.hasText(req.getContent())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "content 不能为空");
        }
        String role = ROLE_ASSISTANT.equalsIgnoreCase(req.getRole()) ? ROLE_ASSISTANT : ROLE_USER;

        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(req.getContent());
        if (req.getCitations() != null) {
            m.setCitations(JSONUtil.toJsonStr(req.getCitations()));
        }
        if (req.getSegments() != null) {
            m.setSegments(JSONUtil.toJsonStr(req.getSegments()));
        }
        if (req.getAttachments() != null) {
            m.setAttachments(JSONUtil.toJsonStr(req.getAttachments()));
        }
        m.setElapsedMs(req.getElapsedMs());
        messageMapper.insert(m);

        // 更新会话：刷新 last_message_at；若标题仍是默认值且这是用户消息，用其作为标题。
        c.setLastMessageAt(new Date());
        if (ROLE_USER.equals(role) && (StrUtil.isBlank(c.getTitle()) || DEFAULT_TITLE.equals(c.getTitle()))) {
            c.setTitle(normalizeTitle(req.getContent(), DEFAULT_TITLE));
        }
        conversationMapper.updateById(c);

        return toMessageView(m);
    }

    // ------------------------------------------------------------------ helpers

    /** 校验当前账号对会话所属 Agent 有访问权；超管放行。agentId 非数字（历史脏数据）则跳过，避免误伤。 */
    private void assertAgentAccess(String agentId) {
        if (StrUtil.isBlank(agentId)) {
            return;
        }
        long id;
        try {
            id = Long.parseLong(agentId.trim());
        } catch (NumberFormatException e) {
            return;
        }
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
    }

    private ChatConversation requireConversation(Long id) {
        ChatConversation c = id == null ? null : conversationMapper.selectById(id);
        if (c == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "会话不存在: " + id);
        }
        return c;
    }

    private String normalizeTitle(String raw, String fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String t = raw.trim().replaceAll("\\s+", " ");
        return t.length() > TITLE_MAX_LEN ? t.substring(0, TITLE_MAX_LEN) : t;
    }

    private ConversationView toView(ChatConversation c, Long messageCount) {
        ConversationView v = new ConversationView();
        v.setId(c.getId());
        v.setAgentId(c.getAgentId());
        v.setAgentName(c.getAgentName());
        v.setTitle(c.getTitle());
        v.setLastMessageAt(c.getLastMessageAt());
        v.setCreateTime(c.getCreateTime());
        v.setMessageCount(messageCount);
        return v;
    }

    private MessageView toMessageView(ChatMessage m) {
        MessageView v = new MessageView();
        v.setId(m.getId());
        v.setConversationId(m.getConversationId());
        v.setRole(m.getRole());
        v.setContent(m.getContent());
        v.setCitations(StrUtil.isBlank(m.getCitations()) ? null : JSONUtil.parse(m.getCitations()));
        v.setSegments(StrUtil.isBlank(m.getSegments()) ? null : JSONUtil.parse(m.getSegments()));
        v.setAttachments(StrUtil.isBlank(m.getAttachments()) ? null : JSONUtil.parse(m.getAttachments()));
        v.setElapsedMs(m.getElapsedMs());
        v.setCreateTime(m.getCreateTime());
        return v;
    }
}

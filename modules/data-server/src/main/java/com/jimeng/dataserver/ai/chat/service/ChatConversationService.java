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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String STATUS_GENERATING = "GENERATING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 一轮对话落库后返回的两条消息 id。 */
    public record TurnMessageIds(Long userMessageId, Long assistantMessageId) {
    }

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
        // 会话「按人私有」：成员只看自己创建的；超管（owner==null）看本租户全部。
        // 历史遗留：仅靠租户拦截器隔离，会把全租户会话都列出 -> 同租户互相看到对方会话。
        String owner = permissionResolver.ownerScopeOrNull();
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
                .eq(owner != null, ChatConversation::getCreateUser, owner)
                .orderByDesc(ChatConversation::getLastMessageAt)
                .orderByDesc(ChatConversation::getId);
        // 一次查出（属主范围内）所有「正在生成」的助手消息，给列表项打上「正在回复」标志（驱动左侧转圈/圆点）。
        Map<Long, String> activeByConv = activeRunByConversation(owner);
        return conversationMapper.selectList(wrapper).stream()
                .map(c -> {
                    ConversationView v = toView(c, null);
                    String runId = activeByConv.get(c.getId());
                    v.setGenerating(runId != null);
                    v.setActiveRunId(runId);
                    return v;
                })
                .toList();
    }

    /** 会话 id → 当前在跑的 runId（仅含有 GENERATING 助手消息的会话）。owner!=null 时只统计本人消息。 */
    private Map<Long, String> activeRunByConversation(String owner) {
        List<ChatMessage> active = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STATUS_GENERATING)
                .eq(owner != null, ChatMessage::getCreateUser, owner));
        Map<Long, String> map = new HashMap<>();
        for (ChatMessage m : active) {
            map.putIfAbsent(m.getConversationId(), m.getRunId());
        }
        return map;
    }

    public ConversationDetail detail(Long id) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        assertOwner(c);
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
        assertOwner(c);
        c.setTitle(normalizeTitle(title, c.getTitle()));
        conversationMapper.updateById(c);
        return toView(c, null);
    }

    public void delete(Long id) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        assertOwner(c);
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
        assertOwner(c);
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

    // ------------------------------------------------------------------ 服务端自持久化的一轮对话（/turns）

    /**
     * 落一轮对话的两条消息：用户消息（COMPLETED）+ 助手占位（GENERATING，带 runId）。
     * 在生成开始前同步落库并提交，使其后续 GET 重连 / 会话刷新都能看到。
     */
    @Transactional
    public TurnMessageIds insertTurnMessages(Long conversationId, String content, Object attachments, String runId) {
        ChatConversation c = requireConversation(conversationId);
        assertAgentAccess(c.getAgentId());
        assertOwner(c);
        if (!StringUtils.hasText(content)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "content 不能为空");
        }
        ChatMessage user = new ChatMessage();
        user.setConversationId(conversationId);
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_COMPLETED);
        user.setContent(content);
        if (attachments != null) {
            user.setAttachments(JSONUtil.toJsonStr(attachments));
        }
        messageMapper.insert(user);

        ChatMessage assistant = new ChatMessage();
        assistant.setConversationId(conversationId);
        assistant.setRole(ROLE_ASSISTANT);
        assistant.setStatus(STATUS_GENERATING);
        assistant.setRunId(runId);
        assistant.setContent("");
        messageMapper.insert(assistant);

        c.setLastMessageAt(new Date());
        if (StrUtil.isBlank(c.getTitle()) || DEFAULT_TITLE.equals(c.getTitle())) {
            c.setTitle(normalizeTitle(content, DEFAULT_TITLE));
        }
        conversationMapper.updateById(c);
        return new TurnMessageIds(user.getId(), assistant.getId());
    }

    /**
     * 生成收尾：把助手占位消息更新为最终态。
     * MyBatis-Plus 默认不更新 null 字段——纯文本回复 segmentsJson=null 不会清掉、成功时 error=null 不会写，符合预期。
     */
    public void finalizeAssistant(Long messageId, String status, String content,
                                  String segmentsJson, String citationsJson, Long elapsedMs, String error) {
        ChatMessage m = new ChatMessage();
        m.setId(messageId);
        m.setStatus(status);
        m.setContent(content == null ? "" : content);
        m.setSegments(segmentsJson);
        m.setCitations(citationsJson);
        m.setElapsedMs(elapsedMs);
        m.setError(error);
        messageMapper.updateById(m);
    }

    /** 该会话当前是否有正在生成的助手回复（单活跃兜底校验）。 */
    public boolean hasActiveGeneration(Long conversationId) {
        Long n = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STATUS_GENERATING));
        return n != null && n > 0;
    }

    /** 校验当前账号对会话所属 Agent 有访问权，返回会话。供 /turns、/runs 重连/取消鉴权复用。 */
    public ChatConversation requireConversationWithAccess(Long id) {
        ChatConversation c = requireConversation(id);
        assertAgentAccess(c.getAgentId());
        assertOwner(c);
        return c;
    }

    /** 据 runId 反查所属会话 id（即使生成已结束、消息已落终态仍可查到）。 */
    public Long conversationIdOfRun(String runId) {
        if (StrUtil.isBlank(runId)) return null;
        List<ChatMessage> ms = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRunId, runId)
                .last("limit 1"));
        return ms.isEmpty() ? null : ms.get(0).getConversationId();
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

    /**
     * 校验当前账号是会话属主（或超管）。会话「按人私有」：成员只能读写自己创建的会话，
     * 否则即便对会话所属 Agent 有权也不可见（抛 NOT_FOUND，表现与「会话不存在」一致）。
     * 与 {@link #assertAgentAccess} 叠加：既要对 Agent 有权，又要是会话属主。
     */
    private void assertOwner(ChatConversation c) {
        permissionResolver.assertOwnerOrSuperAdmin(c.getCreateUser());
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
        v.setStatus(m.getStatus());
        v.setRunId(m.getRunId());
        v.setError(m.getError());
        v.setCreateTime(m.getCreateTime());
        return v;
    }
}

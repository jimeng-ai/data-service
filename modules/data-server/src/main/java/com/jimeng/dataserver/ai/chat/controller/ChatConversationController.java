package com.jimeng.dataserver.ai.chat.controller;

import com.jimeng.dataserver.ai.chat.dto.ChatDtos.AppendMessageRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationDetail;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.MessageView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.UpdateConversationRequest;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 「对话」会话与消息：落库到 chat_conversation / chat_message，按租户隔离。
 */
@Tag(name = "对话会话", description = "ToB Agent 平台 - 对话会话与消息持久化")
@RestController
@RequestMapping("/data/admin/chat/conversations")
@RequiredArgsConstructor
public class ChatConversationController {

    private final ChatConversationService conversationService;

    @Operation(summary = "创建会话")
    @PostMapping
    public ConversationView create(@RequestBody CreateConversationRequest req) {
        return conversationService.create(req);
    }

    @Operation(summary = "列出当前租户的会话（按最近活动倒序）")
    @GetMapping
    public List<ConversationView> list() {
        return conversationService.list();
    }

    @Operation(summary = "会话详情（含消息）")
    @GetMapping("/{id}")
    public ConversationDetail detail(@PathVariable Long id) {
        return conversationService.detail(id);
    }

    @Operation(summary = "重命名会话")
    @PutMapping("/{id}")
    public ConversationView rename(@PathVariable Long id, @RequestBody UpdateConversationRequest req) {
        return conversationService.rename(id, req == null ? null : req.getTitle());
    }

    @Operation(summary = "删除会话（连带消息）")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return Map.of("deleted", true);
    }

    @Operation(summary = "追加一条消息（同时刷新会话标题与活动时间）")
    @PostMapping("/{id}/messages")
    public MessageView appendMessage(@PathVariable Long id, @RequestBody AppendMessageRequest req) {
        return conversationService.appendMessage(id, req);
    }
}

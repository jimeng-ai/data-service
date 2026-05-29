package com.jimeng.dataserver.ai.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 「对话」相关的请求 / 响应 DTO 集合。
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    @Schema(description = "创建会话请求")
    @Data
    public static class CreateConversationRequest {
        @Schema(description = "所属 Agent ID（必填）")
        private String agentId;
        @Schema(description = "Agent 名称快照（可选）")
        private String agentName;
        @Schema(description = "会话标题（可选，默认「新对话」）")
        private String title;
    }

    @Schema(description = "重命名会话请求")
    @Data
    public static class UpdateConversationRequest {
        @Schema(description = "新标题")
        private String title;
    }

    @Schema(description = "追加消息请求")
    @Data
    public static class AppendMessageRequest {
        @Schema(description = "角色：user / assistant")
        private String role;
        @Schema(description = "消息正文")
        private String content;
        @Schema(description = "引用列表（任意 JSON，可空）")
        private Object citations;
    }

    @Schema(description = "会话视图")
    @Data
    public static class ConversationView {
        private Long id;
        private String agentId;
        private String agentName;
        private String title;
        private Date lastMessageAt;
        private Date createTime;
        @Schema(description = "消息条数（列表场景可为空）")
        private Long messageCount;
    }

    @Schema(description = "消息视图")
    @Data
    public static class MessageView {
        private Long id;
        private Long conversationId;
        private String role;
        private String content;
        @Schema(description = "引用列表（已解析为 JSON）")
        private Object citations;
        private Date createTime;
    }

    @Schema(description = "会话详情（含消息）")
    @Data
    public static class ConversationDetail {
        private ConversationView conversation;
        private List<MessageView> messages;
    }
}

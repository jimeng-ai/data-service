package com.jimeng.dataserver.ai.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
        @Schema(description = "助手消息有序片段（文本/工具调用交错，任意 JSON，可空）")
        private Object segments;
        @Schema(description = "消息附件列表（fileId/filename/contentType，任意 JSON，可空）")
        private Object attachments;
        @Schema(description = "助手生成总耗时（毫秒，可空）")
        private Long elapsedMs;
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
        @Schema(description = "该会话当前是否有正在生成的助手回复（驱动列表「正在回复」提示）")
        private Boolean generating;
        @Schema(description = "当前在跑的生成运行 id（generating=true 时有值，可用于直接续接）")
        private String activeRunId;
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
        @Schema(description = "助手消息有序片段（已解析为 JSON，可空）")
        private Object segments;
        @Schema(description = "消息附件列表（已解析为 JSON，可空）")
        private Object attachments;
        @Schema(description = "助手生成总耗时（毫秒，可空）")
        private Long elapsedMs;
        @Schema(description = "生成状态：GENERATING / COMPLETED / FAILED / CANCELLED")
        private String status;
        @Schema(description = "生成运行 id（GENERATING 时据此重连续播，可空）")
        private String runId;
        @Schema(description = "生成失败/取消原因（可空）")
        private String error;
        private Date createTime;
    }

    @Schema(description = "会话详情（含消息）")
    @Data
    public static class ConversationDetail {
        private ConversationView conversation;
        private List<MessageView> messages;
    }

    @Schema(description = "发起一轮对话（服务端自持久化 + 可重连）请求")
    @Data
    public static class TurnStartRequest {
        @Schema(description = "所属 Agent ID（必填）")
        private String agentId;
        @Schema(description = "用户当前轮提问（必填）")
        private String query;
        @Schema(description = "多轮会话历史（OpenAI/Claude messages 数组，可空；当前轮 user message 由服务端拼装）")
        private List<Map<String, Object>> history;
        @Schema(description = "本轮上传文件 id（可空）；非空或会话历史含文件则走代码执行 Agent（exec）分支")
        private List<Long> fileIds;
        @Schema(description = "用户消息附件元信息（fileId/filename/contentType，任意 JSON，可空）")
        private Object attachments;
        @Schema(description = "知识库 ID（可选，调试台显式挂库时用）")
        private Long kbId;
        @Schema(description = "检索 topK（可选）")
        private Integer topK;
        @Schema(description = "是否启用 reranker 精排（可选）")
        private Boolean rerank;
        @Schema(description = "是否调试台预览（读实时草稿）；缺省=对话端只读已发布快照")
        private boolean preview;
    }

    @Schema(description = "发起一轮对话的响应：立即返回，生成在服务端进行")
    @Data
    public static class TurnStartResponse {
        @Schema(description = "生成运行 id，用 GET /runs/{runId}/stream 消费/重连")
        private String runId;
        @Schema(description = "已落库的用户消息 id")
        private Long userMessageId;
        @Schema(description = "已落库的助手消息 id（GENERATING 占位）")
        private Long assistantMessageId;

        public TurnStartResponse() {
        }

        public TurnStartResponse(String runId, Long userMessageId, Long assistantMessageId) {
            this.runId = runId;
            this.userMessageId = userMessageId;
            this.assistantMessageId = assistantMessageId;
        }
    }
}

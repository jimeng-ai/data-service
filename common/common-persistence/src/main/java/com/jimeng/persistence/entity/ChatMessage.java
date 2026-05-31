package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话消息（隶属于 {@link ChatConversation}）。
 *
 * <p>租户隔离：表已加入多租户拦截器白名单。
 *
 * @TableName chat_message
 */
@Schema(description = "对话消息表")
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
@Data
public class ChatMessage extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属会话 ID")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "角色：user / assistant")
    @TableField("role")
    private String role;

    @Schema(description = "消息正文")
    @TableField("content")
    private String content;

    @Schema(description = "引用列表（JSON 字符串，可空）")
    @TableField("citations")
    private String citations;

    @Schema(description = "助手消息有序片段（文本/工具调用交错，JSON 字符串，可空）")
    @TableField("segments")
    private String segments;

    @Schema(description = "助手生成总耗时（毫秒，可空）")
    @TableField("elapsed_ms")
    private Long elapsedMs;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 对话会话（控制台「对话」中的一段会话）。
 *
 * <p>租户隔离：表已加入多租户拦截器白名单，{@code tenant_id} 由拦截器在
 * INSERT/SELECT 时自动注入，业务代码无需显式赋值/过滤。
 *
 * @TableName chat_conversation
 */
@Schema(description = "对话会话表")
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
@Data
public class ChatConversation extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属 Agent ID")
    @TableField("agent_id")
    private String agentId;

    @Schema(description = "Agent 名称快照（防止 Agent 改名/删除后历史不可读）")
    @TableField("agent_name")
    private String agentName;

    @Schema(description = "会话标题（一般取首条用户消息）")
    @TableField("title")
    private String title;

    @Schema(description = "最近一条消息时间")
    @TableField("last_message_at")
    private Date lastMessageAt;
}

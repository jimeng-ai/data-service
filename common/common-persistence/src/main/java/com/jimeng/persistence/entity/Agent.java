package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 实体（岗位智能体）。
 *
 * <p>每个 Agent 是一份"人设 + 默认模型参数 + 可用插件集合"的配置。
 * 业务请求带 {@code agent_id} → ClaudeService 加载这份配置 → 注入到 Claude 请求。
 *
 * @TableName agent
 */
@Schema(description = "Agent 实体表")
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
@Data
public class Agent extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "Agent slug，租户内唯一")
    @TableField("code")
    private String code;

    @Schema(description = "Agent 展示名")
    @TableField("name")
    private String name;

    @Schema(description = "Agent 描述")
    @TableField("description")
    private String description;

    @Schema(description = "头像 URL")
    @TableField("avatar_url")
    private String avatarUrl;

    @Schema(description = "人设 / 系统提示词")
    @TableField("system_prompt")
    private String systemPrompt;

    @Schema(description = "默认模型（如 claude-opus-4-1）；请求体可 override")
    @TableField("model")
    private String model;

    @Schema(description = "模型参数默认值 {temperature, max_tokens, top_p, ...}（JSON）")
    @TableField("model_params")
    private String modelParams;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED")
    @TableField("status")
    private String status;

    @Schema(description = "Agent 所有者用户 ID")
    @TableField("owner_id")
    private String ownerId;
}

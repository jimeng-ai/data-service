package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 构建器会话：一次「在沙箱里原样跑 skill-creator、新建或改进一个 skill」的持久状态。
 *
 * <p>skill-creator 的全部状态在工作区文件里（草稿、evals.json、iteration-N/、feedback.json），
 * 工作区本身在 MinIO 的 {@link #workspacePrefix} 下；这张表只记「谁的会话、对应哪个对话、在改哪个 skill」。
 * 租户隔离表（已加入 TENANT_AWARE_TABLES）。
 */
@Schema(description = "Skill 构建器会话")
@EqualsAndHashCode(callSuper = true)
@TableName("skill_builder_session")
@Data
public class SkillBuilderSession extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @Schema(description = "构建器对话（chat_conversation.id），聊天记录与 run 原语都挂在它上面")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "新建 skill 时的 DRAFT ai_skill 行（首次同步到草稿时才建）")
    @TableField("draft_skill_id")
    private Long draftSkillId;

    @Schema(description = "改进已有 skill 时的目标 skill id")
    @TableField("base_skill_id")
    private Long baseSkillId;

    @Schema(description = "会话开始时目标 skill 的版本；发布时据此做乐观校验")
    @TableField("base_version")
    private Integer baseVersion;

    @Schema(description = "工作区在 MinIO 上的前缀，以 / 结尾")
    @TableField("workspace_prefix")
    private String workspacePrefix;

    @Schema(description = "用户手动指定的运行方式 PROMPT/DOER；null = 按附带文件推断")
    @TableField("skill_type_override")
    private String skillTypeOverride;

    @Schema(description = "ACTIVE / PUBLISHED / ABANDONED")
    @TableField("status")
    private String status;

    @TableField("published_skill_id")
    private Long publishedSkillId;

    @TableField("last_run_id")
    private String lastRunId;
}

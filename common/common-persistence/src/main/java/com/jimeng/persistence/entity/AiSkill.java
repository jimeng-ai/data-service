package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户私有 Skill 主表
 *
 * <p>对应 table: {@code ai_skill}。P1 仅支持 PROMPT 类型；DOER 字段预留。
 *
 * @TableName ai_skill
 */
@Schema(description = "租户私有 Skill 主表")
@EqualsAndHashCode(callSuper = true)
@TableName("ai_skill")
@Data
public class AiSkill extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "创建者用户 ID")
    @TableField("owner_user_id")
    private Long ownerUserId;

    @Schema(description = "可见范围：PRIVATE / TENANT")
    @TableField("scope")
    private String scope;

    @Schema(description = "frontmatter name")
    @TableField("name")
    private String name;

    @Schema(description = "frontmatter description")
    @TableField("description")
    private String description;

    @Schema(description = "SKILL.md 正文")
    @TableField("body")
    private String body;

    @Schema(description = "skill 类型：PROMPT / DOER")
    @TableField("skill_type")
    private String skillType;

    @Schema(description = "来源：UPLOAD / MARKET / AI_GEN")
    @TableField("source")
    private String source;

    @Schema(description = "市面来源 owner/repo@ref:path")
    @TableField("origin_ref")
    private String originRef;

    @Schema(description = "状态：DRAFT / ACTIVE / DISABLED")
    @TableField("status")
    private String status;

    @Schema(description = "MinIO bundle 前缀（DOER 类型使用）")
    @TableField("bundle_key")
    private String bundleKey;

    @Schema(description = "bundle sha256")
    @TableField("bundle_hash")
    private String bundleHash;

    @Schema(description = "版本号")
    @TableField("version")
    private Integer version;
}

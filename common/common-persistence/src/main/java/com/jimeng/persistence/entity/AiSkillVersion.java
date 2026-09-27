package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * skill 的一个已发布版本。ai_skill 行永远指向当前版本（version / bundle_key），这里留下每一版的
 * bundle 位置与元数据——改进已有 skill 时新版本写到 {@code skills/{id}/{version}/}，旧版本的 bundle 不覆盖、可回溯。
 * 租户隔离表（已加入 TENANT_AWARE_TABLES）。
 */
@Schema(description = "Skill 版本历史")
@EqualsAndHashCode(callSuper = true)
@TableName("ai_skill_version")
@Data
public class AiSkillVersion extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("skill_id")
    private Long skillId;

    @TableField("version")
    private Integer version;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("skill_type")
    private String skillType;

    @TableField("bundle_key")
    private String bundleKey;

    @TableField("bundle_hash")
    private String bundleHash;

    @Schema(description = "产出这一版的构建器会话；上传 / 导入的版本为 null")
    @TableField("builder_session_id")
    private Long builderSessionId;
}

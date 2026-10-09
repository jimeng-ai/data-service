package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 企业 skill 的一个 key（加密保存）。一个版本一组行；key 能发往哪里，只看这一行上的 hosts / methods / paths。
 * 租户隔离表（已加入 TENANT_AWARE_TABLES）。
 */
@Schema(description = "技能里的 key（加密保存）")
@EqualsAndHashCode(callSuper = true)
@TableName("skill_secret")
@Data
public class SkillSecret extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("skill_id")
    private Long skillId;

    @TableField("version")
    private Integer version;

    @TableField("name")
    private String name;

    @Schema(description = "JSON 数组：只能发往这些域名")
    @TableField("hosts")
    private String hosts;

    @Schema(description = "JSON 数组；NULL = 不限")
    @TableField("methods")
    private String methods;

    @Schema(description = "JSON 数组；NULL = 不限")
    @TableField("paths")
    private String paths;

    /** 密文也不进 toString：@Data 生成的 toString 会被顺手打进日志。 */
    @ToString.Exclude
    @TableField("ciphertext")
    private String ciphertext;

    @TableField("cipher_version")
    private Integer cipherVersion;
}

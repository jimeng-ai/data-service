package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 企业自定义角色（租户内）。不在租户白名单内，service 层显式拼 {@code WHERE tenant_id=?}。
 *
 * @TableName sys_role
 */
@Schema(description = "企业自定义角色")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
@Data
public class SysRole extends BaseEntity {

    @Schema(description = "所属租户")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "角色 slug，租户内唯一")
    @TableField("code")
    private String code;

    @Schema(description = "角色名")
    @TableField("name")
    private String name;

    @Schema(description = "描述")
    @TableField("description")
    private String description;
}

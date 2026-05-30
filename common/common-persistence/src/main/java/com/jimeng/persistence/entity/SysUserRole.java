package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 成员 ↔ 角色绑定（多对多）。不在租户白名单内，service 层显式拼 {@code WHERE tenant_id=?}。
 *
 * @TableName sys_user_role
 */
@Schema(description = "成员-角色绑定")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_role")
@Data
public class SysUserRole extends BaseEntity {

    @Schema(description = "所属租户（冗余）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "成员 ID（sys_user.id）")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "角色 ID（sys_role.id）")
    @TableField("role_id")
    private Long roleId;
}

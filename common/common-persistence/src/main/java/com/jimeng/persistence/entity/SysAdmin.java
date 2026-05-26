package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 管理后台账户。data-service 自有，与 jm-momi 解耦。
 *
 * @TableName sys_admin
 */
@Schema(description = "管理后台账户")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_admin")
@Data
public class SysAdmin extends BaseEntity {

    @Schema(description = "所属租户 ID（登录后写入 JWT，gateway 据此注入 X-Tenant-Id）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "登录名（租户内唯一）")
    @TableField("username")
    private String username;

    @Schema(description = "BCrypt 哈希")
    @TableField("password_hash")
    private String passwordHash;

    @Schema(description = "展示名")
    @TableField("display_name")
    private String displayName;

    @Schema(description = "1=启用 0=禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "最近一次登录时间")
    @TableField("last_login_at")
    private Date lastLoginAt;
}

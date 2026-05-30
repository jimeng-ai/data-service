package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 企业账号（超管 + 成员）。登录 {@code /data/admin/auth/login}（jm-agent-front 与 jm-admin 企业门户共用）。
 *
 * <p>本表不在 {@code JimengTenantLineHandler.TENANT_AWARE_TABLES} 内：登录早于 TenantContext 设置，
 * 且 username 全局唯一，需按 username 全局解析；service 层显式拼 {@code WHERE tenant_id=?}。
 *
 * @TableName sys_user
 */
@Schema(description = "企业账号（超管/成员）")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
@Data
public class SysUser extends BaseEntity {

    /** user_type 取值。 */
    public static final String TYPE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String TYPE_MEMBER = "MEMBER";

    @Schema(description = "所属租户")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "登录名（全局唯一）")
    @TableField("username")
    private String username;

    @Schema(description = "BCrypt 哈希")
    @TableField("password_hash")
    private String passwordHash;

    @Schema(description = "展示名")
    @TableField("display_name")
    private String displayName;

    @Schema(description = "SUPER_ADMIN | MEMBER")
    @TableField("user_type")
    private String userType;

    @Schema(description = "1=启用 0=禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "最近一次登录时间")
    @TableField("last_login_at")
    private Date lastLoginAt;
}

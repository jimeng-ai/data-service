package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 平台运营账号（跨租户）。登录 {@code /data/admin/operator/auth/login}，
 * 签发的 JWT 带 {@code tenant_id="platform"}。
 *
 * @TableName sys_operator
 */
@Schema(description = "平台运营账号")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_operator")
@Data
public class SysOperator extends BaseEntity {

    @Schema(description = "登录名（全局唯一）")
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

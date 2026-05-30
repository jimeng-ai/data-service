package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 企业（= 租户）。{@code tenant_id} 即写入 JWT、由 gateway 注入 {@code X-Tenant-Id} 的取值。
 *
 * <p>本表不在 {@code JimengTenantLineHandler.TENANT_AWARE_TABLES} 内：运营要跨租户列出所有企业，
 * 不能被自动注入 {@code WHERE tenant_id=?}。
 *
 * @TableName sys_enterprise
 */
@Schema(description = "企业（租户）")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_enterprise")
@Data
public class SysEnterprise extends BaseEntity {

    @Schema(description = "租户标识（[A-Za-z0-9._-] ≤64）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "企业名称")
    @TableField("name")
    private String name;

    @Schema(description = "描述")
    @TableField("description")
    private String description;

    @Schema(description = "1=启用 0=停用（停用后该租户全员禁止登录）")
    @TableField("status")
    private Integer status;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色 → 资源授权（通用资源-权限模型）。
 *
 * <ul>
 *   <li>{@code resource_type=MENU}：{@code resource_id=0}，{@code resource_code} 存模块码（AGENT_MODULE...）。</li>
 *   <li>{@code resource_type=AGENT/KNOWLEDGE_BASE/PLUGIN}：{@code resource_id} 为雪花实例 id。</li>
 * </ul>
 *
 * @TableName sys_role_resource
 */
@Schema(description = "角色-资源授权")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role_resource")
@Data
public class SysRoleResource extends BaseEntity {

    @Schema(description = "所属租户（冗余）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "角色 ID")
    @TableField("role_id")
    private Long roleId;

    @Schema(description = "MENU | AGENT | KNOWLEDGE_BASE | PLUGIN | ...")
    @TableField("resource_type")
    private String resourceType;

    @Schema(description = "实例 id；MENU 类为 0")
    @TableField("resource_id")
    private Long resourceId;

    @Schema(description = "模块码（MENU）或实例 code（冗余）")
    @TableField("resource_code")
    private String resourceCode;
}

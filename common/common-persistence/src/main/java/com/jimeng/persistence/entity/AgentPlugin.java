package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 与插件的多对多绑定。
 *
 * <p>{@code credential_alias} 决定 Agent 调用这个插件时用哪份凭证（为空走 {@code is_default=true}）。
 *
 * @TableName agent_plugin
 */
@Schema(description = "Agent 与插件绑定表")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_plugin")
@Data
public class AgentPlugin extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "Agent ID")
    @TableField("agent_id")
    private Long agentId;

    @Schema(description = "Plugin ID")
    @TableField("plugin_id")
    private Long pluginId;

    @Schema(description = "凭证别名（NULL = 用 is_default 凭证）")
    @TableField("credential_alias")
    private String credentialAlias;
}

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
 * <p>每个插件在租户内只有一份凭证，Agent 调用时直接取该插件的凭证，不再保存别名。
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
}

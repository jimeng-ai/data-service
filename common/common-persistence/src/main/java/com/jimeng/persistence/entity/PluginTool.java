package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 插件工具：暴露给 LLM 的单个可调用工具
 *
 * <p>对应 table: {@code plugin_tool}。工具名约定 {@code <plugin_code>.<verb>.<noun>}，
 * 例如 {@code jira.issue.create}。{@code input_schema} 直接喂给 Claude 的 tools.input_schema 字段。
 *
 * @TableName plugin_tool
 */
@Schema(description = "插件工具表")
@EqualsAndHashCode(callSuper = true)
@TableName("plugin_tool")
@Data
public class PluginTool extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属插件 ID")
    @TableField("plugin_id")
    private Long pluginId;

    @Schema(description = "工具名，租户内唯一（函数名：英文，供 LLM 调用）")
    @TableField("name")
    private String name;

    @Schema(description = "中文展示名（给人看；为空时前端回退用 name）")
    @TableField("title")
    private String title;

    @Schema(description = "工具描述（LLM 判断何时调用）")
    @TableField("description")
    private String description;

    @Schema(description = "Claude input_schema 格式的 JSON Schema")
    @TableField("input_schema")
    private String inputSchema;

    @Schema(description = "是否启用")
    @TableField("enabled")
    private Boolean enabled;

    @Schema(description = "HTTP 方法（来自 http 映射，非持久化）；列表接口回填，供前端区分 READ/WRITE")
    @TableField(exist = false)
    private String method;
}

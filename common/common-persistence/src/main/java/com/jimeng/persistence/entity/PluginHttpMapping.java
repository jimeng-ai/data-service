package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 插件工具到 HTTP 调用的映射（与 {@link PluginTool} 一对一）
 *
 * <p>模板字段支持 {@code {{namespace.path}}} 占位符，namespace 取值 input / secrets / env / meta。
 * 渲染、认证、调用、响应抽取的完整流程见 {@code PluginHttpInvoker}。
 *
 * @TableName plugin_http_mapping
 */
@Schema(description = "插件 HTTP 调用映射表")
@EqualsAndHashCode(callSuper = true)
@TableName("plugin_http_mapping")
@Data
public class PluginHttpMapping extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属工具 ID")
    @TableField("plugin_tool_id")
    private Long pluginToolId;

    @Schema(description = "HTTP 方法：GET / POST / PUT / PATCH / DELETE")
    @TableField("method")
    private String method;

    @Schema(description = "URL 模板，支持 {{...}} 占位")
    @TableField("url_template")
    private String urlTemplate;

    @Schema(description = "Header 模板（JSON 对象，叶子可含占位符）")
    @TableField("headers_template")
    private String headersTemplate;

    @Schema(description = "Query 参数模板（JSON 对象）")
    @TableField("query_template")
    private String queryTemplate;

    @Schema(description = "Body 模板（JSON 节点树）")
    @TableField("body_template")
    private String bodyTemplate;

    @Schema(description = "Body Content-Type，默认 application/json")
    @TableField("body_content_type")
    private String bodyContentType;

    @Schema(description = "响应抽取 JSONPath（如 $.main），为空则返完整响应")
    @TableField("response_extract")
    private String responseExtract;

    @Schema(description = "数组截断阈值，默认 50")
    @TableField("response_max_items")
    private Integer responseMaxItems;

    @Schema(description = "HTTP 超时（毫秒），为空走全局默认")
    @TableField("timeout_ms")
    private Integer timeoutMs;
}

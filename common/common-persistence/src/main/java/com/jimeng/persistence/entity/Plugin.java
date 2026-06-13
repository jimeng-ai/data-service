package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 插件主表
 *
 * <p>对应 table: {@code plugin}。一个插件包含一组工具（{@link PluginTool}），
 * 每个工具有一条 HTTP 映射（{@link PluginHttpMapping}），并共享插件级别的认证配置和凭证。
 *
 * @TableName plugin
 */
@Schema(description = "插件主表")
@EqualsAndHashCode(callSuper = true)
@TableName("plugin")
@Data
public class Plugin extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "插件 slug，租户内唯一")
    @TableField("code")
    private String code;

    @Schema(description = "插件展示名")
    @TableField("name")
    private String name;

    @Schema(description = "插件描述（给 LLM 看的简介）")
    @TableField("description")
    private String description;

    @Schema(description = "插件版本")
    @TableField("version")
    private String version;

    @Schema(description = "默认 base URL（可被 url_template 覆盖）")
    @TableField("base_url")
    private String baseUrl;

    @Schema(description = "认证类型：NONE / API_KEY / BEARER / BASIC / HMAC")
    @TableField("auth_type")
    private String authType;

    @Schema(description = "认证非密配置（JSON 字符串）")
    @TableField("auth_config")
    private String authConfig;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED")
    @TableField("status")
    private String status;

    @Schema(description = "插件所有者用户 ID")
    @TableField("owner_id")
    private String ownerId;

    @Schema(description = "动作（工具）数量；非持久化，列表接口按需回填")
    @TableField(exist = false)
    private Integer toolCount;

    @Schema(description = "被多少个 Agent 引用；非持久化，列表接口按需回填")
    @TableField(exist = false)
    private Integer refAgentCount;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 可选模型目录（平台级，运营维护）。
 *
 * <p>对应 table: {@code ai_model}。把原先散在 Nacos {@code ai.model-catalog} 与
 * {@code ModelPricing} 硬编码价里的「目录 / 计费 / 上游路由」三段，统一成一行：
 * <ul>
 *   <li>目录：{@code value}（逻辑 id，Agent 存的就是它）/ {@code label} / {@code description} / {@code enabled} / {@code maxTemp}</li>
 *   <li>路由：{@code provider}（引用 Nacos providers.* 连接名）+ {@code upstreamModel}（真正下发的上游名）+ {@code protocol}</li>
 *   <li>计费：四档单价 USD/百万 token</li>
 * </ul>
 * 平台级、全租户共享：不在租户白名单，运营经 {@code TenantContext.runAsSystem} 维护。
 *
 * @TableName ai_model
 */
@Schema(description = "可选模型目录（平台级）")
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model")
@Data
public class AiModel extends BaseEntity {

    @Schema(description = "逻辑模型id（下拉 value、Agent 存的就是它）")
    @TableField("value")
    private String value;

    @Schema(description = "展示名")
    @TableField("label")
    private String label;

    @Schema(description = "选型提示")
    @TableField("description")
    private String description;

    @Schema(description = "协议：anthropic / openai（须与所选连接协议一致）")
    @TableField("protocol")
    private String protocol;

    @Schema(description = "引用 Nacos providers.* 的连接名，如 302ai")
    @TableField("provider")
    private String provider;

    @Schema(description = "真正下发给上游的模型名")
    @TableField("upstream_model")
    private String upstreamModel;

    @Schema(description = "temperature 上限：anthropic=1 openai=2")
    @TableField("max_temp")
    private Double maxTemp;

    @Schema(description = "USD/百万token 输入价")
    @TableField("price_input")
    private BigDecimal priceInput;

    @Schema(description = "USD/百万token 输出价")
    @TableField("price_output")
    private BigDecimal priceOutput;

    @Schema(description = "USD/百万token 缓存读价")
    @TableField("price_cache_read")
    private BigDecimal priceCacheRead;

    @Schema(description = "USD/百万token 缓存写价")
    @TableField("price_cache_write")
    private BigDecimal priceCacheWrite;

    @Schema(description = "是否启用（下线置 false，不删行）")
    @TableField("enabled")
    private Boolean enabled;

    @Schema(description = "下拉排序，小在前")
    @TableField("sort")
    private Integer sort;
}

package com.jimeng.dataserver.admin.operator.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型新增/编辑入参。用 @Data class（非 record）。
 * 单价四档 USD/百万 token；protocol 须与所选 provider 的连接协议一致（服务端再校验一次）。
 */
@Schema(description = "模型新增/编辑入参")
@Data
public class ModelUpsertRequest {

    @Schema(description = "逻辑模型id（下拉 value、Agent 存的就是它）")
    private String value;

    @Schema(description = "展示名")
    private String label;

    @Schema(description = "选型提示")
    private String description;

    @Schema(description = "协议：anthropic / openai（须与所选连接协议一致）")
    private String protocol;

    @Schema(description = "引用 Nacos providers.* 的连接名，如 302ai")
    private String provider;

    @Schema(description = "真正下发给上游的模型名")
    private String upstreamModel;

    @Schema(description = "temperature 上限：anthropic=1 openai=2")
    private Double maxTemp;

    @Schema(description = "USD/百万token 输入价")
    private BigDecimal priceInput;

    @Schema(description = "USD/百万token 输出价")
    private BigDecimal priceOutput;

    @Schema(description = "USD/百万token 缓存读价")
    private BigDecimal priceCacheRead;

    @Schema(description = "USD/百万token 缓存写价")
    private BigDecimal priceCacheWrite;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "下拉排序，小在前")
    private Integer sort;
}

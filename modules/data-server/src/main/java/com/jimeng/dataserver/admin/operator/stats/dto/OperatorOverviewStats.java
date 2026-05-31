package com.jimeng.dataserver.admin.operator.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 运营侧全平台用量总览（跨所有租户聚合）。
 */
@Schema(description = "运营-全平台用量总览")
@Data
public class OperatorOverviewStats {

    @Schema(description = "统计窗口天数")
    private int days;

    @Schema(description = "窗口起始时间（含）")
    private Date start;

    @Schema(description = "窗口结束时间（不含）")
    private Date end;

    @Schema(description = "总调用次数")
    private long totalCalls;

    @Schema(description = "总 token 数")
    private long totalTokens;

    @Schema(description = "总输入 token")
    private long inputTokens;

    @Schema(description = "总输出 token")
    private long outputTokens;

    @Schema(description = "总缓存读取 token")
    private long cacheReadTokens;

    @Schema(description = "总缓存写入 token")
    private long cacheWriteTokens;

    @Schema(description = "总成本（USD）")
    private double totalCostUsd;

    @Schema(description = "窗口内有调用的活跃租户数")
    private int activeTenantCount;

    @Schema(description = "成功调用的平均延迟（ms）")
    private double avgLatencyMs;

    @Schema(description = "成功率 0~1")
    private double successRate;
}

package com.jimeng.dataserver.admin.operator.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 运营侧「各租户用量」主列表中的一行。
 */
@Schema(description = "运营-单租户用量汇总")
@Data
public class TenantUsageRow {

    @Schema(description = "租户标识")
    private String tenantId;

    @Schema(description = "企业名称（按 tenantId JOIN sys_enterprise 得到，可空）")
    private String enterpriseName;

    @Schema(description = "调用次数")
    private long calls;

    @Schema(description = "总 token")
    private long tokens;

    @Schema(description = "输入 token")
    private long inputTokens;

    @Schema(description = "输出 token")
    private long outputTokens;

    @Schema(description = "缓存读取 token")
    private long cacheReadTokens;

    @Schema(description = "缓存写入 token")
    private long cacheWriteTokens;

    @Schema(description = "成本（USD）")
    private double costUsd;

    @Schema(description = "成功调用的平均延迟（ms）")
    private double avgLatencyMs;

    @Schema(description = "成功调用次数")
    private long successCalls;

    @Schema(description = "成功率 0~1")
    private double successRate;
}

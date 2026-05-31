package com.jimeng.dataserver.ai.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 仪表盘总览：全部字段来自 {@code ai_model_call_log} 的真实聚合，无任何模拟数据。
 */
@Schema(description = "仪表盘总览数据")
@Data
public class DashboardOverview {

    @Schema(description = "统计窗口天数")
    private int days;

    @Schema(description = "窗口起始时间（含）")
    private Date start;

    @Schema(description = "窗口结束时间（不含）")
    private Date end;

    @Schema(description = "当前窗口汇总")
    private Totals totals;

    @Schema(description = "上一等长窗口汇总（用于环比）")
    private Totals previous;

    @Schema(description = "按天趋势（已补零，连续）")
    private List<TrendPoint> trend;

    @Schema(description = "模型用量 Top")
    private List<ModelUsage> topModels;

    @Schema(description = "全量模型用量（不截断，用于「查看全部」饼图）")
    private List<ModelUsage> allModels;

    @Schema(description = "最近调用记录")
    private List<RecentCall> recentCalls;

    @Data
    public static class Totals {
        @Schema(description = "调用次数")
        private long calls;
        @Schema(description = "总 token 数")
        private long tokens;
        @Schema(description = "输入 token 数")
        private long inputTokens;
        @Schema(description = "输出 token 数")
        private long outputTokens;
        @Schema(description = "成本（USD）")
        private double costUsd;
        @Schema(description = "成功调用的平均延迟（ms）")
        private double avgLatencyMs;
        @Schema(description = "成功调用次数")
        private long successCalls;
        @Schema(description = "成功率 0~1")
        private double successRate;
    }

    @Data
    public static class TrendPoint {
        @Schema(description = "日期 yyyy-MM-dd")
        private String date;
        private long calls;
        private long tokens;
    }

    @Data
    public static class ModelUsage {
        private String model;
        private long calls;
        private long tokens;
    }

    @Data
    public static class RecentCall {
        private Long id;
        @Schema(description = "发起调用的 Agent ID（可空，历史数据为空）")
        private Long agentId;
        @Schema(description = "发起调用的 Agent 名称（按 agentId JOIN agent 表得到，可空）")
        private String agentName;
        private String model;
        private String provider;
        private Long totalTokens;
        private Integer latencyMs;
        @Schema(description = "0-进行中 1-成功 2-失败")
        private Integer callStatus;
        private Boolean hasTool;
        private String toolNames;
        private String errorMsg;
        private Date createTime;
    }
}

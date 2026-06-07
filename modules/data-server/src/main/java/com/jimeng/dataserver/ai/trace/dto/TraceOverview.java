package com.jimeng.dataserver.ai.trace.dto;

import lombok.Data;

/**
 * 调用日志概览统计（列表页顶部）：时间窗口内的 trace 总数 / 平均耗时 / 错误率。
 */
@Data
public class TraceOverview {

    /** trace 总数。 */
    private long totalCalls;

    /** 平均总耗时，毫秒。 */
    private double avgLatencyMs;

    /** 错误率（0~1）。 */
    private double errorRate;
}

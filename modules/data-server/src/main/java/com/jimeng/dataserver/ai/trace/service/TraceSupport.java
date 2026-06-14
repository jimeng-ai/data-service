package com.jimeng.dataserver.ai.trace.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.dataserver.ai.trace.dto.TraceOverview;
import com.jimeng.persistence.entity.AiTrace;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 调用日志查询的共用逻辑：过滤条件拼装、概览换算、CSV 输出。
 * 租户侧 {@link TraceQueryService} 与运营侧 OperatorTraceService 共用，避免重复。
 */
public final class TraceSupport {

    /** CSV 导出的最大行数兜底，避免全平台海量数据 OOM；超出时由调用方记日志告知截断。 */
    public static final int EXPORT_MAX_ROWS = 100_000;

    private TraceSupport() {
    }

    /**
     * 构造列表/导出共用的过滤条件（按创建时间倒序）。
     *
     * @param tenantId 仅运营侧用于收窄到单个企业；租户侧传 null（拦截器自动注入租户过滤）
     */
    public static LambdaQueryWrapper<AiTrace> buildWrapper(Date start, Date end, String status,
                                                           String keyword, String tenantId, String sceneCode) {
        LambdaQueryWrapper<AiTrace> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(tenantId)) {
            w.eq(AiTrace::getTenantId, tenantId);
        }
        if (StrUtil.isNotBlank(status)) {
            w.eq(AiTrace::getStatus, status);
        }
        if (StrUtil.isNotBlank(sceneCode)) {
            w.eq(AiTrace::getSceneCode, sceneCode);
        }
        if (start != null) {
            w.ge(AiTrace::getCreateTime, start);
        }
        if (end != null) {
            w.lt(AiTrace::getCreateTime, end);
        }
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            // 关键字模糊匹配 trace_id / Agent 名称 / 用户消息。
            w.and(q -> q.like(AiTrace::getTraceId, kw)
                    .or().like(AiTrace::getAgentName, kw)
                    .or().like(AiTrace::getUserMessage, kw));
        }
        w.orderByDesc(AiTrace::getCreateTime);
        return w;
    }

    /** 把聚合 Map 换算成概览 DTO。 */
    public static TraceOverview toOverview(Map<String, Object> row) {
        TraceOverview o = new TraceOverview();
        if (row == null) {
            return o;
        }
        long total = asLong(row.get("totalCalls"));
        long error = asLong(row.get("errorCalls"));
        o.setTotalCalls(total);
        o.setAvgLatencyMs(asDouble(row.get("avgLatencyMs")));
        o.setErrorRate(total > 0 ? (double) error / total : 0d);
        return o;
    }

    /** 把 trace 列表写成 CSV（含表头）。 */
    public static void writeCsv(List<AiTrace> rows, Writer writer, boolean withEnterprise) throws IOException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        // UTF-8 BOM，便于 Excel 正确识别中文
        writer.write('﻿');
        StringBuilder header = new StringBuilder("trace_id,");
        if (withEnterprise) {
            header.append("企业,");
        }
        header.append("Agent,用户消息,状态,步骤数,总耗时(ms),总Token,开始时间");
        writer.write(header.toString());
        writer.write("\n");
        if (rows == null) {
            return;
        }
        for (AiTrace t : rows) {
            StringBuilder sb = new StringBuilder();
            sb.append(csv(t.getTraceId())).append(',');
            if (withEnterprise) {
                sb.append(csv(t.getEnterpriseName())).append(',');
            }
            sb.append(csv(t.getAgentName())).append(',');
            sb.append(csv(t.getUserMessage())).append(',');
            sb.append(csv(t.getStatus())).append(',');
            sb.append(t.getStepCount() == null ? 0 : t.getStepCount()).append(',');
            sb.append(t.getTotalLatencyMs() == null ? 0 : t.getTotalLatencyMs()).append(',');
            sb.append(t.getTotalTokens() == null ? 0 : t.getTotalTokens()).append(',');
            sb.append(csv(t.getStartTime() == null ? "" : fmt.format(t.getStartTime())));
            sb.append("\n");
            writer.write(sb.toString());
        }
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹并转义内部引号。 */
    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? 0d : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}

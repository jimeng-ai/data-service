package com.jimeng.dataserver.ai.stats.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.stats.dto.DashboardOverview;
import com.jimeng.persistence.entity.AiModelCallLog;
import com.jimeng.persistence.mapper.AiModelCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计：基于 {@code ai_model_call_log} 的真实聚合，全部按当前租户隔离。
 *
 * <p>{@code ai_model_call_log} 未纳入多租户拦截器白名单，故所有查询都显式传入
 * {@link TenantContext#get()} 作为 {@code tenant_id} 条件。租户上下文缺失时返回空数据，
 * 而不是泄漏全量数据。
 */
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private static final int MAX_DAYS = 365;
    private static final int TOP_MODELS_LIMIT = 6;
    private static final int RECENT_CALLS_LIMIT = 8;

    private final AiModelCallLogMapper callLogMapper;

    public DashboardOverview overview(int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        String tenantId = TenantContext.get();

        DashboardOverview result = new DashboardOverview();
        result.setDays(window);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        // [start, end) 覆盖今天在内的 window 天；prevStart 为上一等长窗口起点。
        LocalDate startDate = today.minusDays(window - 1L);
        LocalDate prevStartDate = startDate.minusDays(window);

        Date start = Date.from(startDate.atStartOfDay(zone).toInstant());
        Date end = Date.from(today.plusDays(1).atStartOfDay(zone).toInstant());
        Date prevStart = Date.from(prevStartDate.atStartOfDay(zone).toInstant());

        result.setStart(start);
        result.setEnd(end);

        if (tenantId == null || tenantId.isEmpty()) {
            // 没有租户上下文：返回空壳，避免跨租户聚合。
            result.setTotals(emptyTotals());
            result.setPrevious(emptyTotals());
            result.setTrend(fillTrend(List.of(), startDate, today));
            result.setTopModels(List.of());
            result.setRecentCalls(List.of());
            return result;
        }

        result.setTotals(toTotals(callLogMapper.selectOverview(tenantId, start, end)));
        result.setPrevious(toTotals(callLogMapper.selectOverview(tenantId, prevStart, start)));
        result.setTrend(fillTrend(callLogMapper.selectDailyTrend(tenantId, start, end), startDate, today));
        result.setTopModels(toModelUsage(callLogMapper.selectTopModels(tenantId, start, end, TOP_MODELS_LIMIT)));
        result.setRecentCalls(toRecentCalls(callLogMapper.selectRecentCalls(tenantId, RECENT_CALLS_LIMIT)));
        return result;
    }

    private DashboardOverview.Totals emptyTotals() {
        return new DashboardOverview.Totals();
    }

    private DashboardOverview.Totals toTotals(Map<String, Object> row) {
        DashboardOverview.Totals t = new DashboardOverview.Totals();
        if (row == null) {
            return t;
        }
        long calls = asLong(row.get("calls"));
        long success = asLong(row.get("successCalls"));
        t.setCalls(calls);
        t.setTokens(asLong(row.get("tokens")));
        t.setInputTokens(asLong(row.get("inputTokens")));
        t.setOutputTokens(asLong(row.get("outputTokens")));
        t.setCostUsd(asDouble(row.get("costUsd")));
        t.setAvgLatencyMs(asDouble(row.get("avgLatencyMs")));
        t.setSuccessCalls(success);
        t.setSuccessRate(calls > 0 ? (double) success / calls : 0d);
        return t;
    }

    /** 把按天分组的稀疏结果补成 [startDate, today] 连续序列。 */
    private List<DashboardOverview.TrendPoint> fillTrend(List<Map<String, Object>> rows,
                                                         LocalDate startDate, LocalDate today) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object d = row.get("date");
                if (d != null) {
                    byDate.put(String.valueOf(d), row);
                }
            }
        }
        List<DashboardOverview.TrendPoint> out = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            String key = d.toString(); // yyyy-MM-dd
            DashboardOverview.TrendPoint p = new DashboardOverview.TrendPoint();
            p.setDate(key);
            Map<String, Object> row = byDate.get(key);
            if (row != null) {
                p.setCalls(asLong(row.get("calls")));
                p.setTokens(asLong(row.get("tokens")));
            }
            out.add(p);
        }
        return out;
    }

    private List<DashboardOverview.ModelUsage> toModelUsage(List<Map<String, Object>> rows) {
        List<DashboardOverview.ModelUsage> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            DashboardOverview.ModelUsage m = new DashboardOverview.ModelUsage();
            m.setModel(row.get("model") == null ? null : String.valueOf(row.get("model")));
            m.setCalls(asLong(row.get("calls")));
            m.setTokens(asLong(row.get("tokens")));
            out.add(m);
        }
        return out;
    }

    private List<DashboardOverview.RecentCall> toRecentCalls(List<AiModelCallLog> logs) {
        List<DashboardOverview.RecentCall> out = new ArrayList<>();
        if (logs == null) {
            return out;
        }
        for (AiModelCallLog log : logs) {
            DashboardOverview.RecentCall c = new DashboardOverview.RecentCall();
            c.setId(log.getId());
            c.setModel(log.getModel());
            c.setProvider(log.getProvider());
            c.setTotalTokens(log.getTotalTokens() == null ? null : log.getTotalTokens().longValue());
            c.setLatencyMs(log.getLatencyMs());
            c.setCallStatus(log.getCallStatus());
            c.setHasTool(log.getHasTool());
            c.setToolNames(log.getToolNames());
            c.setErrorMsg(log.getErrorMsg());
            c.setCreateTime(log.getCreateTime());
            out.add(c);
        }
        return out;
    }

    private long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double asDouble(Object v) {
        if (v == null) {
            return 0d;
        }
        if (v instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}

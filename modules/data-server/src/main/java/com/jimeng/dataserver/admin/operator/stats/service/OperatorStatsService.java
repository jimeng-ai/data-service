package com.jimeng.dataserver.admin.operator.stats.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.operator.stats.dto.OperatorOverviewStats;
import com.jimeng.dataserver.admin.operator.stats.dto.TenantUsageRow;
import com.jimeng.dataserver.ai.stats.dto.DashboardOverview;
import com.jimeng.dataserver.ai.stats.service.DashboardStatsService;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.mapper.AiModelCallLogMapper;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营侧用量统计：跨租户聚合 {@code ai_model_call_log}，全程 {@link TenantContext#runAsSystem}
 * （该表与 {@code sys_enterprise} 都不在租户白名单内，需绕过租户隔离做平台级聚合）。
 *
 * <p>权限由 {@code OperatorGuard} 在 Controller 层兜底，本类只管聚合。
 */
@Service
@RequiredArgsConstructor
public class OperatorStatsService {

    private static final int MAX_DAYS = 365;

    private final AiModelCallLogMapper callLogMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final DashboardStatsService dashboardStatsService;

    /** 全平台总览（跨所有租户）。 */
    public OperatorOverviewStats overview(int days) {
        int window = clampDays(days);
        Date start = windowStart(window);
        Date end = windowEnd();

        return TenantContext.runAsSystem(() -> {
            Map<String, Object> row = callLogMapper.selectCrossTenantOverview(start, end);
            OperatorOverviewStats s = new OperatorOverviewStats();
            s.setDays(window);
            s.setStart(start);
            s.setEnd(end);
            if (row != null) {
                long calls = asLong(row.get("calls"));
                long success = asLong(row.get("successCalls"));
                s.setTotalCalls(calls);
                s.setTotalTokens(asLong(row.get("tokens")));
                s.setInputTokens(asLong(row.get("inputTokens")));
                s.setOutputTokens(asLong(row.get("outputTokens")));
                s.setCacheReadTokens(asLong(row.get("cacheReadTokens")));
                s.setCacheWriteTokens(asLong(row.get("cacheWriteTokens")));
                s.setTotalCostUsd(asDouble(row.get("costUsd")));
                s.setAvgLatencyMs(asDouble(row.get("avgLatencyMs")));
                s.setActiveTenantCount((int) asLong(row.get("tenantCount")));
                s.setSuccessRate(calls > 0 ? (double) success / calls : 0d);
            }
            return s;
        });
    }

    /** 各租户用量列表（主表）。 */
    public List<TenantUsageRow> byTenant(int days) {
        int window = clampDays(days);
        Date start = windowStart(window);
        Date end = windowEnd();

        return TenantContext.runAsSystem(() -> {
            List<Map<String, Object>> rows = callLogMapper.selectOverviewByTenant(start, end);
            Map<String, String> names = loadTenantNames();
            List<TenantUsageRow> out = new ArrayList<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    String tenantId = row.get("tenantId") == null ? null : String.valueOf(row.get("tenantId"));
                    long calls = asLong(row.get("calls"));
                    long success = asLong(row.get("successCalls"));
                    TenantUsageRow r = new TenantUsageRow();
                    r.setTenantId(tenantId);
                    r.setEnterpriseName(tenantId == null ? null : names.get(tenantId));
                    r.setCalls(calls);
                    r.setTokens(asLong(row.get("tokens")));
                    r.setInputTokens(asLong(row.get("inputTokens")));
                    r.setOutputTokens(asLong(row.get("outputTokens")));
                    r.setCacheReadTokens(asLong(row.get("cacheReadTokens")));
                    r.setCacheWriteTokens(asLong(row.get("cacheWriteTokens")));
                    r.setCostUsd(asDouble(row.get("costUsd")));
                    r.setAvgLatencyMs(asDouble(row.get("avgLatencyMs")));
                    r.setSuccessCalls(success);
                    r.setSuccessRate(calls > 0 ? (double) success / calls : 0d);
                    out.add(r);
                }
            }
            return out;
        });
    }

    /** 单租户下钻明细（趋势 / 模型分布 / 最近调用），复用仪表盘统计逻辑。 */
    public DashboardOverview tenantDetail(String tenantId, int days) {
        return TenantContext.runAsSystem(() -> dashboardStatsService.overview(tenantId, clampDays(days)));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> loadTenantNames() {
        List<SysEnterprise> ents = sysEnterpriseMapper.selectList(null);
        Map<String, String> map = new HashMap<>();
        if (ents != null) {
            for (SysEnterprise e : ents) {
                if (e.getTenantId() != null) {
                    map.put(e.getTenantId(), e.getName());
                }
            }
        }
        return map;
    }

    private int clampDays(int days) {
        return Math.max(1, Math.min(days, MAX_DAYS));
    }

    /** 窗口起点：覆盖今天在内的 window 天的零点。 */
    private Date windowStart(int window) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate startDate = LocalDate.now(zone).minusDays(window - 1L);
        return Date.from(startDate.atStartOfDay(zone).toInstant());
    }

    /** 窗口终点（不含）：明天零点。 */
    private Date windowEnd() {
        ZoneId zone = ZoneId.systemDefault();
        return Date.from(LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant());
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

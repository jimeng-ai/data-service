package com.jimeng.dataserver.ai.stats.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.stats.dto.DashboardOverview;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AiModelCallLog;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiModelCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final int TOP_AGENTS_LIMIT = 8;
    private static final int RECENT_CALLS_LIMIT = 8;
    /** 「查看全部」饼图需要全量模型，给一个足够大的上限即可（单租户模型数远小于此）。 */
    private static final int ALL_MODELS_LIMIT = 100;

    private final AiModelCallLogMapper callLogMapper;
    private final AgentMapper agentMapper;

    public DashboardOverview overview(int days) {
        return overview(TenantContext.get(), days);
    }

    /**
     * 指定租户的总览统计。运营侧下钻复用本方法（传入目标租户 id），
     * 调用方需用 {@code TenantContext.runAsSystem} 包裹，使 agent 名称解析不被错误的租户上下文过滤。
     */
    public DashboardOverview overview(String tenantId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));

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
            result.setAllModels(List.of());
            result.setTopAgents(List.of());
            result.setRecentCalls(List.of());
            return result;
        }

        result.setTotals(toTotals(callLogMapper.selectOverview(tenantId, start, end)));
        result.setPrevious(toTotals(callLogMapper.selectOverview(tenantId, prevStart, start)));
        result.setTrend(fillTrend(callLogMapper.selectDailyTrend(tenantId, start, end), startDate, today));
        // 全量模型用量（按调用次数倒序）：allModels 给「查看全部」饼图，topModels 取前 N。
        List<DashboardOverview.ModelUsage> allModels =
                toModelUsage(callLogMapper.selectTopModels(tenantId, start, end, ALL_MODELS_LIMIT));
        result.setAllModels(allModels);
        result.setTopModels(allModels.size() > TOP_MODELS_LIMIT
                ? new ArrayList<>(allModels.subList(0, TOP_MODELS_LIMIT))
                : allModels);
        result.setTopAgents(toAgentUsage(callLogMapper.selectTopAgents(tenantId, start, end, TOP_AGENTS_LIMIT)));
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
        // 先收集本批次出现的 agentId，批量查 agent 名称，避免 N+1。
        Map<Long, String> agentNames = resolveAgentNames(logs);
        for (AiModelCallLog log : logs) {
            DashboardOverview.RecentCall c = new DashboardOverview.RecentCall();
            c.setId(log.getId());
            c.setAgentId(log.getAgentId());
            c.setAgentName(log.getAgentId() == null ? null : agentNames.get(log.getAgentId()));
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

    private List<DashboardOverview.AgentUsage> toAgentUsage(List<Map<String, Object>> rows) {
        List<DashboardOverview.AgentUsage> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Set<Long> agentIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Long id = asLongObj(row.get("agentId"));
            if (id != null) {
                agentIds.add(id);
            }
        }
        Map<Long, String> names = batchAgentNames(agentIds);
        for (Map<String, Object> row : rows) {
            DashboardOverview.AgentUsage a = new DashboardOverview.AgentUsage();
            Long id = asLongObj(row.get("agentId"));
            a.setAgentId(id);
            a.setAgentName(id == null ? null : names.get(id));
            a.setCalls(asLong(row.get("calls")));
            a.setTokens(asLong(row.get("tokens")));
            a.setCostUsd(asDouble(row.get("costUsd")));
            out.add(a);
        }
        return out;
    }

    /** 批量把 agentId 解析成 Agent 名称；agent 已删除或查不到时该 id 不在返回 map 中。 */
    private Map<Long, String> resolveAgentNames(List<AiModelCallLog> logs) {
        Set<Long> agentIds = new HashSet<>();
        for (AiModelCallLog log : logs) {
            if (log.getAgentId() != null) {
                agentIds.add(log.getAgentId());
            }
        }
        return batchAgentNames(agentIds);
    }

    private Map<Long, String> batchAgentNames(Set<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Agent agent : agentMapper.selectBatchIds(agentIds)) {
            names.put(agent.getId(), agent.getName());
        }
        return names;
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

    private Long asLongObj(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
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

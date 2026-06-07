package com.jimeng.dataserver.admin.operator.trace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.trace.dto.TraceOverview;
import com.jimeng.dataserver.ai.trace.dto.TraceReplay;
import com.jimeng.dataserver.ai.trace.service.TraceReplayService;
import com.jimeng.dataserver.ai.trace.service.TraceSupport;
import com.jimeng.persistence.entity.AiTrace;
import com.jimeng.persistence.entity.AiTraceStep;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.mapper.AiTraceMapper;
import com.jimeng.persistence.mapper.AiTraceStepMapper;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Writer;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用日志查询（运营侧，jm-operator，跨租户）。
 *
 * <p>{@code ai_trace} / {@code ai_trace_step} 在租户白名单内，正常查询会被拦截器按当前租户过滤；
 * 运营要看全平台，因此全程 {@link TenantContext#runAsSystem} 绕过租户隔离，并支持可选 {@code tenantId}
 * 收窄到单个企业。列表/详情会按 tenant_id 回填企业名称。权限由 {@code OperatorGuard} 在 Controller 兜底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorTraceService {

    private final AiTraceMapper aiTraceMapper;
    private final AiTraceStepMapper aiTraceStepMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final TraceReplayService traceReplayService;

    /** 跨租户分页列表，回填企业名称。 */
    public Page<AiTrace> page(int page, int size, Date start, Date end,
                              String status, String keyword, String tenantId) {
        return TenantContext.runAsSystem(() -> {
            Page<AiTrace> p = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200));
            Page<AiTrace> result = aiTraceMapper.selectPage(p,
                    TraceSupport.buildWrapper(start, end, status, keyword, tenantId));
            Map<String, String> names = loadTenantNames();
            for (AiTrace t : result.getRecords()) {
                t.setEnterpriseName(names.get(t.getTenantId()));
            }
            return result;
        });
    }

    /** 跨租户详情（含有序步骤）。 */
    public AiTrace detail(String traceId) {
        return TenantContext.runAsSystem(() -> {
            AiTrace trace = aiTraceMapper.selectOne(
                    new LambdaQueryWrapper<AiTrace>().eq(AiTrace::getTraceId, traceId).last("limit 1"));
            if (trace == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该调用链路: " + traceId);
            }
            trace.setEnterpriseName(loadTenantNames().get(trace.getTenantId()));
            List<AiTraceStep> steps = aiTraceStepMapper.selectList(
                    new LambdaQueryWrapper<AiTraceStep>()
                            .eq(AiTraceStep::getTraceId, traceId)
                            .orderByAsc(AiTraceStep::getStepIndex));
            trace.setSteps(steps);
            return trace;
        });
    }

    /** 跨租户可视化回放：runAsSystem 加载 trace（含企业名 + 步骤）后交给共用还原逻辑。 */
    public TraceReplay replay(String traceId) {
        return TenantContext.runAsSystem(() -> traceReplayService.buildReplay(detail(traceId)));
    }

    /** 全平台（或单企业）概览统计。 */
    public TraceOverview overview(Date start, Date end, String tenantId) {
        return TenantContext.runAsSystem(() ->
                TraceSupport.toOverview(aiTraceMapper.selectOverview(tenantId, start, end)));
    }

    /** 按当前筛选导出 CSV（含企业列），封顶 {@link TraceSupport#EXPORT_MAX_ROWS} 行。 */
    public void exportCsv(Date start, Date end, String status, String keyword,
                          String tenantId, Writer writer) throws Exception {
        List<AiTrace> rows = TenantContext.runAsSystem(() -> {
            Page<AiTrace> p = new Page<>(1, TraceSupport.EXPORT_MAX_ROWS, false);
            List<AiTrace> list = aiTraceMapper.selectPage(p,
                    TraceSupport.buildWrapper(start, end, status, keyword, tenantId)).getRecords();
            Map<String, String> names = loadTenantNames();
            for (AiTrace t : list) {
                t.setEnterpriseName(names.get(t.getTenantId()));
            }
            return list;
        });
        if (rows.size() >= TraceSupport.EXPORT_MAX_ROWS) {
            log.warn("运营调用日志 CSV 导出达到上限 {} 行，结果可能被截断", TraceSupport.EXPORT_MAX_ROWS);
        }
        TraceSupport.writeCsv(rows, writer, true);
    }

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
}

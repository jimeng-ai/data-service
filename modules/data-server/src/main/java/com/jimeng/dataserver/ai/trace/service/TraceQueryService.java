package com.jimeng.dataserver.ai.trace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.trace.dto.TraceOverview;
import com.jimeng.persistence.entity.AiTrace;
import com.jimeng.persistence.entity.AiTraceStep;
import com.jimeng.persistence.mapper.AiTraceMapper;
import com.jimeng.persistence.mapper.AiTraceStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Writer;
import java.util.Date;
import java.util.List;

/**
 * 调用日志查询（租户侧，jm-agent-front）。
 *
 * <p>{@code ai_trace} / {@code ai_trace_step} 在多租户白名单内，所有查询由 MyBatis-Plus 租户拦截器
 * 自动注入 {@code tenant_id} 过滤——本类无需显式传租户，企业用户只能看到本租户的 trace。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TraceQueryService {

    private final AiTraceMapper aiTraceMapper;
    private final AiTraceStepMapper aiTraceStepMapper;
    private final PermissionResolver permissionResolver;

    /** 分页列表。trace「按人私有」：成员只看自己（user_id==自身）的调用链路，超管看本租户全部。 */
    public Page<AiTrace> page(int page, int size, Date start, Date end, String status, String keyword,
                              String sceneCode) {
        Page<AiTrace> p = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200));
        String owner = permissionResolver.ownerScopeOrNull();
        return aiTraceMapper.selectPage(p, TraceSupport.buildWrapper(start, end, status, keyword, null, sceneCode)
                .eq(owner != null, AiTrace::getUserId, owner));
    }

    /** trace 详情（含有序步骤）。非属主（且非超管）按「不存在」处理，避免越权读他人对话链路。 */
    public AiTrace detail(String traceId) {
        String owner = permissionResolver.ownerScopeOrNull();
        AiTrace trace = aiTraceMapper.selectOne(
                new LambdaQueryWrapper<AiTrace>().eq(AiTrace::getTraceId, traceId)
                        .eq(owner != null, AiTrace::getUserId, owner)
                        .last("limit 1"));
        if (trace == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该调用链路: " + traceId);
        }
        List<AiTraceStep> steps = aiTraceStepMapper.selectList(
                new LambdaQueryWrapper<AiTraceStep>()
                        .eq(AiTraceStep::getTraceId, traceId)
                        .orderByAsc(AiTraceStep::getStepIndex));
        trace.setSteps(steps);
        return trace;
    }

    /** 概览统计（成员仅统计本人，超管统计本租户全部）。 */
    public TraceOverview overview(Date start, Date end) {
        return TraceSupport.toOverview(
                aiTraceMapper.selectOverview(null, permissionResolver.ownerScopeOrNull(), start, end));
    }

    /** 按当前筛选导出 CSV（不分页，封顶 {@link TraceSupport#EXPORT_MAX_ROWS} 行）。 */
    public void exportCsv(Date start, Date end, String status, String keyword, String sceneCode, Writer writer)
            throws Exception {
        Page<AiTrace> p = new Page<>(1, TraceSupport.EXPORT_MAX_ROWS, false);
        String owner = permissionResolver.ownerScopeOrNull();
        List<AiTrace> rows = aiTraceMapper.selectPage(p,
                        TraceSupport.buildWrapper(start, end, status, keyword, null, sceneCode)
                        .eq(owner != null, AiTrace::getUserId, owner))
                .getRecords();
        if (rows.size() >= TraceSupport.EXPORT_MAX_ROWS) {
            log.warn("调用日志 CSV 导出达到上限 {} 行，结果可能被截断", TraceSupport.EXPORT_MAX_ROWS);
        }
        TraceSupport.writeCsv(rows, writer, false);
    }
}

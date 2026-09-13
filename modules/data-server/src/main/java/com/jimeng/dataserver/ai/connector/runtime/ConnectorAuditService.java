package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditQuery;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditView;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.web.MdcContextFilter;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.ConnectorAudit;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.ConnectorAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连接器使用留痕：谁、哪个 Agent、哪次运行、对哪条连接做了什么、成没成、花了多久。
 *
 * <h3>为什么单独建表，而不是寄生在 ai_trace_step 上</h3>
 * {@code TraceRecorder.recordStep} 有三个对审计致命的性质：
 * <ul>
 *   <li>{@code step_index} 是<b>读-改-写无锁</b>的（先 loadOrInitHeader 拿 stepCount，再 insert，
 *       最后 update 头表），同一 trace 并发记步会拿到重复序号，而 {@code ai_trace_step} 上没有唯一约束</li>
 *   <li>每记一步 = 1 次 select + 1 次 insert + 1 次 update 头表，<b>写放大三倍</b></li>
 *   <li>业务维度只能塞进 metadata 的 JSON 里，按 connector_id 检索要全表扫</li>
 * </ul>
 * 审计是「出事时唯一能回溯的东西」，不该建立在一个会丢步、会重号的载体上。
 *
 * <h3>审计失败不能拖垮主流程</h3>
 * 但也不能静默——所以失败走 {@code log.error} 并带上足以重建这条记录的字段。
 * 这是一个刻意的取舍：宁可丢一条审计，也不要因为审计表写不进去而让客户的查询失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorAuditService {

    /**
     * 语句文本的落库上限。超出截断——审计要的是「查了什么」，不是把客户的 SQL 原样存成一份副本。
     * 4000 是个经验值：足够覆盖绝大多数模型写的查询，又不至于让这张表膨胀得比业务表还快。
     */
    private static final int STATEMENT_MAX = 4000;

    /** 同理，错误详情也要封顶。它已经是脱敏文案，但脱敏不等于短。 */
    private static final int ERROR_DETAIL_MAX = 1000;

    /** 查询时一次最多返回多少条。审计表只增不减，没有上限的列表迟早会把某个页面拖垮。 */
    private static final int PAGE_MAX = 200;

    private final ConnectorAuditMapper auditMapper;
    private final AgentMapper agentMapper;

    public void record(ConnectorInstance inst, Long agentId, Capability cap, String operation,
                       String statementText, Integer rowCount, long elapsedMs,
                       boolean success, ConnectorErrorCode errorCode, String errorDetail) {
        try {
            ConnectorAudit row = new ConnectorAudit();
            row.setTenantId(inst == null ? null : inst.tenantId());
            row.setConnectorId(inst == null ? null : inst.id());
            row.setConnectorName(inst == null ? null : inst.name());
            row.setAgentId(agentId);
            row.setTraceId(MDC.get(MdcContextFilter.MDC_TRACE_ID));
            row.setCapability(cap == null ? null : cap.name());
            row.setOperation(operation);
            row.setStatementText(truncate(statementText, STATEMENT_MAX));
            row.setRowCount(rowCount);
            // 耗时用 int 存：超过 int 上限（约 24 天）的单次调用不存在，真出现了说明是计时逻辑坏了。
            row.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
            row.setSuccess(success);
            row.setErrorCode(errorCode == null ? null : errorCode.name());
            // errorDetail 必须是 ConnectorException.getSafeDetail()，绝不能是原始异常 message——
            // 这张表会被管理台展示给客户看。
            row.setErrorDetail(truncate(errorDetail, ERROR_DETAIL_MAX));
            auditMapper.insert(row);
        } catch (Exception e) {
            // 把足以重建这条记录的字段打进日志，这样即使表写不进去也还有一条线索。
            log.error("连接器审计写入失败（主流程不受影响） connectorId={} agentId={} cap={} op={} success={} code={}",
                    inst == null ? null : inst.id(), agentId, cap, operation, success, errorCode, e);
        }
    }

    // ================================================================ 查询

    /**
     * 分页查询使用记录。
     *
     * <p>回答产品方案第 10 节那块「使用记录」要回答的问题：<b>这个连接被谁、在什么时候、
     * 用来做了什么</b>。它的价值不只是合规——无人值守的东西没人盯着，出问题时
     * 「能不能看到发生过什么」决定了排查是十分钟还是三天。
     *
     * <h3>租户隔离是自动的</h3>
     * {@code connector_audit} 在 {@code TENANT_AWARE_TABLES} 白名单里，MyBatis-Plus 的租户
     * 拦截器会自动注入 {@code tenant_id}。所以本方法<b>刻意不写</b> {@code eq(tenantId)}——
     * 与仓库其它地方一致，写两遍会在将来改白名单时分叉。
     *
     * <h3>索引</h3>
     * 带上 {@code connectorId} 时走 {@code (tenant_id, connector_id, create_time)} 这条复合索引，
     * 最快；只按租户查会退化成「索引前缀 + filesort」。当前数据量下可接受，
     * <b>但界面默认应当带上连接筛选</b>，别养成全表翻页的习惯。
     */
    public Page<ConnectorAuditView> query(ConnectorAuditQuery q) {
        ConnectorAuditQuery cond = q == null ? new ConnectorAuditQuery() : q;
        int page = cond.getPage() == null ? 1 : Math.max(1, cond.getPage());
        int size = cond.getSize() == null ? 20 : Math.min(Math.max(1, cond.getSize()), PAGE_MAX);

        LambdaQueryWrapper<ConnectorAudit> w = new LambdaQueryWrapper<ConnectorAudit>()
                .eq(cond.getConnectorId() != null, ConnectorAudit::getConnectorId, cond.getConnectorId())
                .eq(cond.getAgentId() != null, ConnectorAudit::getAgentId, cond.getAgentId())
                .eq(cond.getSuccess() != null, ConnectorAudit::getSuccess, cond.getSuccess())
                .eq(cond.getCapability() != null && !cond.getCapability().isBlank(),
                        ConnectorAudit::getCapability, cond.getCapability())
                .ge(cond.getStart() != null, ConnectorAudit::getCreateTime, cond.getStart())
                .le(cond.getEnd() != null, ConnectorAudit::getCreateTime, cond.getEnd())
                .orderByDesc(ConnectorAudit::getCreateTime);

        Page<ConnectorAudit> rows = auditMapper.selectPage(new Page<>(page, size), w);

        Map<Long, String> agentNames = resolveAgentNames(rows.getRecords());
        Page<ConnectorAuditView> out = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        out.setRecords(rows.getRecords().stream().map(r -> toView(r, agentNames)).toList());
        return out;
    }

    /** 批量解析 Agent 名，避免逐行查（一页 200 行就是 200 次查询）。 */
    private Map<Long, String> resolveAgentNames(List<ConnectorAudit> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConnectorAudit r : rows) {
            if (r.getAgentId() != null) ids.add(r.getAgentId());
        }
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> out = new LinkedHashMap<>();
        // agent 在租户白名单里，这里同样不写 tenant 条件。
        for (Agent a : agentMapper.selectBatchIds(ids)) {
            out.put(a.getId(), a.getName());
        }
        return out;
    }

    private static ConnectorAuditView toView(ConnectorAudit r, Map<Long, String> agentNames) {
        return ConnectorAuditView.builder()
                .id(str(r.getId()))
                .time(r.getCreateTime())
                .connectorId(str(r.getConnectorId()))
                .connectorName(r.getConnectorName())
                .agentId(str(r.getAgentId()))
                // Agent 被删掉之后审计记录仍在（它记的是历史事实，不该跟着消失），此时名字取不到，
                // 界面按「已删除的 Agent」展示即可。
                .agentName(r.getAgentId() == null ? null : agentNames.get(r.getAgentId()))
                .capability(r.getCapability())
                .operation(r.getOperation())
                .traceId(r.getTraceId())
                .rowCount(r.getRowCount())
                .elapsedMs(r.getElapsedMs())
                .success(r.getSuccess())
                .errorCode(r.getErrorCode())
                .errorDetail(r.getErrorDetail())
                .statementText(r.getStatementText())
                .build();
    }

    private static String str(Long v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        // 显式标记截断：否则后人查审计时会以为客户真的写了一条正好 4000 字符的 SQL。
        return s.substring(0, max) + "…[已截断]";
    }
}

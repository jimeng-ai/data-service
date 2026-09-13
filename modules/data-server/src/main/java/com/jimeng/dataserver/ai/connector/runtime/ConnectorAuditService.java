package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.web.MdcContextFilter;
import com.jimeng.persistence.entity.ConnectorAudit;
import com.jimeng.persistence.mapper.ConnectorAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

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

    private final ConnectorAuditMapper auditMapper;

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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        // 显式标记截断：否则后人查审计时会以为客户真的写了一条正好 4000 字符的 SQL。
        return s.substring(0, max) + "…[已截断]";
    }
}

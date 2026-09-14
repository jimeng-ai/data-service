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

    // ================================================================ 动作名

    /**
     * ★ <b>管理面</b>（平台自己发起的访问）在 {@code operation} 上的保留前缀。
     *
     * <h3>为什么非要和 Agent 那套名字分开</h3>
     * 客户的 DBA 迟早会在<b>他自己的</b>数据库审计里看到我们打过去的查询，然后来问一句
     * 「这些是谁发的」。那时要分得清两类完全不同的事：
     * <ul>
     *   <li><b>Agent 在替人问数</b>（{@code conn_query} / {@code conn_catalog} …）——
     *       由一次具体的对话触发，有 Agent、有 trace，一问一次。</li>
     *   <li><b>平台在剖析这个库</b>（刷新结构快照、语义层推导与采样验证）——
     *       没有 Agent，由管理面或后台任务触发，一次可能是几百条查询。</li>
     * </ul>
     * 两者混用同一套动作名，{@code connector_audit} 就回答不了这个问题：
     * 客户看到的会是「你们的 Agent 昨晚半夜跑了 600 次查询」，而事实是没有任何一次对话发生过。
     * 说不清楚的审计等于没有审计。
     *
     * <p>{@code ConnectorGateway.executeAsPlatform} 会<b>强制</b>动作名以此开头——
     * 靠约定不行，约定只在写代码的人记得的时候成立。
     */
    public static final String PLATFORM_OP_PREFIX = "platform.";

    /** 刷新结构快照：1 次 catalog + 最多 200 次 describe，全打在客户库的 {@code information_schema} 上。 */
    public static final String OP_SCHEMA_REFRESH = PLATFORM_OP_PREFIX + "schema_refresh";

    /**
     * 语义层的采样探查（S3：对推导出来的表关系做 LEFT JOIN 包含性校验）。
     *
     * <p><b>名字先登记在这里，是为了别让它将来被随手起成另一个形状。</b>它与
     * {@link #OP_SCHEMA_REFRESH} 有一个要紧的区别：那个读的是元数据，这个会真的
     * {@code SELECT} 客户的<b>业务数据</b>做抽样。DBA 分得清这两者，比分清平台与 Agent 更要紧。
     */
    public static final String OP_SEMANTIC_PROBE = PLATFORM_OP_PREFIX + "semantic_probe";

    /**
     * 语义层的<b>值域采集</b>（S4：对够格的列取真实取值集合）。
     *
     * <h3>★ 为什么它必须和 {@link #OP_SEMANTIC_PROBE} 分开，哪怕两者都是「平台在剖析这个库」</h3>
     * 因为<b>出库的东西不是一个量级</b>，而这正是客户的 DBA 唯一会追问的那件事：
     * <ul>
     *   <li>{@code semantic_probe}（S3）读的是<b>派生统计</b>——{@code COUNT}、
     *       {@code COUNT(DISTINCT)}、包含率。离开客户库的只有数字，没有一个业务值。
     *       对应数据出库档位的<b>第 2 档</b>。</li>
     *   <li>{@code semantic_values}（S4）读的是<b>真实取值</b>——{@code SELECT DISTINCT status}
     *       的结果会原样写进我们的库、并注入模型上下文。对应<b>第 3 档</b>，而那一档默认是关的。</li>
     * </ul>
     * 两者共用一个动作名，{@code connector_audit} 就只能靠<b>读 statement_text</b> 来区分——
     * 而那张表是要给客户看的，让人逐条读 SQL 才能回答「你们到底把我的数据取走了没有」，
     * 等于没有回答。一个分不出敏感级别的审计，在最需要它的那次对话里是没有用的。
     *
     * <h3>今天的接线状态（<b>如实记下来，别当成已完成</b>）</h3>
     * {@code SemanticValueProfiler} 内部每条语句的审计目前<b>仍然</b>写的是
     * {@link #OP_SEMANTIC_PROBE}。<b>现已改掉</b>：{@code SemanticValueProfiler} 的逐条语句审计
     * 用的就是本常量，所以按 operation 过滤即可回答「平台读过我的业务数据值吗」，
     * 不必再去读语句原文。
     */
    public static final String OP_SEMANTIC_VALUES = PLATFORM_OP_PREFIX + "semantic_values";

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

    /**
     * 管理面<b>阶段级</b>留痕：一整轮后台剖析结束时记一条，而不是每条语句记一条。
     *
     * <h3>它和 {@link #record} 回答的不是同一个问题</h3>
     * {@code record} 回答「这一条语句是谁发的」，它的每一行都对应客户库里真实发生过的一次访问。
     * 本方法回答「<b>这一批</b>访问是什么性质、一共多少次、花了多久」——当一轮剖析会打出几百条
     * 语句时，前者是几百行细账，后者是那张发票。客户的 DBA 先看发票，才决定要不要翻细账。
     *
     * <p>所以这里<b>没有 statement_text</b>：填一段拼出来的摘要进那一列，会让人以为客户库上真的
     * 执行过那么一条语句。宁可留空，也不要往一个「客户执行过的 SQL」列里写我们自己编的字符串。
     *
     * <h3>为什么不走 {@code ConnectorInstance}</h3>
     * {@link #record} 的入参是 {@code ConnectorInstance}，拿到它要先解密一次凭据
     *（{@code ConnectorInstanceLoader.load}）。为了写一条审计而解一次密，既没必要，又凭空多出
     * 一条「凭据解不开 ⇒ 审计丢了」的失败路径。审计要的只有 id / 租户 / 名字这三样，直接收。
     *
     * <p>与 {@code ConnectorGateway.executeAsPlatform} 同一条纪律：{@code operation} 必须以
     * {@link #PLATFORM_OP_PREFIX} 开头，不合规的<b>拒绝落库</b>并报错。靠约定不行——
     * 约定只在写代码的人记得的时候成立，而一条混进 Agent 动作名里的平台记录会让整张表说不清话。
     *
     * @param summary 给人看的一句话摘要，写进 {@code error_detail} 列（那一列本来就是「给客户看的、
     *                已经脱敏的说明文字」，成功时同样可以用来承载结论）。<b>绝不放客户数据</b>
     */
    public void recordPlatformStage(Long connectorId, String tenantId, String connectorName,
                                    Capability cap, String operation, String summary,
                                    Integer statementCount, long elapsedMs, boolean success) {
        if (operation == null || !operation.startsWith(PLATFORM_OP_PREFIX)) {
            log.error("平台阶段审计的动作名没有 {} 前缀，本条拒绝落库 connectorId={} op={}",
                    PLATFORM_OP_PREFIX, connectorId, operation);
            return;
        }
        try {
            ConnectorAudit row = new ConnectorAudit();
            row.setTenantId(tenantId);
            row.setConnectorId(connectorId);
            row.setConnectorName(connectorName);
            // agentId 恒为空：平台自己发起的剖析背后没有任何一次对话。
            row.setAgentId(null);
            row.setTraceId(MDC.get(MdcContextFilter.MDC_TRACE_ID));
            row.setCapability(cap == null ? null : cap.name());
            row.setOperation(operation);
            // statement_text 留空，理由见上。
            row.setStatementText(null);
            row.setRowCount(statementCount);
            row.setElapsedMs((int) Math.min(Math.max(elapsedMs, 0L), Integer.MAX_VALUE));
            row.setSuccess(success);
            row.setErrorDetail(truncate(summary, ERROR_DETAIL_MAX));
            auditMapper.insert(row);
        } catch (Exception e) {
            log.error("连接器阶段审计写入失败（主流程不受影响） connectorId={} op={} success={} summary={}",
                    connectorId, operation, success, summary, e);
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

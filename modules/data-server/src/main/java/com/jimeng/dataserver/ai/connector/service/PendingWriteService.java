package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.dataserver.web.MdcContextFilter;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.ConnectorPendingWrite;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.ConnectorPendingWriteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写操作审批流：{@code connection.write_policy = REQUIRE_APPROVAL} 那一档的全部实现。
 *
 * <h3>这一档为什么最重要</h3>
 * 产品方案第 8 节对「写自动」的风险评注是<b>业务事故</b>，对「写需审批」是<b>有人兜底</b>。
 * {@code AUTO} 的失败是<b>无人察觉</b>的：模型写错一条 UPDATE，数据就改了，没人知道。
 * 这一档把「生成操作」和「执行操作」拆开，中间插一个人——所以它才是真正该被推荐的默认开放方式，
 * 它的实现质量决定整个写能力敢不敢开。
 *
 * <h3>★ approve 不用异常表达「没执行」</h3>
 * 并发抢锁失败、已过期、执行失败，三种情况都<b>正常返回</b>，结果写在
 * {@link PendingWriteView#getStatus()} 上。只有「这条记录压根不存在（或不属于本租户）」才抛。
 *
 * <p>理由是这三种情况都产生了<b>必须落库的状态变化</b>，调用方需要拿到变化后的那一行，
 * 而不是一个只有文案的异常。代价是调用方<b>必须看 status</b>——把「approve 返回了」
 * 读成「已经批准并执行了」，就会向操作者报一个假的成功。
 *
 * <h3>并发：唯一的防线是「带条件的 update」</h3>
 * 两个超管同时点批准，绝不能执行两次。判据是
 * {@code UPDATE ... WHERE id = ? AND status = 'PENDING'} 的返回行数：拿到 1 才算抢到。
 * 换成「先 select 判 status、再 update」，两个线程会双双通过判断然后各执行一次写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingWriteService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_FAILED = "FAILED";

    /** 审批执行时写进 {@code connector_audit.operation}，与 Agent 自己发起的写操作区分开。 */
    private static final String OPERATION_APPROVED_WRITE = "approved_write";

    /**
     * 语句长度上限。<b>超了直接拒收，不截断。</b>
     *
     * <p>因为批准时<b>就是照 {@code statement_text} 这一列去执行</b>的：截断过的语句要么语法错报一堆
     * 看不懂的错，要么更坏——{@code WHERE} 少掉一半，变成一条范围完全不同的更新。
     * 审计表可以截断（那是事后读的证据），这张表不行（这是执行源）。
     */
    private static final int STATEMENT_MAX = 16000;

    /** 原因文案封顶。它已经是脱敏文案，但脱敏不等于短。 */
    private static final int ERROR_DETAIL_MAX = 1000;

    /** 一页最多多少条。这张表只增不减，没有上限的列表迟早把某个页面拖垮。 */
    private static final int PAGE_MAX = 200;

    private final ConnectorPendingWriteMapper pendingWriteMapper;
    private final AgentMapper agentMapper;
    private final ConnectorGateway gateway;
    private final ConnectorProperties properties;

    // ================================================================ 提交

    /**
     * 提交一条待审批写请求。
     *
     * <p>调用方（工具层）此前<b>已经跑过 {@code WriteSqlGuard}</b>，{@code operation} 和
     * {@code targetTable} 就是护栏解析出来的结果——这里不再解析一遍：同一条语句解析两次、
     * 两处结论万一不一致，人看到的和实际执行的就对不上了。
     *
     * @return 落库后的 id
     */
    public Long submit(Long connectorId, String connectorName, String tenantId, Long agentId,
                       String operation, String targetTable, String statementText,
                       Integer estimatedRows) {
        if (tenantId == null || tenantId.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "当前请求缺少租户上下文，无法提交写请求");
        }
        if (agentId == null) {
            // ★ 不允许匿名写请求进队列。批准时要按「当初那个 Agent」判权限（见 approve 的注释）；
            //   没有 agentId 就只剩下「按点批准的人判」这一条路，那等于超管点一下就绕过了 Agent 授权。
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "当前会话没有绑定 Agent，无法提交写请求。写操作必须由具体 Agent 发起");
        }
        if (statementText == null || statementText.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "写请求的语句不能为空");
        }
        if (statementText.length() > STATEMENT_MAX) {
            // 见 STATEMENT_MAX 的注释：这一列是执行源，截断后执行的就不是人批准的那条语句了。
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条语句超过 " + STATEMENT_MAX + " 字符，平台不受理。"
                            + "请拆成多条范围更小的操作——过长的写语句本身也很难被人审出对错");
        }

        ConnectorPendingWrite row = new ConnectorPendingWrite();
        row.setTenantId(tenantId);
        row.setConnectorId(connectorId);
        row.setConnectorName(connectorName);
        row.setAgentId(agentId);
        // 串起「模型当时为什么要写这一条」：审批的人要能顺着它回到那段对话。
        row.setTraceId(MDC.get(MdcContextFilter.MDC_TRACE_ID));
        row.setOperation(operation);
        row.setTargetTable(targetTable);
        row.setStatementText(statementText);
        // 估不出来就留空——估不出来不该阻止一条写请求进入队列。
        row.setEstimatedRows(estimatedRows);
        row.setStatus(STATUS_PENDING);
        // 取不到就留空：Agent 跑在异步线程上时 user-id 不一定捎带得到，
        // 而「提交人是谁」不是授权依据（授权依据是 agentId），缺了不影响正确性。
        Long submitter = AdminRequestContext.findUserIdOrNull();
        row.setSubmittedBy(submitter == null ? null : String.valueOf(submitter));
        row.setExpiresAt(new Date(System.currentTimeMillis() + ttlMillis()));
        pendingWriteMapper.insert(row);

        log.info("写请求已进入待审批队列 id={} connector={} agentId={} op={} table={}",
                row.getId(), connectorName, agentId, operation, targetTable);
        return row.getId();
    }

    // ================================================================ 查询

    /**
     * 分页查询。租户隔离由 {@code JimengTenantLineHandler} 自动注入
     * （{@code connector_pending_write} 在白名单里），所以这里<b>刻意不写</b> {@code eq(tenantId)}——
     * 与仓库其它地方一致，写两遍会在将来改白名单时分叉。
     */
    public Page<PendingWriteView> query(PendingWriteQuery q) {
        PendingWriteQuery cond = q == null ? new PendingWriteQuery() : q;
        int page = cond.getPage() == null ? 1 : Math.max(1, cond.getPage());
        // 必须夹取：前端传 size=100000 时这张只增不减的表会把整页数据拖进内存。
        int size = cond.getSize() == null ? 20 : Math.min(Math.max(1, cond.getSize()), PAGE_MAX);

        LambdaQueryWrapper<ConnectorPendingWrite> w = new LambdaQueryWrapper<ConnectorPendingWrite>()
                .eq(cond.getConnectorId() != null, ConnectorPendingWrite::getConnectorId, cond.getConnectorId())
                .eq(cond.getAgentId() != null, ConnectorPendingWrite::getAgentId, cond.getAgentId())
                .eq(cond.getStatus() != null && !cond.getStatus().isBlank(),
                        ConnectorPendingWrite::getStatus, cond.getStatus())
                .orderByDesc(ConnectorPendingWrite::getCreateTime);

        Page<ConnectorPendingWrite> rows = pendingWriteMapper.selectPage(new Page<>(page, size), w);

        Map<Long, String> agentNames = resolveAgentNames(rows.getRecords());
        Page<PendingWriteView> out = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        out.setRecords(rows.getRecords().stream().map(r -> toView(r, agentNames)).toList());
        return out;
    }

    /** 批量解析 Agent 名，避免逐行查（一页 200 行就是 200 次查询）。 */
    private Map<Long, String> resolveAgentNames(List<ConnectorPendingWrite> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConnectorPendingWrite r : rows) {
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

    // ================================================================ 批准

    /**
     * 批准并<b>真正执行</b>。
     *
     * <p>执行顺序不可调换，每一步都有它挡的具体事故：
     * <ol>
     *   <li><b>过期先判</b>——陈年请求的 {@code WHERE} 条件今天命中的可能已是另一批行。</li>
     *   <li><b>抢锁再执行</b>——{@code UPDATE ... WHERE status = 'PENDING'} 返回 0 就说明
     *       别人已经处理过了，直接把真实状态还回去，<b>不覆盖、不重复执行</b>。</li>
     *   <li><b>按提交时那个 Agent 的身份执行</b>——见下面 {@link #executeAsSubmittingAgent}。</li>
     *   <li><b>结果落库</b>——影响行数 / 失败原因。审批记录是这类操作事后唯一能回溯的东西。</li>
     *   <li><b>失败落 FAILED 而不是回退 PENDING</b>——回退会让人以为「还能再点一次」，
     *       而那条语句可能已经部分生效（超时、连接断在提交途中），再点一次就是第二次执行。</li>
     * </ol>
     */
    public PendingWriteView approve(Long id) {
        ConnectorPendingWrite row = require(id);
        Map<Long, String> names = resolveAgentNames(List.of(row));

        if (!STATUS_PENDING.equals(row.getStatus())) {
            // 不是竞态，就是有人刷新前点了第二次。返回真实状态，让界面自己纠正。
            log.warn("批准了一条已被处理的写请求 id={} 当前状态={}", id, row.getStatus());
            return toView(row, names);
        }

        // ---- 1 过期不准执行 ----
        if (isExpired(row)) {
            String reason = "这条写请求已于 " + row.getExpiresAt() + " 过期，未执行。"
                    + "请让 Agent 按当前数据重新生成一条";
            // 顺手把状态落成 EXPIRED：过期这件事本身要留痕（说明有人把写请求晾在那没管），
            // 而且能让它从「待办」列表里消失，不再被反复点。
            if (claim(id, STATUS_PENDING, STATUS_EXPIRED, null, null, reason) == 0) {
                return toView(require(id), names);
            }
            applyLocally(row, STATUS_EXPIRED, null, null, reason);
            log.warn("批准被拒：写请求已过期 id={} expiresAt={}", id, row.getExpiresAt());
            return toView(row, names);
        }

        // ---- 2 抢锁 ----
        String decidedBy = currentUserIdOrNull();
        Date decidedAt = new Date();
        if (claim(id, STATUS_PENDING, STATUS_APPROVED, decidedBy, decidedAt, null) == 0) {
            // 没抢到 = 另一个超管刚刚处理完。回查一次拿真实状态，绝不能再执行一遍。
            ConnectorPendingWrite fresh = require(id);
            log.warn("并发批准：本次未抢到 id={} 当前状态={}（另一个操作者已处理）", id, fresh.getStatus());
            return toView(fresh, names);
        }
        applyLocally(row, STATUS_APPROVED, decidedBy, decidedAt, null);

        // ---- 3 执行 ----
        WriteResult result;
        try {
            result = executeAsSubmittingAgent(row);
        } catch (ConnectorException e) {
            // safeDetail 已在抛出点脱敏，可以落库、可以出网。原始异常在网关那一层已经进过日志。
            String detail = e.getSafeDetail() == null || e.getSafeDetail().isBlank()
                    ? e.getCode().title() : e.getSafeDetail();
            markFailed(row, names, detail);
            return toView(row, names);
        } catch (RuntimeException e) {
            // 网关的契约是只抛 ConnectorException；走到这里说明契约破了。原始异常只进日志：
            // 它可能带着 SQL 片段、主机名、连接参数，而 error_detail 会随审批列表出网。
            log.error("审批执行出现未归类异常 id={} connector={} agentId={}",
                    id, row.getConnectorName(), row.getAgentId(), e);
            markFailed(row, names, "执行时发生未预期的错误，数据是否被修改需要人工核对（请凭 trace_id 查服务端日志）");
            return toView(row, names);
        }

        // ---- 4 结果落库 ----
        int updated = pendingWriteMapper.update(null, new LambdaUpdateWrapper<ConnectorPendingWrite>()
                .eq(ConnectorPendingWrite::getId, id)
                .eq(ConnectorPendingWrite::getStatus, STATUS_APPROVED)
                .set(ConnectorPendingWrite::getAffectedRows, result.affectedRows()));
        if (updated == 0) {
            // 语句已经执行了，但影响行数没记下来。这是唯一一处「数据已改、留痕不全」的窗口，
            // 必须 error 级别喊出来，日志里带上全部关键字段以便人工补记。
            log.error("审批执行成功但影响行数回填失败 id={} connector={} affected={} statement={}",
                    id, row.getConnectorName(), result.affectedRows(), result.effectiveStatement());
        }
        row.setAffectedRows(result.affectedRows());

        log.info("写请求已批准并执行 id={} connector={} agentId={} affected={} elapsedMs={}",
                id, row.getConnectorName(), row.getAgentId(), result.affectedRows(), result.elapsedMs());
        return toView(row, names);
    }

    /**
     * ★ 用<b>提交这条请求的那个 Agent</b> 的身份去执行，不是用点批准的人的身份。
     *
     * <p>{@link ConnectorGateway} 的授权是按 {@link AgentContext} 判的（查 {@code agent_connection}，
     * 为空即拒）。而审批发生在<b>超管的 HTTP 请求线程</b>上，那里没有 AgentContext，
     * 直接调网关会被 fail-closed 拒掉。所以必须按 {@code row.agentId} 重建一个。
     *
     * <p><b>为什么是「重建当初那个 Agent」而不是「以超管身份放行」</b>：
     * 审批执行的是<b>当初那个 Agent 的请求</b>，权限就该按那个 Agent 判。
     * 若改成按点批准的人判，超管点一下就等于绕过了 Agent 授权——
     * 一条从没被授权访问这个连接的 Agent，可以靠「先提交、再让超管点批准」拿到写权限。
     * 顺带的好处是：如果在等待审批期间授权被取消了，网关会正常拒绝，这正是我们想要的行为。
     *
     * <p>重建的视图只填 agentId + tenantId：网关只用到这两个，多填反而会让人以为
     * 这里加载了一份完整的 Agent 配置。try/finally 恢复原值——这是请求线程池的线程，
     * 留下 ThreadLocal 会污染下一个请求。
     */
    private WriteResult executeAsSubmittingAgent(ConnectorPendingWrite row) {
        int maxRows = Math.max(1, properties.getWrite().getMaxAffectedRows());
        int timeoutSec = Math.max(1, properties.getWrite().getTimeoutSeconds());

        AgentRuntimeView previous = AgentContext.get();
        AgentContext.set(AgentRuntimeView.builder()
                .agentId(row.getAgentId())
                .tenantId(row.getTenantId())
                .build());
        try {
            return gateway.execute(row.getConnectorName(), Capability.WRITE, OPERATION_APPROVED_WRITE,
                    session -> {
                        if (!(session instanceof WriteCapable writable)) {
                            // 显式判而不是直接强转：ClassCastException 会被网关归成 UPSTREAM_ERROR
                            // 「目标系统返回了错误」，把排查方向带到客户系统上，而问题其实在我们这边。
                            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                    "连接「" + row.getConnectorName() + "」的类型不支持写操作");
                        }
                        return writable.execute(row.getStatementText(), new WriteOptions(maxRows, timeoutSec));
                    });
        } finally {
            if (previous == null) {
                AgentContext.clear();
            } else {
                AgentContext.set(previous);
            }
        }
    }

    // ================================================================ 拒绝

    /** 拒绝。与批准同一套抢锁逻辑：已被处理的直接返回真实状态，不覆盖别人的决定。 */
    public PendingWriteView reject(Long id, String reason) {
        ConnectorPendingWrite row = require(id);
        Map<Long, String> names = resolveAgentNames(List.of(row));

        if (!STATUS_PENDING.equals(row.getStatus())) {
            log.warn("拒绝了一条已被处理的写请求 id={} 当前状态={}", id, row.getStatus());
            return toView(row, names);
        }
        // 原因留空也放行：拒绝是「不做事」，不该因为文案没填就卡住。但记一句默认文案，
        // 免得列表里出现一排没有任何说明的 REJECTED。
        String detail = truncate(reason == null || reason.isBlank() ? "审批人未填写拒绝原因" : reason.trim(),
                ERROR_DETAIL_MAX);
        String decidedBy = currentUserIdOrNull();
        Date decidedAt = new Date();

        if (claim(id, STATUS_PENDING, STATUS_REJECTED, decidedBy, decidedAt, detail) == 0) {
            ConnectorPendingWrite fresh = require(id);
            log.warn("并发处理：拒绝未抢到 id={} 当前状态={}", id, fresh.getStatus());
            return toView(fresh, names);
        }
        applyLocally(row, STATUS_REJECTED, decidedBy, decidedAt, detail);
        log.info("写请求已拒绝 id={} connector={} by={}", id, row.getConnectorName(), decidedBy);
        return toView(row, names);
    }

    // ================================================================ 过期清理

    /**
     * 定时清理过期未处理的写请求。
     *
     * <p>{@code @Scheduled} 挂在下面的 void 包装方法上（与 {@code OrphanRunReconciler} 一致），
     * 这个方法保留返回值是给单测和将来的手工触发用的。
     *
     * <p>★ <b>必须 {@link TenantContext#runAsSystem} 包住。</b>定时线程上没有 TenantContext，
     * 而 {@code connector_pending_write} 在租户白名单里 ——
     * 拦截器会回落到 {@code __no_tenant__} 哨兵，于是这条 UPDATE <b>一行都扫不到，而且不报错</b>：
     * 表现是「定时任务跑得好好的，过期请求却永远躺在待办里」。
     *
     * <p>只改状态不删行：过期未处理这件事本身就是要留痕的（说明有人把写请求晾在那没管）。
     *
     * @return 本次作废的条数
     */
    public int expireStale() {
        return TenantContext.runAsSystem(() -> {
            int n = pendingWriteMapper.update(null, new LambdaUpdateWrapper<ConnectorPendingWrite>()
                    .eq(ConnectorPendingWrite::getStatus, STATUS_PENDING)
                    .lt(ConnectorPendingWrite::getExpiresAt, new Date())
                    .set(ConnectorPendingWrite::getStatus, STATUS_EXPIRED)
                    .set(ConnectorPendingWrite::getErrorDetail,
                            "超过有效期仍无人处理，已自动作废。如仍需执行，请让 Agent 按当前数据重新生成"));
            if (n > 0) {
                // warn 而不是 info：有写请求被晾到过期，要么是没人看审批队列，要么是 TTL 配得太短，
                // 两种都该被注意到。
                log.warn("已作废 {} 条过期未处理的写请求", n);
            }
            return n;
        });
    }

    /** 十分钟一轮。审批本来就是人工节奏，扫得再勤也没有意义，只是白占定时线程。 */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 180_000L)
    public void sweepExpired() {
        try {
            expireStale();
        } catch (Exception e) {
            // 定时任务抛出去没人接，且 Spring 的默认调度器是单线程的——一次未捕获异常不会
            // 停掉后续轮次，但会在日志里变成一坨没有上下文的堆栈。这里明确记一句。
            log.error("过期写请求清理失败，下一轮会重试", e);
        }
    }

    // ================================================================ 内部

    private long ttlMillis() {
        int hours = properties.getWrite().getApprovalTtlHours();
        // 配成 0 或负数时回落到 1 小时而不是「永不过期」：不确定的时候往严的方向落。
        return (hours <= 0 ? 1 : hours) * 3600_000L;
    }

    /**
     * ★ 状态流转的唯一入口：带条件的 update，返回行数就是「有没有抢到」。
     *
     * @return 1 = 抢到；0 = 别人已经改过了，本次什么都没做
     */
    private int claim(Long id, String fromStatus, String toStatus,
                      String decidedBy, Date decidedAt, String errorDetail) {
        LambdaUpdateWrapper<ConnectorPendingWrite> w = new LambdaUpdateWrapper<ConnectorPendingWrite>()
                .eq(ConnectorPendingWrite::getId, id)
                // 这一个条件就是全部的并发防线，删掉它这个方法就变成了「无条件覆盖」。
                .eq(ConnectorPendingWrite::getStatus, fromStatus)
                .set(ConnectorPendingWrite::getStatus, toStatus)
                .set(decidedBy != null, ConnectorPendingWrite::getDecidedBy, decidedBy)
                .set(decidedAt != null, ConnectorPendingWrite::getDecidedAt, decidedAt)
                .set(errorDetail != null, ConnectorPendingWrite::getErrorDetail, errorDetail);
        return pendingWriteMapper.update(null, w);
    }

    /**
     * 执行失败：落 FAILED + 脱敏原因。
     *
     * <p><b>不回退 PENDING。</b>回退会让人以为「还能再点一次」，而那条语句可能已经部分生效
     * （超时、连接断在提交途中），再点一次就是第二次执行。失败的写请求要重来，
     * 应该由 Agent 按当前数据重新生成一条，而不是复用一条不知道有没有生效过的。
     */
    private void markFailed(ConnectorPendingWrite row, Map<Long, String> names, String detail) {
        String safe = truncate(detail, ERROR_DETAIL_MAX);
        int updated = pendingWriteMapper.update(null, new LambdaUpdateWrapper<ConnectorPendingWrite>()
                .eq(ConnectorPendingWrite::getId, row.getId())
                .eq(ConnectorPendingWrite::getStatus, STATUS_APPROVED)
                .set(ConnectorPendingWrite::getStatus, STATUS_FAILED)
                .set(ConnectorPendingWrite::getErrorDetail, safe));
        if (updated == 0) {
            log.error("写请求执行失败但状态回写失败 id={} detail={}", row.getId(), safe);
        }
        row.setStatus(STATUS_FAILED);
        row.setErrorDetail(safe);
        log.warn("写请求执行失败 id={} connector={} agentId={} 原因={}",
                row.getId(), row.getConnectorName(), row.getAgentId(), safe);
    }

    /**
     * 把刚写进库的值同步到手里这份实体上，用来直接拼视图。
     *
     * <p>不再回查一次：值是我们自己刚写的，回查只会多一次查询、并把「返回的到底是哪一刻的状态」
     * 变得更含糊（回查期间别人也可能又改了别的字段）。
     */
    private static void applyLocally(ConnectorPendingWrite row, String status,
                                     String decidedBy, Date decidedAt, String errorDetail) {
        row.setStatus(status);
        if (decidedBy != null) row.setDecidedBy(decidedBy);
        if (decidedAt != null) row.setDecidedAt(decidedAt);
        if (errorDetail != null) row.setErrorDetail(errorDetail);
    }

    /** 租户过滤由拦截器注入，所以「查不到」既可能是不存在、也可能是不属于本租户——对调用方同形。 */
    private ConnectorPendingWrite require(Long id) {
        ConnectorPendingWrite row = id == null ? null : pendingWriteMapper.selectById(id);
        if (row == null) {
            throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND, "找不到这条待审批的写请求");
        }
        return row;
    }

    private static boolean isExpired(ConnectorPendingWrite row) {
        // expiresAt 为空按「未过期」处理：这一列 NOT NULL，为空说明是手工改库塞进来的行，
        // 那种行不该被时间悄悄作废，让人自己去点拒绝。
        return row.getExpiresAt() != null && row.getExpiresAt().before(new Date());
    }

    private static String currentUserIdOrNull() {
        Long uid = AdminRequestContext.findUserIdOrNull();
        return uid == null ? null : String.valueOf(uid);
    }

    private static PendingWriteView toView(ConnectorPendingWrite r, Map<Long, String> agentNames) {
        return PendingWriteView.builder()
                .id(str(r.getId()))
                .time(r.getCreateTime())
                .connectorName(r.getConnectorName())
                .agentId(str(r.getAgentId()))
                // Agent 被删掉之后审批记录仍在（它记的是历史事实），此时名字取不到，
                // 界面按「已删除的 Agent」展示即可。
                .agentName(r.getAgentId() == null ? null : agentNames.get(r.getAgentId()))
                .operation(r.getOperation())
                .targetTable(r.getTargetTable())
                .statementText(r.getStatementText())
                // ★ 之前漏了这两个，而它们恰恰是审批时唯一能判断「该不该批」的信息：
                //   traceId 让人回到「模型当时为什么要写这一条」，estimatedRows 给出影响范围。
                //   只给一条 SQL 原文，人能做的只有走个过场。
                .traceId(r.getTraceId())
                .estimatedRows(r.getEstimatedRows())
                .status(r.getStatus())
                .affectedRows(r.getAffectedRows())
                .errorDetail(r.getErrorDetail())
                .decidedBy(r.getDecidedBy())
                .decidedAt(r.getDecidedAt())
                .expiresAt(r.getExpiresAt())
                .build();
    }

    private static String str(Long v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        // 显式标记截断，否则后人会以为原文正好这么长。
        return s.substring(0, max) + "…[已截断]";
    }
}

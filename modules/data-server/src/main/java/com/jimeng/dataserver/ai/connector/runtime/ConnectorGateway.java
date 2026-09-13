package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 连接器网关：<b>所有</b>对客户系统的访问必经之路。横切层的九件事都在这里，一次写完，
 * 每个连接器实现白拿。
 *
 * <h3>为什么是一个按固定顺序执行的方法，而不是责任链 / 装饰器</h3>
 * 这九步顺序固定，且没有「按连接器类型增减某一环」的需求。拆成九个 Decorator 只是把一段
 * 从上往下读得懂的直线代码，变成九个文件加一套装配次序——而装配次序本身又会成为新的隐性契约。
 * <b>等真的出现「某类连接器要跳过/插入某一环」的需求，再抽责任链也不迟，那时改的是一处。</b>
 *
 * <h3>执行顺序（不可调换）</h3>
 * <ol>
 *   <li>总开关</li>
 *   <li>租户上下文在不在 —— <b>明确报错，不依赖 MyBatis 的哨兵兜底</b></li>
 *   <li>按名字找实例（租户过滤由拦截器注入）</li>
 *   <li>Agent 授权 —— 查 {@code agent_connection}，<b>AgentContext 为空即拒</b></li>
 *   <li>实例状态与 transport</li>
 *   <li>能力校验 —— 看探测回填的 {@code capability_flags}</li>
 *   <li>限流 —— 每实例并发闸 + 每租户速率</li>
 *   <li>执行（try-with-resources）</li>
 *   <li>审计 + 错误归一</li>
 * </ol>
 *
 * <h3>★ 三个必须 fail-closed 的地方</h3>
 * <ul>
 *   <li><b>租户为空</b>：不能靠 {@code JimengTenantLineHandler} 的 {@code __no_tenant__} 哨兵。
 *       那个兜底的表现是「查不到任何行」，会被上层误读成「没有这条连接」，再被模型转述成
 *       「系统里没有这个数据源」——一个线程上下文丢失的 bug，最后变成一句自信的错误回答。</li>
 *   <li><b>{@code AgentContext.get()} 为 null</b>：直连 {@code /data/claude/messages} 不带
 *       {@code agent_id} 时就是这种情况。绝不能照抄 {@code SkillRuntimeService.filterByAgentAllowlist}
 *       的 {@code if (agent == null) return packages;}（那是「不过滤」）——连接器一旦不过滤，
 *       等于任何一次匿名对话都能碰客户的生产库。</li>
 *   <li><b>{@code transport != direct}</b>：隧道尚未实现。{@code ConnectionResolver} 对这种行是
 *       warn 后静默跳过，于是表现为「连接明明配好了却不生效」。这里改成明确报错。</li>
 * </ul>
 *
 * <h3>授权只能查 agent_connection</h3>
 * <b>绝不能借道 {@code AgentRuntimeView.allowedSkillIds}</b>：它的 ID 空间是 {@code ai_skill.id}，
 * 拿 {@code connection.id} 去 contains 会出现「绑了 skill 100 就顺带放行 connection 100」——
 * 两套雪花 id 撞上只是时间问题，而且撞上时没有任何报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorGateway {

    /** 一次能力调用。{@code session} 已经过授权、限流与能力校验。 */
    @FunctionalInterface
    public interface Op<T> {
        T apply(ConnectorSession session);
    }

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProperties properties;
    private final ConnectorAuditService auditService;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 每实例并发闸。
     *
     * <p><b>已知局限：这是进程内的。</b>多副本时每个副本各持一份，实际并发是 N × 配置值。
     * 它保护的是客户库的连接数，而客户库的连接是全局资源——所以严格来说该做成分布式的。
     * 先按单副本做，理由是本系统当前的多副本本来就没打通（另一份高可用设计里，
     * 新实例启动会把全库在途生成刷成失败）。<b>等多副本真正可用时，这里必须一起改。</b>
     */
    private final Map<Long, Semaphore> concurrencyGates = new ConcurrentHashMap<>();

    // ================================================================ 对外

    /** 当前 Agent 被授权、状态 ACTIVE 的连接器清单。给 {@code conn_list} 工具用。 */
    public List<ConnectorSummary> listAuthorized() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        if (!TenantContext.isSet()) {
            // 这里不抛：conn_list 是模型的第一跳，抛异常会让整轮对话以一个不明所以的错误开场。
            // 返回空集 + 工具层的「未被授权」提示，语义上是安全的（fail-closed）。
            log.warn("listAuthorized 在无租户上下文的线程上被调用，返回空集");
            return List.of();
        }
        Set<Long> granted = grantedConnectionIds();
        if (granted.isEmpty()) {
            return List.of();
        }
        List<Connection> rows = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .in(Connection::getId, granted)
                .eq(Connection::getStatus, "ACTIVE")
                .orderByAsc(Connection::getName));
        List<ConnectorSummary> out = new ArrayList<>(rows.size());
        for (Connection row : rows) {
            out.add(new ConnectorSummary(
                    row.getName(),
                    row.getDisplayName(),
                    ConnectorInstanceLoader.normalizeKind(row.getKind()),
                    parseCapabilities(row.getCapabilityFlags()),
                    row.getHealthState() == null ? "UNKNOWN" : row.getHealthState(),
                    row.getHealthReason(),
                    row.getReadonlyVerifiedAt() != null));
        }
        return out;
    }

    /** 按名字执行一次能力调用。模型寻址用的是名字，不是 id。 */
    public <T> T execute(String connectorName, Capability required, String operationForAudit, Op<T> op) {
        // ---- 1 总开关 ----
        if (!properties.isEnabled()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "外部系统接入功能当前未启用，请联系平台管理员");
        }

        // ---- 2 租户上下文 ----
        if (!TenantContext.isSet()) {
            log.error("连接器调用发生在无租户上下文的线程上 connector={} cap={}（异步线程漏包 MdcAsyncSupport.wrap？）",
                    connectorName, required);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "当前请求缺少租户上下文，无法访问外部系统");
        }

        // ---- 3 找实例 ----
        Connection row = findByName(connectorName);

        // ---- 4 Agent 授权 ----
        Long agentId = requireAuthorized(row);

        // ---- 5 状态与 transport ----
        assertUsable(row);

        // ---- 6 能力 ----
        Set<Capability> caps = parseCapabilities(row.getCapabilityFlags());
        if (!caps.contains(required)) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "连接「" + row.getName() + "」不具备" + capabilityLabel(required) + "能力"
                            + (row.getCapabilityFlags() == null || row.getCapabilityFlags().isBlank()
                               ? "（尚未完成接入探测，请在管理台点一次「测试连接」）"
                               : "（实际可用：" + row.getCapabilityFlags() + "）"));
        }

        // 解析实例与实现。这一段单独包 try：本方法对外的契约是「只抛 ConnectorException」，
        // 工具层正是靠这条契约才能保证回灌模型的永远是那九类之一。loader / registry 抛出
        // 未归类的 RuntimeException 会把这条契约打破——虽然工具层还有一道兜底 catch 能防住泄漏，
        // 但那条路上审计不会写，且错误会被笼统归成「目标系统返回了错误」，把排查方向带偏到客户系统上。
        ConnectorInstance inst;
        Connector connector;
        try {
            inst = loader.load(row);
            connector = registry.require(inst.kind());
        } catch (ConnectorException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("连接器实例解析失败 connectorId={} kind={}", row.getId(), row.getKind(), e);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条连接的配置无法加载，请在管理台重新保存一次");
        }

        // ---- 7 限流 ----
        checkTenantRate(inst);
        Semaphore gate = gateFor(inst.id());
        if (!tryAcquire(gate)) {
            throw ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                    "连接「" + row.getName() + "」当前并发查询已达上限，请稍后重试");
        }

        // ---- 8 执行 + 9 审计与错误归一 ----
        long start = System.currentTimeMillis();
        try (ConnectorSession session = connector.open(inst)) {
            T result = op.apply(session);
            auditService.record(inst, agentId, required, operationForAudit,
                    auditStatement(operationForAudit, result), rowCountOf(result),
                    System.currentTimeMillis() - start, true, null, null);
            return result;
        } catch (ConnectorException e) {
            auditService.record(inst, agentId, required, operationForAudit, null, null,
                    System.currentTimeMillis() - start, false, e.getCode(), e.getSafeDetail());
            throw e;
        } catch (Exception e) {
            // 没人归过类的失败。原始异常只进日志——它可能带着 SQL 片段、主机名、连接参数，
            // 而这个方法的调用方会把返回/异常内容 JSON 化后回灌模型并落进 ai_model_call_content。
            log.error("连接器调用出现未归类异常 connectorId={} kind={} cap={}", inst.id(), inst.kind(), required, e);
            auditService.record(inst, agentId, required, operationForAudit, null, null,
                    System.currentTimeMillis() - start, false, ConnectorErrorCode.UPSTREAM_ERROR, null);
            throw ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR, null);
        } finally {
            gate.release();
        }
    }

    // ================================================================ 各步

    private Connection findByName(String connectorName) {
        if (connectorName == null || connectorName.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "缺少连接名");
        }
        // 租户过滤由 JimengTenantLineHandler 注入（connection 在 TENANT_AWARE_TABLES 里）。
        // 这里不写 eq(tenantId)，与 ConnectionResolver 的做法一致——写两遍反而会在将来改白名单时分叉。
        Connection row = connectionMapper.selectOne(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getName, connectorName.trim())
                .last("LIMIT 1"));
        if (row == null) {
            // 刻意不说「不存在」——未授权与不存在对调用方应当同形，否则可以用报错差异枚举出
            // 本租户里有哪些连接名。
            throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                    "没有名为「" + connectorName + "」的连接，或当前 Agent 未被授权使用它。"
                            + "请先用 conn_list 查看可用连接");
        }
        return row;
    }

    /** @return 当前 agentId，用于审计 */
    private Long requireAuthorized(Connection row) {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null || agent.getAgentId() == null) {
            // fail-closed。见类注释第二条。
            throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "当前会话没有绑定 Agent，无法访问外部系统。外部系统访问必须由具体 Agent 发起");
        }
        Long agentId = agent.getAgentId();
        Long count = agentConnectionMapper.selectCount(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getAgentId, agentId)
                .eq(AgentConnection::getConnectionId, row.getId()));
        if (count == null || count == 0) {
            throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                    "没有名为「" + row.getName() + "」的连接，或当前 Agent 未被授权使用它。"
                            + "请先用 conn_list 查看可用连接");
        }
        return agentId;
    }

    private void assertUsable(Connection row) {
        if (!"ACTIVE".equals(row.getStatus())) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "连接「" + row.getName() + "」已被停用");
        }
        String transport = row.getTransport();
        if (transport != null && !transport.isBlank() && !"direct".equalsIgnoreCase(transport)) {
            // 明确报错而不是静默跳过：ConnectionResolver 对这种行只 warn，表现为「配好了却不生效」。
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "连接「" + row.getName() + "」配置为经内网隧道访问，但隧道功能尚未实现");
        }
    }

    private Set<Long> grantedConnectionIds() {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null || agent.getAgentId() == null) {
            return Set.of();
        }
        List<AgentConnection> binds = agentConnectionMapper.selectList(
                new LambdaQueryWrapper<AgentConnection>().eq(AgentConnection::getAgentId, agent.getAgentId()));
        Set<Long> out = new LinkedHashSet<>();
        for (AgentConnection b : binds) {
            if (b.getConnectionId() != null) out.add(b.getConnectionId());
        }
        return out;
    }

    // ---------------------------------------------------------------- 限流

    private Semaphore gateFor(Long connectorId) {
        int permits = Math.max(1, properties.getLimit().getPerInstanceConcurrency());
        // 配置热更时已建的信号量不会变——可接受：这个值是事故防线不是调优项，改了重启即可生效。
        return concurrencyGates.computeIfAbsent(connectorId, id -> new Semaphore(permits, true));
    }

    private boolean tryAcquire(Semaphore gate) {
        try {
            // 等一小会儿而不是立刻失败：并发两三个的池，排队几百毫秒比直接报错对用户友好得多。
            // 但也不能等太久——工具执行这一段完全不受 AiConversationLoop 的 5 分钟 latch 约束。
            return gate.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 每租户每分钟调用次数。用 Redis 的 INCR + 过期做固定窗口。
     *
     * <p>刻意不用滑动窗口：固定窗口在窗口边界上最多放行 2 倍，而这里限的是「防滥用与防烧钱」，
     * 不是精确配额，2 倍误差无所谓；换来的是一次 INCR 就够、不需要 ZSET 也不需要 Lua。
     *
     * <p><b>Redis 不可用时放行而不是拒绝。</b>这是本文件里唯一一处刻意的 fail-open：
     * 这一层限的是速率，不是权限；因为缓存抖动就把客户的查询全部拒掉，代价大于收益。
     * 权限相关的每一步都在上面，且全部 fail-closed。
     */
    private void checkTenantRate(ConnectorInstance inst) {
        int limit = properties.getLimit().getPerTenantPerMinute();
        if (limit <= 0) {
            return;
        }
        String key = "connector:rate:" + inst.tenantId() + ":" + (System.currentTimeMillis() / 60_000L);
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // 两分钟过期而不是一分钟：窗口 key 本身带分钟数，多留一分钟只是为了防止
                // INCR 成功、EXPIRE 失败留下一个永不过期的 key。
                redisTemplate.expire(key, Duration.ofMinutes(2));
            }
            if (n != null && n > limit) {
                throw ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                        "本租户对外部系统的调用已达每分钟上限（" + limit + " 次），请稍后重试");
            }
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.warn("连接器租户限流检查失败，本次放行 tenantId={}", inst.tenantId(), e);
        }
    }

    // ---------------------------------------------------------------- 小工具

    static Set<Capability> parseCapabilities(String flags) {
        if (flags == null || flags.isBlank()) {
            return Set.of();
        }
        Set<Capability> out = new LinkedHashSet<>();
        for (String s : Arrays.asList(flags.split(","))) {
            String v = s.trim().toUpperCase(Locale.ROOT);
            if (v.isEmpty()) continue;
            try {
                out.add(Capability.valueOf(v));
            } catch (IllegalArgumentException ignored) {
                // 存量行里可能有本版本已经不认识的能力名，跳过而不是整条作废。
                log.debug("忽略无法识别的能力标记: {}", v);
            }
        }
        return out;
    }

    private static String capabilityLabel(Capability c) {
        return switch (c) {
            case QUERY -> "「查询」";
            case DESCRIBE -> "「自描述」";
            case INVOKE -> "「调用」";
            case SYNC -> "「同步」";
            case SUBSCRIBE -> "「订阅」";
            case HEALTH -> "「健康探测」";
        };
    }

    /**
     * 审计里记什么语句。只有查询类才有意义，且取的是<b>平台实际执行的语句</b>
     * （已被护栏改写过的那条），不是模型原文——排查时要看的是真正打到客户库上的东西。
     */
    private static String auditStatement(String operation, Object result) {
        if (result instanceof com.jimeng.dataserver.ai.connector.model.QueryResult qr) {
            return qr.effectiveStatement();
        }
        return null;
    }

    private static Integer rowCountOf(Object result) {
        if (result instanceof com.jimeng.dataserver.ai.connector.model.QueryResult qr) {
            return qr.rowCount();
        }
        return null;
    }
}

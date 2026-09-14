package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.WriteOutcome;
import com.jimeng.dataserver.ai.connector.model.WritePlan;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.service.PendingWriteService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 * <h3>两条入口，同一条管道</h3>
 * 第 4 步是 Agent 面<b>独有</b>的，其余八步与「谁在调用」无关。管理面（刷新结构快照、
 * 语义层推导这类平台自己发起的动作）走 {@link #executeAsPlatform}：跳过第 4 步，
 * <b>第 2 步的租户上下文一个字都不减</b>，并且额外验一次这条连接确实属于当前租户。
 *
 * <p>在这条路出现之前，管理面是自己 {@code connector.open()} 绕过整个网关的
 * （{@code ConnectorSchemaService.refresh} 的注释写着「这是管理面操作，不走网关」）——
 * 那句话如实描述了一个洞，但不是一个设计：限流、并发闸、审计<b>一个都没有</b>，
 * 而这些动作一次要往客户的生产库打上百条查询。抽出第 5～9 步共用，
 * 就是为了让「不走网关」这个选项不再需要存在。
 *
 * <p>唯一一处「同一步、两种参数」是第 7 步的<b>每租户速率</b>：两条路各记各的计数器
 * （见 {@link #checkTenantRate}）。共用一本账时，一轮语义层采样验证就能把客户当分钟的
 * 问数额度吃干净，表现是「模型说被限流了」而客户一句话都没问过。
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

    /**
     * 内部版的 {@link Op}，多收一个 agentId。
     *
     * <p>存在的唯一理由：写审批入队时要记下「是哪个 Agent 提的」，而那个 agentId 正是第 4 步
     * {@code requireAuthorized} 刚刚判定出来的。让入队方自己再从 {@code AgentContext} 取一遍，
     * 就出现了同一个事实的两处来源——将来授权判定一改（比如支持代跑），两处就会分叉。
     */
    private interface BoundOp<T> {
        T apply(ConnectorSession session, Long agentId);
    }

    /**
     * 这次调用是<b>谁</b>发起的。只影响第 7 步走哪个限流桶，不影响其余任何一步。
     *
     * <p>为什么不直接拿 {@code agentId == null} 当判据：那是一个<b>巧合</b>成立的等式
     * （今天管理面的 agentId 恰好是 null），不是一条被写下来的约定。将来任何一个
     * 「让后台任务代某个 Agent 跑一次」的需求，都会让这个等式不再成立，
     * 而分桶分错的表现是——平台的剖析又开始吃客户的问数额度，且没有任何报错。
     */
    private enum Caller {
        /** 一次具体的对话触发的。额度属于客户。 */
        AGENT,
        /** 平台自己发起的剖析（刷新结构快照、语义层采样探查）。额度是平台的，另记一本。 */
        PLATFORM
    }

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProperties properties;
    private final ConnectorAuditService auditService;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * ★ 用 {@link ObjectProvider} 而不是直接注入：{@code PendingWriteService} 反过来依赖本类
     * （批准之后要走网关执行那条语句），构造期直接注入会成环、启动即失败。
     *
     * <p>为什么不是仓库里更常见的 {@code @Lazy}：本类用 {@code @RequiredArgsConstructor}，
     * 而 Lombok 不会把字段上的 {@code @Lazy} 抄到构造器参数上（没有 lombok.config 的
     * copyableAnnotations），要用就得把这个八个字段的构造器手写出来。ObjectProvider 同样是
     * 延迟解析，且它本身就写明了「这里有意不在构造期拿」。
     *
     * <p>放在字段列表<b>最后</b>是有意的：{@code @RequiredArgsConstructor} 按字段声明顺序生成
     * 构造器，插在中间会让所有按位置构造本类的地方（测试）静默错位——编译期未必报错，
     * 报错时也只是一句 NoSuchMethod，看不出是顺序问题。
     *
     * <h4>为什么审批入队必须由网关发起，而不是上一层的工具层</h4>
     * 写策略的三档是<b>一个</b>判断：FORBIDDEN 拒、REQUIRE_APPROVAL 入队、AUTO 执行。
     * 把其中一档挪到工具层，等于让「写需审批」这道闸变成可绕过的——
     * 将来任何一个新的写入口（另一个工具、一个内部任务）只要没抄那段分支，
     * 这条连接就会从「写需审批」悄悄退化成「写自动」。这正是本次修复的那个缺陷。
     */
    private final ObjectProvider<PendingWriteService> pendingWrites;

    /**
     * 每实例并发闸。
     *
     * <p><b>已知局限：这是进程内的。</b>多副本时每个副本各持一份，实际并发是 N × 配置值。
     * 它保护的是客户库的连接数，而客户库的连接是全局资源——所以严格来说该做成分布式的。
     * 先按单副本做，理由是本系统当前的多副本本来就没打通（另一份高可用设计里，
     * 新实例启动会把全库在途生成刷成失败）。<b>等多副本真正可用时，这里必须一起改。</b>
     */
    private final Map<Long, Semaphore> concurrencyGates = new ConcurrentHashMap<>();

    /**
     * Agent 面的每租户速率计数 key 前缀。<b>这是客户的问数额度。</b>
     * 提成常量是为了让它和下面那个平台前缀在一个地方被看见——两者一旦写成同一个字符串，
     * 分桶就失效了，而失效的表现只是「客户偶尔被限流」，没有任何人会往这里找。
     */
    static final String RATE_KEY_PREFIX = "connector:rate:";

    /** 管理面的计数 key 前缀。见 {@link ConnectorProperties.Limit#getPerTenantPlatformPerMinute()}。 */
    static final String RATE_KEY_PLATFORM_PREFIX = "connector:rate:platform:";

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
        Connection row = preflight(connectorName, required);
        return executeOn(row, required, operationForAudit, (session, agentId) -> op.apply(session));
    }

    /**
     * 写操作专用入口。与 {@link #execute} 的唯一差别：它认写策略的<b>第三档</b>。
     *
     * <ul>
     *   <li>{@code FORBIDDEN} —— 在下面第 6.5 步被拒，和走 {@code execute} 完全一样。</li>
     *   <li>{@code REQUIRE_APPROVAL} —— <b>只解析不执行</b>，把语句连同「改哪张表、做什么」
     *       一起入审批队列，返回单号。</li>
     *   <li>{@code AUTO} —— 直接执行。</li>
     * </ul>
     *
     * <h4>★ 为什么这一档不能写进 {@link #execute} 里</h4>
     * 因为 {@code PendingWriteService.approve()} 批准之后，正是回过头来调 {@code execute()}
     * 去执行那条语句的。若把「REQUIRE_APPROVAL 就入队」塞进 {@code execute}，
     * 批准的动作会再入一次队，永远执行不到——审批变成一个自己咬自己的循环。
     * 所以 {@code execute} 保持「纯执行」，分档只在这个入口发生。
     *
     * <h4>入队也要走完整的 4～7 步</h4>
     * 入队路径同样经过 Agent 授权、状态校验、能力校验、限流与审计。
     * 没被授权的 Agent 连「提交一条待审批」都不该做得到——否则它可以用垃圾请求淹掉审批队列，
     * 或者赌某个超管会顺手点批准（批准执行时会按<b>当初那个 Agent</b> 判权限，所以最终仍拦得住，
     * 但把明知会被拒的东西放进别人的待办列表，本身就是一次成功的骚扰）。
     */
    public WriteOutcome executeWrite(String connectorName, String statement, WriteOptions options,
                                     String operationForAudit) {
        Connection row = preflight(connectorName, Capability.WRITE);
        WritePolicy policy = WritePolicy.parse(row.getWritePolicy());

        if (policy == WritePolicy.REQUIRE_APPROVAL) {
            // 入队那一条单独命名：它在 connector_audit 里必须与「真的写进去了」长得不一样，
            // 否则事后翻审计时会把一次提交读成一次写入。
            Long approvalId = executeOn(row, Capability.WRITE, operationForAudit + ".submit", (session, agentId) -> {
                WriteCapable w = writeCapable(session, row);
                WritePlan plan = w.plan(statement);
                // 预估影响行数：审批的人光看一条 SQL 判不出它命中 3 行还是 30 万行。
                // 估算失败不能挡住入队——它是给人的参考，不是准入条件。
                Integer estimated;
                try {
                    estimated = w.estimateAffectedRows(plan.effectiveStatement());
                } catch (RuntimeException e) {
                    log.warn("预估影响行数失败，按未知处理 connectorId={} op={}", row.getId(), plan.operation(), e);
                    estimated = null;
                }
                return pendingWrites.getObject().submit(
                        row.getId(), row.getName(), row.getTenantId(), agentId,
                        plan.operation(), plan.targetTable(), plan.effectiveStatement(), estimated);
            });
            return WriteOutcome.pending(approvalId);
        }

        WriteResult result = executeOn(row, Capability.WRITE, operationForAudit,
                (session, agentId) -> writeCapable(session, row).execute(statement, options));
        return WriteOutcome.done(result);
    }

    /**
     * ★ <b>管理面</b>入口：平台自己发起的一次能力调用（刷新结构快照、语义层的采样探查……）。
     *
     * <p>与 {@link #execute} 的唯一差别是<b>跳过第 4 步的 Agent 授权</b>——管理面没有 Agent 身份，
     * 那一步无从判起。其余八步一步不少：状态、能力、写策略、每租户速率、每实例并发闸、
     * 执行、审计、错误归一，全部与 Agent 面共用同一份实现（{@link #runGated}）。
     *
     * <h4>★ 它不是授权旁路，别把它当后门用</h4>
     * 跳过的只是「<b>这个 Agent</b> 被授予了这条连接吗」。租户隔离一点没松：
     * <ul>
     *   <li>当前线程没有租户上下文 → 直接拒（第 2 步，与 Agent 面同一段代码）。</li>
     *   <li>这条连接不属于当前租户 → 按「不存在」拒，见 {@link #platformPreflight}。</li>
     * </ul>
     *
     * <p><b>{@code TenantContext.runAsSystem(...)} 不算数。</b>它只是把 {@code SYSTEM_MODE}
     * 打开、让 MyBatis 的租户拦截器别注入条件，{@code CURRENT_TENANT} <b>原样为空</b>——
     * 于是第 2 步照样拒。这是有意的：后台任务要碰客户的生产库，就必须说清楚碰的是
     * <b>哪一个</b>客户的库，而不是以「系统身份」笼统地碰。
     * 所以定时/异步任务的正确姿势是按那一行的 {@code tenant_id} 自己
     * {@code TenantContext.set(...)}（并在 finally 里 {@code clear()}），再调本方法。
     *
     * <h4>★ operation 必须是 {@code platform.} 开头</h4>
     * 理由见 {@link ConnectorAuditService#PLATFORM_OP_PREFIX}：客户的 DBA 要能一眼分出
     * 哪些查询是 Agent 在替人问数、哪些是平台在剖析这个库。名字传错当场抛，不是警告——
     * 一个混进 Agent 命名空间的平台查询，事后没有任何办法从审计里择出来。
     *
     * @param connectorId       连接 id。管理面按 id 寻址，不像模型那样按名字
     * @param required          需要的能力，同样按 {@code capability_flags} 校验
     * @param operationForAudit 审计里的动作名，必须以
     *                          {@link ConnectorAuditService#PLATFORM_OP_PREFIX} 开头
     */
    public <T> T executeAsPlatform(Long connectorId, Capability required, String operationForAudit, Op<T> op) {
        requirePlatformOperation(operationForAudit);
        Connection row = platformPreflight(connectorId, required);
        // agentId 传 null：审计里「哪个 Agent 干的」这一列对管理面就该是空的。
        // 随手填个 0 或 -1 会在 connector_audit 里造出一个查无此人的 Agent，
        // 而那张表正是用来回答「这次访问是谁发起的」的。
        return runGated(row, required, null, Caller.PLATFORM, operationForAudit,
                (session, agentId) -> op.apply(session));
    }

    /** 第 1～3 步：总开关、租户上下文、按名字找实例。Agent 面的两个入口共用。 */
    private Connection preflight(String connectorName, Capability required) {
        assertEnabledAndTenant(connectorName, required);

        // ---- 3 找实例 ----
        return findByName(connectorName);
    }

    /**
     * 管理面的第 1～3 步。与 {@link #preflight} 的差别只有寻址方式（id 而不是名字），
     * 和<b>多出来的那一句归属校验</b>。
     *
     * <h4>为什么这里显式比对 tenant_id，而 {@link #findByName} 不比</h4>
     * 别处不写 {@code eq(tenantId)}，是因为那等于把 {@code JimengTenantLineHandler} 的白名单
     * 抄第二遍，将来改白名单时两处会分叉。这里不一样：这是把行查回来<b>之后</b>的一句断言，
     * 它不可能与拦截器分叉，只可能比拦截器更严。
     *
     * <p>而管理面恰好是唯一可能跑在 {@code TenantContext.runAsSystem(...)} 里的地方——
     * 系统模式下拦截器<b>不注入</b>租户条件，{@code selectById} 就能捞到别的租户的行。
     * 没有这一句，一个「顺手包了 runAsSystem」的后台任务就成了跨租户访问客户生产库的入口，
     * 而且不会报任何错。
     */
    private Connection platformPreflight(Long connectorId, Capability required) {
        assertEnabledAndTenant(connectorId, required);

        // ---- 3 找实例 ----
        if (connectorId == null) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "缺少连接 id");
        }
        Connection row = connectionMapper.selectById(connectorId);
        if (row == null || !TenantContext.get().equals(row.getTenantId())) {
            // 与 findByName 同一条取舍：「不存在」与「不属于你」对调用方必须同形。
            throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                    "连接不存在，或它不属于当前租户");
        }
        return row;
    }

    /** 第 1～2 步：总开关与租户上下文。<b>Agent 面与管理面完全一致</b>，一个字都不减。 */
    private void assertEnabledAndTenant(Object connectorRef, Capability required) {
        // ---- 1 总开关 ----
        if (!properties.isEnabled()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "外部系统接入功能当前未启用，请联系平台管理员");
        }

        // ---- 2 租户上下文 ----
        if (!TenantContext.isSet()) {
            log.error("连接器调用发生在无租户上下文的线程上 connector={} cap={}（异步线程漏包 MdcAsyncSupport.wrap？）",
                    connectorRef, required);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "当前请求缺少租户上下文，无法访问外部系统");
        }
    }

    /** 见 {@link #executeAsPlatform} 里关于审计命名的那一段。 */
    private static void requirePlatformOperation(String operationForAudit) {
        if (operationForAudit == null
                || !operationForAudit.startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX)) {
            // 刻意抛 IllegalArgumentException 而不是 ConnectorException：这是接错线，不是运行期故障。
            // 归成 ConnectorException 会被上层那些「失败只记日志」的 catch 顺手吞掉，
            // 于是审计里悄悄多出一批看起来像 Agent 干的平台查询——正是这次要修的那个问题本身。
            throw new IllegalArgumentException("管理面调用的审计动作名必须以 "
                    + ConnectorAuditService.PLATFORM_OP_PREFIX + " 开头（见 ConnectorAuditService），实际传入: "
                    + operationForAudit);
        }
    }

    /** 第 4～9 步。实例已解析出来，剩下的横切层对 {@code execute} 与 {@code executeWrite} 完全一致。 */
    private <T> T executeOn(Connection row, Capability required, String operationForAudit, BoundOp<T> op) {
        // ---- 4 Agent 授权 ----
        Long agentId = requireAuthorized(row);
        return runGated(row, required, agentId, Caller.AGENT, operationForAudit, op);
    }

    /**
     * 第 5～9 步：状态与 transport、能力、写策略、限流、执行、审计与错误归一。
     *
     * <p><b>这一段与「谁在调用」无关</b>，所以 Agent 面（第 4 步判过 agent_connection）
     * 与管理面（{@link #executeAsPlatform}，没有 Agent 但验过租户归属）共用同一份实现。
     * 抽出来的理由见类注释「两条入口，同一条管道」那一节：在此之前，管理面想做一次
     * 对客户库的访问，只能自己 {@code open()}，于是限流、并发闸、审计一并绕过。
     *
     * <p><b>写策略这一档管理面同样要过。</b>后台任务不是超管：连接配成「只读」，
     * 平台自己发起的写也该被拒——否则「只读」这个开关就只对模型成立。
     *
     * @param agentId 发起调用的 Agent；管理面为 {@code null}，审计里那一列就该是空的
     */
    private <T> T runGated(Connection row, Capability required, Long agentId, Caller caller,
                           String operationForAudit, BoundOp<T> op) {
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

        // ---- 6.5 写策略闸 ----
        //
        // ★ 这一步单独存在、而不是靠上面 capability_flags 里有没有 WRITE 来兜底，
        //   因为两者的时效不同：capability_flags 是【上次探测时】回填的快照，
        //   而写策略是【此刻】的配置。超管刚把一条连接从 AUTO 改回只读，若只看快照，
        //   在下次探测之前写操作仍会放行——一个「关了但没立刻生效」的安全开关，
        //   比没有这个开关更危险。
        if (required == Capability.WRITE) {
            WritePolicy policy = WritePolicy.parse(row.getWritePolicy());
            if (!policy.allowsWrite()) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "连接「" + row.getName() + "」的写策略是「只读」，不允许执行写操作。"
                                + "如需开放，请由企业超管在管理台修改写策略");
            }
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
        checkTenantRate(inst, caller);
        Semaphore gate = gateFor(inst.id());
        if (!tryAcquire(gate)) {
            throw ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                    "连接「" + row.getName() + "」当前并发查询已达上限，请稍后重试");
        }

        // ---- 8 执行 + 9 审计与错误归一 ----
        long start = System.currentTimeMillis();
        try (ConnectorSession session = connector.open(inst)) {
            T result = op.apply(session, agentId);
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

    /**
     * 网关已按 {@code capability_flags} 放行了 WRITE，但会话类压根没实现写——说明标记与实现分叉。
     * 直接强转抛出的 {@code ClassCastException} 会被下面归成「目标系统返回了错误」，
     * 把排查方向带到客户系统上，而问题在我们这边。
     */
    private static WriteCapable writeCapable(ConnectorSession session, Connection row) {
        if (session instanceof WriteCapable w) return w;
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                "连接「" + row.getName() + "」的类型不支持写操作");
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
     * <h4>★ Agent 面与管理面各记一本，key 前缀不同</h4>
     * 从前两条路共用 {@code connector:rate:<租户>:<分钟>} 这一个计数器。语义层的采样验证
     * 一轮要对客户的库打上百条探查，于是<b>一次后台剖析就能把客户当分钟的问数额度吃干净</b>——
     * 客户那边一句话都没问，模型却开始回「被限流了」。而排查时，审计里那一分钟的确有上百次调用，
     * 但它们全是平台自己发的，没有任何线索把「模型被限流」指回真凶。
     *
     * <p>所以管理面换成 {@link #RATE_KEY_PLATFORM_PREFIX}，额度也单独配
     * （{@link ConnectorProperties.Limit#getPerTenantPlatformPerMinute()}，{@code <= 0} 时沿用
     * Agent 面那个<b>数值</b>，但仍然是独立的一本账）。
     * <b>要点是分桶，不是数值</b>：数值配多少都只是平台剖析自己跑多快的问题，
     * 而共用一个桶是「平台的行为砸在用户脸上」。
     *
     * <p><b>Redis 不可用时放行而不是拒绝。</b>这是本文件里唯一一处刻意的 fail-open：
     * 这一层限的是速率，不是权限；因为缓存抖动就把客户的查询全部拒掉，代价大于收益。
     * 权限相关的每一步都在上面，且全部 fail-closed。
     */
    private void checkTenantRate(ConnectorInstance inst, Caller caller) {
        boolean platform = caller == Caller.PLATFORM;
        int limit = effectiveRateLimit(platform);
        if (limit <= 0) {
            return;
        }
        String key = rateKey(inst.tenantId(), platform, System.currentTimeMillis() / 60_000L);
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // 两分钟过期而不是一分钟：窗口 key 本身带分钟数，多留一分钟只是为了防止
                // INCR 成功、EXPIRE 失败留下一个永不过期的 key。
                redisTemplate.expire(key, Duration.ofMinutes(2));
            }
            if (n != null && n > limit) {
                // 两条路的文案必须分开：管理面的限流不是客户干了什么，是平台自己的后台任务跑太快。
                // 给后台任务回一句「本租户调用过于频繁」，会让读日志的人去查客户的用量——方向是反的。
                throw ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, platform
                        ? "平台侧对该租户外部系统的剖析调用已达每分钟上限（" + limit + " 次），请稍后重试"
                        : "本租户对外部系统的调用已达每分钟上限（" + limit + " 次），请稍后重试");
            }
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.warn("连接器租户限流检查失败，本次放行 tenantId={} platform={}", inst.tenantId(), platform, e);
        }
    }

    /**
     * 管理面没单独配额度时沿用 Agent 面那个<b>数值</b>（桶仍然是分开的）。
     *
     * <p>为什么不给它一个自己的默认数字：那会让「两条路各自多宽」变成一个必须先想清楚才敢改的问题，
     * 而这次要修的根本不是宽窄，是两条路共用一个计数器。沿用同一个数值，
     * 等于把这次改动的语义收窄成「把一本账拆成两本」，不附带任何额度变化——
     * 拆账和调额度混在一次改动里，出了问题分不清是哪一半造成的。
     */
    private int effectiveRateLimit(boolean platform) {
        int perTenant = properties.getLimit().getPerTenantPerMinute();
        if (!platform) {
            return perTenant;
        }
        int configured = properties.getLimit().getPerTenantPlatformPerMinute();
        return configured > 0 ? configured : perTenant;
    }

    /** 固定窗口计数 key。提成方法是为了让「两条路的 key 确实不同」可以被单测钉住。 */
    static String rateKey(String tenantId, boolean platform, long minute) {
        return (platform ? RATE_KEY_PLATFORM_PREFIX : RATE_KEY_PREFIX) + tenantId + ":" + minute;
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
            case WRITE -> "「写入」";
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
        // 写操作更需要留痕：查询留不下语句顶多是排查费劲，写操作留不下语句就是「数据被改了但没人知道改的是什么」。
        if (result instanceof WriteResult wr) {
            return wr.effectiveStatement();
        }
        // 入队路径返回的是审批单 id，没有「已执行的语句」可记——语句在 connector_pending_write 里，
        // 那才是它此刻的唯一权威副本。抄一份到审计表只会制造两个可能分叉的版本。
        return null;
    }

    private static Integer rowCountOf(Object result) {
        if (result instanceof com.jimeng.dataserver.ai.connector.model.QueryResult qr) {
            return qr.rowCount();
        }
        // 写操作的「行数」是被改动的行数——审计表里这一列对写和读是同一个含义：这次调用碰了多少行。
        if (result instanceof WriteResult wr) {
            return wr.affectedRows();
        }
        return null;
    }
}

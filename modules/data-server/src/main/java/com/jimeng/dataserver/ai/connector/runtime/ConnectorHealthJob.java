package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 连接器的两件定时活：<b>健康探测</b>（ping）与<b>结构快照的低频刷新</b>（refresh）。
 *
 * <p>放在一个类里，是设计文档 §8 的原话（「ConnectorHealthJob 搭车加低频 refresh」），也因为它们回答的是
 * 同一个问题的两半：这条连接<b>还通不通</b>，我们手上那份说明书<b>还对不对</b>。
 * 但执行上两者刻意<b>互不相干</b>——各自的开关、各自的调度、各自的跨副本锁，刷新还跑在本类自己的线程上。
 * 一次几十秒的结构刷新不能把 ping 挤到下一个 5 分钟去，更不能让 ping 因为抢不到什么东西而把连接判成异常。
 *
 * <h2>一、健康探测：让「连接坏了」在界面上可见，而不是等某次对话里 Agent 答错才发现</h2>
 *
 * <h3>★ 只 ping，不做接入探测那三步</h3>
 * 接入探测是 探活 → <b>验只读</b> → 探能力，其中验只读会往客户库发一条
 * {@code UPDATE ... WHERE 1 = 0}（靠权限拒绝来证明账号写不了）。那是<b>录入时验一次</b>的动作。
 * 每 5 分钟往客户的生产库发一次写尝试，无论多无害都不可接受——客户的 DBA 看到审计日志
 * 会先来找我们。所以这里只调 {@link ConnectorSession#ping()}。
 *
 * <p>推论：本任务<b>绝不触碰</b> {@code readonly_verified_at} 和 {@code capability_flags}。
 * 那两个字段的含义是「接入时验过/探过」，由完整探测负责。健康探测把它们刷掉，
 * 等于把一次网络抖动说成「只读验证失效了」。
 *
 * <h3>★ ping 刻意不走 {@link ConnectorGateway}——「所有访问必经网关」的两个有文档的例外之一</h3>
 * 网关的类注释写的是「所有对客户系统的访问必经之路」，并列了<b>两个</b>例外：这里的 ping，和接入探测
 * {@link ConnectorProbeService#probe}（管理台上试连 / 新建 / 编辑 / 测试连接时的三步探测）。
 * <b>两者不经网关的理由不同，不能互相援引</b>：接入探测是网关<b>无从下手</b>——试连的配置根本没落库、
 * 新连接还没有能力标记，理由在它自己的类注释里；ping 是<b>刻意选择</b>——连接行好好地在库里，网关完全走得通，不走是因为：
 * <ul>
 *   <li><b>它不碰客户的数据。</b>MySQL 是一句 {@code SELECT 1}，HTTP 是对 base_url 的一次 {@code HEAD}——
 *       读不到任何一张表、拿不到任何响应体。网关那几道闸保护的东西（数据、写入、查询额度）它一样都不涉及。</li>
 *   <li><b>走网关会把审计表淹掉。</b>网关第 9 步每次调用写一行 {@code connector_audit}：5 分钟一次，
 *       就是<b>每条连接每天约 288 行</b>。客户 DBA 用那张表的方式是按 operation 过滤，去回答
 *       「平台到底对我的库做了什么」——几百行 ping 会把真正要看的那几行 {@code platform.*} 埋掉，
 *       损坏的恰恰是那张表存在的意义。</li>
 *   <li><b>走网关会让健康态量到我们自己的闸。</b>网关第 7 步要抢每实例并发许可（最多等 2 秒）。
 *       一条正忙、但完全健康的连接（许可被 Agent 查询和平台剖析占满）会抢不到许可、被记成 UNHEALTHY——
 *       健康态从此量的是「我们的信号量忙不忙」，而不是「客户的库通不通」。</li>
 * </ul>
 * 代价是 ping 不受每租户速率限制、不留审计。可以接受：它是一个固定、极小、由平台自己控制的量。
 * <b>这条例外只覆盖 ping。</b>读结构、读数据、读统计的动作都不在此列——本类里的结构刷新就老老实实走
 * {@code ConnectorSchemaService.refresh()} → {@link ConnectorGateway#executeAsPlatform}。
 * 例外清单由单测 {@code ConnectorGatewayBypassInventoryTest} 从编译产物里钉住：谁在网关、本类、接入探测之外的
 * 第四个地方调 {@code Connector.open}，那条测试会红。
 *
 * <h3>健康态不参与放行判定</h3>
 * {@code ConnectorGateway} 不检查 health——一次瞬时抖动把连接标成 UNHEALTHY 就拦掉正常查询，
 * 代价大于收益。健康态的作用是<b>显示</b>：管理台上看得见，以及经 {@code conn_list} 给到模型，
 * 让它在调用前先向用户说明「这条连接当前状态异常」。
 *
 * <h3>三个执行期约束</h3>
 * <ul>
 *   <li><b>跨租户</b>：定时线程里没有 {@code TenantContext}，必须 {@code runAsSystem}，
 *       否则租户拦截器回落到 {@code __no_tenant__} 哨兵，一行都扫不到<b>而且不报错</b>。</li>
 *   <li><b>多副本</b>：这是对<b>客户外部系统</b>的调用，每个副本各扫一遍就是 N 倍的外部流量。
 *       用 Redis 锁让同一时刻只有一个副本在扫。拿不到锁就安静跳过——不是错误。</li>
 *   <li><b>单条失败不能中断整轮</b>：一条连不上的连接不该让它后面的连接都得不到探测。</li>
 * </ul>
 *
 * <h2>二、结构快照的低频刷新</h2>
 * 漂移处置（ObjectDiff → applyDrift → STALE）挂在 {@code ConnectorSchemaService.refresh()} 上，
 * 在本任务之前它只在超管手工点「刷新结构」时才跑。没人点，过期的说明书就一直被当成事实注入——
 * 而 {@code ConnectorOverviewService} 把那份快照塞进的是<b>每一个</b>请求的 system 上下文。
 * 细节在 {@link #dispatchSchemaRefresh()} 与 {@link #refreshDueSchemas()}，这里只列不能动的几条：
 * <ul>
 *   <li><b>只刷新，绝不重新推导。</b>本类只调 {@code refresh()}，不持有任何推导或改写语义行的 bean。
 *       周期性重推要走 {@code replaceInferred}，物理删掉全部 INFERRED 行——连同平台花客户查询额度换来的
 *       每一条采样验证结论——设计文档 §12 明文不做（会覆盖人工确认过的口径）。
 *       {@code refresh()} 成功之后自己派发的增量推导不在此列：它只给还没有说明书的表补推导，不清空任何东西。</li>
 *   <li><b>每条刷新都以那一行的真实租户身份跑，而且不在系统模式里跑</b>，见 {@link #refreshOne}。</li>
 *   <li><b>低频、有上限、最旧优先，时钟是落库的 {@code connector_schema.synced_at}</b>，不是进程里的定时器。</li>
 *   <li><b>只刷已经有快照的连接；逐条失败、安静失败。</b></li>
 *   <li><b>繁忙不算失败、不占名额，但要让位；有繁忙前科的连接，重试另有预算。</b>一群忙的连接挤不掉健康的到期连接。</li>
 *   <li><b>能不能刷，按轮到它那一刻重读的连接行判</b>，不按本轮开始时选人读到的那份。</li>
 *   <li><b>进程关闭时，本实例手上的跨副本锁当场放掉</b>，不等租期，见 {@link #releaseOnShutdown()}。</li>
 * </ul>
 *
 * <p>顺带一提：{@code MyMetaObjectHandler} 从请求头取 user-id 填 update_user，
 * 定时线程里没有请求，所以本任务写回的行 {@code update_user} 是 null。这是预期的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorHealthJob {

    private static final String LOCK_KEY = "connector:health:sweep";

    /** 结构刷新的跨副本锁。<b>与 ping 的锁分开</b>：谁在跑都不该让另一件事跳过一轮。 */
    private static final String REFRESH_LOCK_KEY = "connector:schema-refresh:sweep";

    /**
     * 「这条连接暂时别再自动刷」的标记，一条连接一个键，值是「结局@写入时刻（毫秒）」。三种结局会写它：
     * <ul>
     *   <li>{@value #OUTCOME_FAILED}：失败，TTL {@code intervalHours} 小时；</li>
     *   <li>{@value #OUTCOME_GUARDED}：{@code refresh()} 拒绝落库，TTL {@code intervalHours} 小时（见 {@link #refreshDueSchemas()}）；</li>
     *   <li>{@value #OUTCOME_BUSY}：繁忙，TTL 是 {@link #busyMemoryMinutes}——比繁忙冷却长，多出来的那一段是「繁忙前科」。</li>
     * </ul>
     * 前两种<b>键在就是冷却</b>，值只给排查看（两者 TTL 一样长，光看 TTL 分不出是哪一种）。
     * BUSY 的时间戳是<b>承重的</b>：写入不到 {@link #busyCooldownMinutes} 算冷却；过了冷却期、键还在，
     * 就是「有繁忙前科」，重试它要占本轮的繁忙预算。读法只有 {@link #classifyMark} 一份。
     */
    static final String REFRESH_COOLDOWN_KEY_PREFIX = "connector:schema-refresh:cooldown:";

    static final String OUTCOME_FAILED = "FAILED";
    static final String OUTCOME_GUARDED = "GUARDED";
    static final String OUTCOME_BUSY = "BUSY";

    /**
     * 「繁忙」的<b>兜底</b>标记短语——只在 cause 链上找不到 {@link ConnectorException} 原件时才用，见 {@link #isBusy}。
     *
     * <p><b>今天真实的限流拒绝不靠它认。</b>{@code ConnectorSchemaService.refresh()} 把网关抛的
     * {@link ConnectorException} 转成 {@code ServiceException(INVALID_REQUEST, safeDetail)} 时<b>带着 cause</b>，
     * {@code RATE_LIMITED} 直接从 cause 链上读到。「refresh() 带 cause」这件事由单测
     * {@code ConnectorHealthJobSchemaRefreshTest} 用真实的网关 + 真实的 refresh() 钉住。
     *
     * <p><b>那为什么还留着：</b>防的是将来某次改动把 cause 丢了（本功能最初的写法就没带）。那时错误码在转换那一层
     * 被抹成 INVALID_REQUEST，只剩文案可认；没有这条兜底，每一次限流都会被记成失败、冷却一整个间隔，日志里只是多几条 WARN。
     * refresh() 里今天不带 cause 抛出的那几种 {@code ServiceException}（连接不存在、已停用、类型不支持自描述）文案里都没有这句话，
     * 所以这条兜底现在实际上接不到任何东西——它只在 cause 丢了的那天生效。
     * 带这句话的 RATE_LIMITED 目前有三处：网关的并发许可等不到、平台速率桶满了、客户库连接数满了（MySQL 1040/1203）。
     * 三者都是「现在不方便」，不是「坏了」。
     *
     * <p>认错的方向是温和的——繁忙被当成失败，只是多一条 WARN、晚一个间隔再刷。
     */
    static final String BUSY_MARK = "请稍后重试";

    /**
     * 「繁忙」之后让位多久（分钟）的<b>下限</b>，实际值见 {@link #busyCooldownMinutes}。
     *
     * <p><b>繁忙已经不占名额了，为什么还要冷却：</b>它的快照没更新、下一次检查仍然排在最前。不冷却，
     * 一条整天有 Agent 在问数、许可常年被占满的库每 10 分钟就被撞一次——每次要么白等 2 秒许可、
     * 要么往一个连接数已经满了的客户库再挤一次连接，还每次都去抢本轮的繁忙预算，让别的有前科的连接轮不到重试。
     *
     * <p><b>为什么是几十分钟而不是一个间隔：</b>繁忙是「现在不方便」，过半小时多半就不忙了；
     * 按失败那样冷却 {@code intervalHours} 小时，是在惩罚一条健康的连接、让它的说明书平白多旧几个小时。
     */
    static final long BUSY_COOLDOWN_MINUTES = 30L;

    /**
     * 推导认领多久内算「还活着」。与 {@code ConnectorSemanticDeriveService.CLAIM_STALE_MINUTES} 同值，
     * 那边是 private 所以这里抄一份：超过它，推导那边自己也认为这次认领已经死了、允许别人抢。
     */
    static final long DERIVE_CLAIM_LIVE_MINUTES = 30L;

    /**
     * 推导 READY 之后多少分钟内不做定时刷新，给紧随其后的采样验证阶段让路。
     * 按默认节流估的：S3 最坏约 20 分钟、S4 最坏约 30 分钟，先后跑。这是个窗口，不是精确信号。
     */
    static final long VALIDATION_QUIET_MINUTES = 60L;

    /** {@code connector.schema-refresh.check-interval-ms} 的默认值，与两处占位符里写的 600000 相同。 */
    static final long DEFAULT_CHECK_INTERVAL_MS = 600_000L;

    /** 关闭回调替别的线程放锁时，等 Redis 回话最多多久。进程关闭不能被一次 Redis 抖动拖住。 */
    static final long FORCE_RELEASE_WAIT_MS = 3_000L;

    private final ConnectionMapper connectionMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProperties properties;
    private final RedissonClient redissonClient;
    /** 选「哪些连接的快照过期了」。只读我们自己的库，见 {@link #selectDue}。 */
    private final ConnectorSchemaMapper schemaMapper;
    /**
     * 结构刷新的唯一入口，<b>本类只调它的 {@code refresh()}</b>。
     *
     * <p>注入它不会成环：没有任何 bean 依赖本类，本类是叶子。
     * 这两个新依赖放在字段列表<b>最后</b>：{@code @RequiredArgsConstructor} 按声明顺序生成构造器，
     * 插在中间会让按位置构造本类的地方（测试）静默错位。
     */
    private final ConnectorSchemaService schemaService;

    /**
     * 检查间隔（毫秒），只用来算繁忙冷却与繁忙前科（{@link #busyCooldownMinutes} / {@link #busyMemoryMinutes}）。
     * 启动时由 {@link #setCheckInterval} 写入；非 final，不进 {@code @RequiredArgsConstructor} 生成的构造器，
     * 按位置构造本类的地方（测试）拿到的是与占位符相同的默认值。
     */
    private volatile long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;

    /** 上一轮刷新还没跑完时，本次检查直接跳过，不排队。见 {@link #dispatchSchemaRefresh()}。 */
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    /** 进程正在关闭。置位之后不再抢任何锁、刷新循环不再开始下一条。见 {@link #releaseOnShutdown()}。 */
    private volatile boolean closing;

    /**
     * 本实例<b>此刻</b>拿着的跨副本锁，连同「是哪条线程拿的」。只为 {@link #releaseOnShutdown()} 而记。
     *
     * <p><b>为什么要显式记，而不是关闭时去问 Redisson「这把锁是不是我的」：</b>Redisson 的锁归属是
     * 「客户端 id + <b>线程 id</b>」，关闭回调跑在另一条线程上，{@code isHeldByCurrentThread()} 永远是 false；
     * 而不问归属直接 {@code forceUnlock()}，在租期已过、锁已被别的副本拿走时删掉的是<b>别人</b>的锁。
     * 记下拿锁那条线程的 id，才能用 {@code unlockAsync(threadId)} 只放自己那一份。
     *
     * <p>谁从集合里摘掉这一项，谁负责放锁：正常收尾（{@link #release}）与关闭回调各 {@code remove} 一次，
     * 只有一个会成功，所以两者撞在一起时锁不会被放两次。
     */
    private final Set<HeldLock> heldLocks = ConcurrentHashMap.newKeySet();

    /**
     * 结构刷新专用线程。理由见 {@link #dispatchSchemaRefresh()}：Spring 默认的调度器是单线程的，
     * 刷新不能占着它。守护线程——进程退出时不该被一次卡在客户库上的刷新拖住；
     * 也正因为它可能死在半路，它拿着的锁不指望它自己的 finally 去放，见 {@link #releaseOnShutdown()}。
     */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connector-schema-refresh");
        t.setDaemon(true);
        return t;
    });

    /**
     * ★ 与 {@link #scheduleSchemaRefresh()} 的 {@code @Scheduled(fixedDelayString)} 读<b>同一个</b>占位符，
     * 所以<b>按 String 绑定</b>，由 {@link #parseCheckIntervalMs} 照 Spring 的判法解析。
     *
     * <p>{@code fixedDelayString} 接受两种写法：毫秒整数（{@code 600000}）和 ISO-8601 时长（{@code PT10M}）。
     * 按 {@code long} 绑定时，Nacos 里写一个 {@code @Scheduled} 完全接受的 {@code PT10M}，本 bean 就创建失败、
     * <b>data-server 整个起不来</b>——而出事的只是一个用来算繁忙冷却的附属参数。附属参数不能比它服务的调度更挑剔。
     *
     * <p>解析不了时回落默认值、记一条 WARN，<b>不抛</b>：真解析不了的值 {@code @Scheduled} 自己会在启动时拒绝，
     * 起不起得来轮不到这里决定。
     */
    @Value("${connector.schema-refresh.check-interval-ms:600000}")
    void setCheckInterval(String raw) {
        Long parsed = parseCheckIntervalMs(raw);
        if (parsed == null) {
            log.warn("connector.schema-refresh.check-interval-ms 的值「{}」解析不了，繁忙冷却按默认检查间隔 {} 毫秒算",
                    raw, DEFAULT_CHECK_INTERVAL_MS);
            checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
            return;
        }
        checkIntervalMs = parsed;
    }

    /** 实际生效的检查间隔（毫秒）。给单测看绑定结果。 */
    long effectiveCheckIntervalMs() {
        return checkIntervalMs;
    }

    // ================================================================ 一、健康探测

    /**
     * initialDelay 给足 2 分钟：启动瞬间就去连一圈客户的库没有意义，
     * 而且那会儿本进程自己的连接池、Nacos 配置都还在预热。
     */
    @Scheduled(fixedDelayString = "${connector.health.interval-ms:300000}", initialDelay = 120_000L)
    public void sweep() {
        if (!properties.getHealth().isEnabled()) {
            return;
        }
        HeldLock held = null;
        try {
            // waitTime=0：抢不到就是别的副本正在扫，直接放弃本轮，不排队。
            // leaseTime 用整轮超时上限，防止本副本崩了之后锁永远不释放。
            held = tryHold(LOCK_KEY, properties.getHealth().getSweepTimeoutSeconds(), TimeUnit.SECONDS, "连接器健康探测");
            if (held == null) {
                log.debug("连接器健康探测：另一个副本正在执行（或本进程正在关闭），本轮跳过");
                return;
            }
            TenantContext.runAsSystem(this::doSweep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 定时任务抛出去会被 Spring 吞掉且只打一行栈，这里自己记清楚。
            log.error("连接器健康探测整轮失败", e);
        } finally {
            // 不能直接 isHeldByCurrentThread()/unlock()：调度线程在进程关闭时可能已被中断，见 releaseQuietly；
            // 关闭回调也可能已经替本线程放过了，见 release。
            release(held);
        }
    }

    private void doSweep() {
        List<Connection> rows = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getStatus, "ACTIVE"));
        if (rows.isEmpty()) {
            return;
        }
        int ok = 0;
        int bad = 0;
        for (Connection row : rows) {
            // 隧道未实现，探了也只会永远失败，没必要每轮都去撞一次。
            String transport = row.getTransport();
            if (transport != null && !transport.isBlank() && !"direct".equalsIgnoreCase(transport)) {
                continue;
            }
            if (probeOne(row)) {
                ok++;
            } else {
                bad++;
            }
        }
        if (bad > 0) {
            log.warn("连接器健康探测完成：{} 正常，{} 异常", ok, bad);
        } else {
            log.debug("连接器健康探测完成：{} 正常", ok);
        }
    }

    /** @return 是否健康 */
    private boolean probeOne(Connection row) {
        try {
            ConnectorInstance inst = loader.load(row);
            Connector connector = registry.require(inst.kind());
            // 直接 open、不经网关——有意为之，不是漏网。理由见类注释「ping 刻意不走 ConnectorGateway」：
            // 走网关每条连接每天多写约 288 行审计，还会因为抢不到并发许可把一条忙而健康的连接记成异常。
            try (ConnectorSession session = connector.open(inst)) {
                session.ping();
            }
            writeBack(row, "HEALTHY", null);
            return true;
        } catch (ConnectorException e) {
            // safeDetail 已脱敏，可以直接写进库并展示给客户。
            writeBack(row, "UNHEALTHY",
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
            return false;
        } catch (Exception e) {
            // 没归类的异常：原始信息只进日志，写回库的文案用固定句子。
            log.warn("连接器健康探测出现未归类异常 connectorId={} kind={}", row.getId(), row.getKind(), e);
            writeBack(row, "UNHEALTHY", "探测失败，请在管理台点「测试连接」查看详情");
            return false;
        }
    }

    /**
     * 只写健康三件套。<b>刻意用 LambdaUpdateWrapper 精确列更新，而不是 updateById(entity)</b>：
     * 后者会把实体上的全部非空字段一起写回，而这一行是几分钟前查出来的快照——
     * 期间有人在管理台改过配置的话，就会被这次探测悄悄覆盖回去。
     */
    private void writeBack(Connection row, String state, String reason) {
        try {
            connectionMapper.update(null, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, row.getId())
                    .set(Connection::getHealthState, state)
                    .set(Connection::getHealthCheckedAt, new Date())
                    .set(Connection::getHealthReason, reason));
        } catch (Exception e) {
            log.warn("连接器健康态写回失败 connectorId={}", row.getId(), e);
        }
    }

    // ================================================================ 二、结构快照的低频刷新

    /**
     * 定时检查「有没有快照过期的连接」。间隔由占位符 {@code connector.schema-refresh.check-interval-ms}
     * 控制（默认 10 分钟；毫秒整数或 ISO-8601 时长如 {@code PT10M} 都行，见 {@link #setCheckInterval}），
     * {@code @Scheduled} 直接读它，<b>改了要重启才生效</b>。
     * 真正决定「每条连接多久刷一次」的是 {@code connector.schema-refresh.interval-hours}，那个不用重启。
     *
     * <p>initialDelay 10 分钟：刚启动时连接池、Nacos 配置都在预热。到期与否落在库里，晚几分钟不会漏掉谁。
     *
     * <p>{@code @Scheduled} 挂在 void 包装方法上，与 {@code PendingWriteService.sweepExpired} 一致。
     */
    @Scheduled(fixedDelayString = "${connector.schema-refresh.check-interval-ms:600000}", initialDelay = 600_000L)
    public void scheduleSchemaRefresh() {
        dispatchSchemaRefresh();
    }

    /**
     * 把一轮刷新<b>交给本类自己的线程</b>，调度线程立刻返回。
     *
     * <h3>★ 为什么不直接在调度线程上刷</h3>
     * Spring 默认的 TaskScheduler 是<b>单线程</b>的（{@code CustomerDataSourceManager} 的关池线程就是为此单开的）：
     * 本类的 ping、{@code OrphanRunReconciler}、连接池空闲回收、过期写请求清理，全在那一个线程上排队。
     * 一次刷新是 1 + N 次跨公网的 {@code information_schema} 往返，大库上几十秒，一轮几条就是几分钟——
     * 放在调度线程上，ping 会被整段推迟，孤儿生成的清理也跟着推迟。
     *
     * <p>单线程 + {@link #refreshInFlight}：上一轮没跑完时本次检查直接跳过。堆起来的检查没有意义——
     * 到期与否由 synced_at 决定，下一次检查自然会看到。
     *
     * @return 这次是否真的派发了一轮（给单测用）
     */
    boolean dispatchSchemaRefresh() {
        if (closing || !properties.getSchemaRefresh().isEnabled()) {
            return false;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            log.debug("结构定时刷新：上一轮还没跑完，本次检查跳过");
            return false;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshDueSchemas();
                } catch (Exception e) {
                    // 这个线程上抛出去没有任何人接，只会让下一轮看起来莫名其妙地没跑。
                    log.error("结构定时刷新整轮失败，下一次检查会重试", e);
                } finally {
                    refreshInFlight.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // 只会发生在进程关闭、执行器已经 shutdown 之后。
            refreshInFlight.set(false);
            return false;
        }
    }

    /**
     * 进程关闭：停掉刷新线程，并<b>当场放掉本实例手上的跨副本锁</b>，不等租期。
     *
     * <h3>★ 为什么不能指望拿锁的线程自己放</h3>
     * 部署恰好落在一轮刷新中间时，刷新线程多半卡在一次 JDBC socket 读上（客户库的 information_schema 往返），
     * 而<b>中断叫不醒 socket 读</b>。于是：{@code shutdownNow()} 立刻返回，Redisson 随后被销毁，
     * 那条守护线程跟着进程一起死掉，它的 finally 一行都没跑——锁留在 Redis 里等租期（{@code sweepTimeoutMinutes}，默认 30 分钟）。
     * 新进程起来之后，每一次检查都以为「另一个副本正在执行」而<b>静默跳过</b>，最长半小时没有任何定时刷新，日志里只有 debug。
     * 健康探测那把锁同理（调度线程卡在 ping 上），只是租期短得多。
     *
     * <h3>★ 顺序，以及为什么这时放得掉</h3>
     * <ol>
     *   <li>先置 {@link #closing}：此后 {@link #tryHold} 不再抢锁，刷新循环不再开始下一条。</li>
     *   <li>{@code shutdownNow()}：没卡住的刷新线程会在下一条之前停下。</li>
     *   <li>{@link #heldLocks} 里还在的每一把，用<b>拿锁那条线程的 id</b> 放掉（{@code unlockAsync(threadId)}）。
     *       Redisson 此时还活着：本类的构造器依赖 {@code RedissonClient}，Spring 销毁时先销毁依赖方、后销毁被依赖方。</li>
     * </ol>
     *
     * <h3>★ 只放自己那一份</h3>
     * {@code unlockAsync(threadId)} 是一段原子的 Lua：锁的持有者不是「本客户端 + 那条线程」就什么都不删，
     * 以 {@code IllegalMonitorStateException} 结束——租期已过、锁已被别的副本拿走时正是这种情况，记一条 INFO 就走。
     * <b>绝不用 {@code forceUnlock()}</b>：那会删掉别人正拿着的锁，让两个副本同时刷。
     *
     * <p>代价：放锁之后，那条卡住的刷新在 socket 读返回之后可能还会把手上这一条跑完，
     * 恰好与别的副本撞在同一条连接上。那是 {@link #refreshDueSchemas()}「与手工刷新撞车」里写过的那一种；
     * 实际上几乎撞不上——进程几秒内就退出了，而别的副本最早也要到它自己的下一次检查才会去抢锁。
     */
    @PreDestroy
    void releaseOnShutdown() {
        closing = true;
        refreshExecutor.shutdownNow();
        for (HeldLock held : heldLocks) {
            if (heldLocks.remove(held)) {
                forceRelease(held);
            }
        }
    }

    /**
     * 一轮结构刷新：挑出快照过期的连接，最旧的先刷，最多 {@code maxPerSweep} 个<b>占名额的结局</b>（繁忙不占，见下）。
     * 同步执行——定时触发走 {@link #dispatchSchemaRefresh()}，单测直接调它。
     *
     * <h3>★ 只挑已经有快照的连接</h3>
     * 选人从 {@code connector_schema} 出发（按 connector_id 分组），所以从没拉过结构的连接<b>按构造</b>进不来。
     * 首份快照归推导的补拉管：那一次拉取和推导在同一个认领之下，是「接入即触发」的一部分。
     * 在这里替它造首份快照，等于在推导不知情时和它抢同一件事，而一份「全是新增」的首份快照对漂移检测没有任何信息量。
     *
     * <h3>★ 判定用的是轮到它那一刻<b>重读</b>的连接行</h3>
     * 一轮之内串行刷好几条，每条是几十秒的客户库往返。本轮开始时选人读到的 semantic_status / health_state /
     * semantic_synced_at，轮到后面那条时可能早就不对了：前一条刷新期间有人点了「重新生成」、ping 把它判成了异常、
     * 推导刚写了 READY——拿旧行判，下面那几条跳过规则恰好在最该生效的时候失效。所以每条在判定之前按 id 重读一次
     * （主键查询，只投影判定要用的列）。连接已经被删了就跳过；重读本身失败也跳过——拿不到此刻的行，就不拿旧行去赌。
     *
     * <h3>★ 刷之前先判掉的几种（不算失败、不写标记、不占名额）</h3>
     * <ul>
     *   <li><b>类型不提供自描述（HTTP）/ 接入探测没确认 DESCRIBE / 已停用 / 走隧道 / 行上没有租户。</b>
     *       必须在调 {@code refresh()} <b>之前</b>判掉，不能靠 catch：{@code refresh()} 对这些情况抛
     *       {@code ServiceException}，而 {@code ServiceException} 的<b>构造器自己就 log.error</b>——
     *       catch 住也拦不下那一行。对 HTTP 连接那就是每个到期周期一条 ERROR，
     *       而一条每轮都响的 ERROR 很快就会让所有人学会无视日志。</li>
     *   <li><b>健康探测判为 UNHEALTHY</b>（只在 ping 开着时才信这个态——关着时它可能是很久以前的）。
     *       连不上的库刷了也是失败。它仍然到期、仍然排在前面，恢复健康后的下一次检查就会被刷。</li>
     *   <li><b>语义层推导正在跑</b>（RUNNING 且认领未超过 {@value #DERIVE_CLAIM_LIVE_MINUTES} 分钟）。
     *       推导落库前要比对快照指纹（行数 + 最新 synced_at），而 {@code refresh()} 整批删了重插、每行盖新的
     *       synced_at——<b>结构一个字没变，指纹也会变</b>，推导就把整批模型产出作废、写 FAILED「请重新生成」。
     *       一次定时刷新不该悄悄毁掉一次有人点的「重新生成」。</li>
     *   <li><b>推导 READY 不到 {@value #VALIDATION_QUIET_MINUTES} 分钟</b>：紧随其后的采样验证阶段每条探查都要抢
     *       每实例许可。这时再叠一次刷新占着一个许可，那条连接的两个许可就全在平台手里，
     *       Agent 的查询等 2 秒拿不到就是 RATE_LIMITED。</li>
     *   <li><b>冷却中</b>；<b>有繁忙前科、而本轮繁忙预算已经用完</b>：见下两节。</li>
     * </ul>
     *
     * <h3>★ 结局：逐条隔离，分开处置</h3>
     * 共同的前提：快照没有前进的连接，下一次检查<b>仍然到期、仍然排第一</b>。凡是没让 synced_at 前进的结局，
     * 要么记一个标记让它让位，要么就得接受它每 10 分钟都去撞一次——「最旧优先」会变成饿死所有人的规则。
     * <ul>
     *   <li><b>其它失败</b>：一条 WARN，占名额，并在 Redis 记 {@code intervalHours} 小时的冷却。没有冷却的话，
     *       一条持续失败的连接（大库 catalog 超时、账号看不了 information_schema……）快照永远最旧、永远排第一，
     *       每 10 分钟占掉一个名额、打一次客户库、记一条 WARN。失败的连接一多，健康的连接就<b>一个都轮不到</b>。
     *       有了冷却，失败的连接和健康的一样，大约每个间隔试一次。</li>
     *   <li><b>繁忙</b>（{@link #isBusy}）：不是故障，是「现在不方便」。日志 INFO、不算失败、<b>不占名额</b>；
     *       记一个短冷却（{@link #busyCooldownMinutes}，默认 {@value #BUSY_COOLDOWN_MINUTES} 分钟）让位，
     *       冷却过后标记再留一个间隔，作为「繁忙前科」。为什么这样分，见下一节。</li>
     *   <li><b>{@code refresh()} 拒绝落库</b>（返回的 {@code guardNote} 非空。今天有两种：目录拉回来是空的，它不肯拿这份可疑的快照
     *       覆盖掉原来那份；或者落库前发现已有一次更晚开始的拉取先落了库，本次整个作废）：当成一次<b>成功的无变化</b>——
     *       日志 INFO、不算失败、不记 WARN，占名额，并记一个 {@code intervalHours} 小时的标记。
     *       空目录那一种 synced_at 没有前进：不记的话，一条权限被收走、目录永远拉回来是空的连接会每 10 分钟占一个名额、
     *       打一次客户库、记一条 INFO，而每次的结论都一样；记了，它的下一次尝试与正常刷新完的连接一样，在一个间隔之后。
     *       「更晚的拉取先落了库」那一种，库里的快照本来就是刚拉的、一个间隔之内不会到期，这个标记对它多余但无害。</li>
     *   <li><b>被中断，或进程已经在关闭</b>：不算失败、不记冷却，这一轮就此停下。关闭开始之后抛出的任何异常都归这一类——
     *       那多半是被拆掉的依赖造成的，不是这条连接的问题。放跨副本锁之前要先清掉中断标记，见 {@link #releaseQuietly}。</li>
     *   <li>标记读写失败时放行（当作没有标记）：最坏是某条连接多试几次，不影响正确性。</li>
     * </ul>
     *
     * <h3>★ 繁忙不占名额；有繁忙前科的重试另有预算</h3>
     * 繁忙从前也占名额，只靠冷却让位。那只是把饿死的门槛抬高了，没有拆掉：冷却 3 个检查间隔、每次 3 个名额，
     * 9 条以上持续繁忙的连接（整天有 Agent 在问数、许可常年被占满的库）就能在<b>每一次</b>检查里轮流把名额占光，
     * 排在它们后面的到期连接一条都轮不到——而日志是 INFO，看起来一直在「干活」。现在的规则：
     * <ul>
     *   <li><b>名额只数占名额的结局</b>（成功、拒绝落库、失败、被中断）。繁忙不数。</li>
     *   <li><b>首次撞上的繁忙不设上限。</b>没撞之前认不出它会忙；给它设上限、数到了就停，等于在它身后的健康连接前面
     *       重新立起一堵墙。它的量有另一种上界：撞一次就留下前科，前科期内再轮到它就不算「首次」——每条连接每个前科窗口至多一次。</li>
     *   <li><b>有前科的重试有预算</b>（{@link #busyRetryBudget}）：本轮繁忙结局（首次的也算）数到预算，
     *       后面有前科的连接就不再去撞（计 deferred，不写标记），留给下一轮；没有前科的连接照常去刷。</li>
     * </ul>
     * 由此，<b>一条没有繁忙前科的到期连接，这一轮轮不到它只可能是因为</b>：名额被比它旧的占名额结局用完（这就是最旧优先本身）、
     * 本轮时间预算用完、或进程在关闭。繁忙——已知的还是首次的——都不在这张单子上。
     * 一次繁忙多半是秒级（等 2 秒许可 / 限流桶直接拒 / 连接数满直接拒），要靠它把 {@code sweepTimeoutMinutes} 的时间预算耗光，
     * 得在一轮里撞上几百条首次繁忙的连接。
     * 一条<b>有前科、但其实已经不忙了</b>的连接，最坏等到前科过期（{@link #busyMemoryMinutes}：冷却 + 一个间隔）就回到普通队列——
     * 不会比一条失败过的连接等得更久；轮到重试且刷成了，前科当场清掉。
     *
     * <h3>★ 与手工「刷新结构」、推导补拉撞在同一条连接上时会发生什么</h3>
     * {@code refresh()} 分三段：事务外的网络往返 → {@code txTemplate} 里的「删全部 + 重插」→ 事务外的漂移处置。
     * <ul>
     *   <li><b>与手工刷新撞车</b>（跨副本锁租期到了、另一个副本又刷一次，也是这一种）：
     *     <ol>
     *       <li>网络段两边各占这条连接的一个每实例许可（默认共 2 个）。撞车期间 Agent 查询要等许可，
     *           等 2 秒拿不到就是 RATE_LIMITED——这是撞车唯一用户看得见的代价，持续一次刷新的时长。</li>
     *       <li>落库段两个事务被 {@code DELETE ... WHERE connector_id = ?} 的行锁串行化，后提交的覆盖先提交的。
     *           删与插在同一个事务里，所以最终快照一定是<b>某一次拉取的完整结果</b>，不会是两次的拼接。
     *           万一 InnoDB 判了死锁，被回滚的那一次抛异常（这边记失败与冷却，手工那边看到报错可以再点一次），
     *           快照保留赢家那一份。</li>
     *       <li>后一个事务的 diff 可能对着撞车<b>前</b>的快照算——同一批 ObjectDiff 报两遍、applyDrift 跑两遍；
     *           也可能对着前一个<b>已提交</b>的快照算——那样它的 diff 里没有前一次已经报过的 REMOVED。<br>
     *           <b>保证了的：</b>{@code applyDrift} 判「表还在不在」看的是<b>本次拉取自己</b>的处境，不是 diff。
     *           所以两次拉取看到的是<b>同一份</b>结构时，谁先落库、处置跑几遍，语义状态都一样；表消失之后，
     *           每一次看到它仍然不在的刷新都让挂在它上面的行（OBJECT / FIELD / JOIN）保持 STALE，挂在整条连接上的口径
     *           （METRIC / CAVEAT）在 detail_json 里记着「是哪张消失的表让我过期的」，要等那张表重新被描述到才撤销。
     *           定时刷新一直开着，不会把过期标记冲掉。<br>
     *           <b>没保证的：两次拉取跨着一次结构变更</b>（A 在客户 DROP 之前开始拉、B 在 DROP 之后开始拉）。
     *           {@code applyDrift} 只看得见手上这一份拉取，分不出它比已经处置过的那份旧：较旧的 A 若在 B 之后处置，
     *           就按「描述到了」把 B 刚标的 STALE 撤销，过期的说明书重新被当成事实注入。
     *           <ul>
     *             <li><b>已经关上的一种：A 在 B 之后落库。</b>{@code ConnectorSchemaService.refresh()} 的「旧拉取不许覆盖新快照」
     *                 在落库事务里锁住连接行、比拉取开始时刻，库里已有更晚开始的拉取就整次作废——不落库、不处置、不派发，
     *                 返回 guardNote（本类按「拒绝落库」记）。</li>
     *             <li><b>仍然开着的一种：A 先落库、B 后落库，漂移处置却是 B 先 A 后。</b>那道闸只在落库那一刻比，
     *                 漂移处置在提交之后、锁外才跑，A 的处置照样撤销 B 刚标的 STALE——快照是对的（B 的），语义行却退回 DROP 之前，
     *                 直到下一次刷新再标回来。窗口是「A 提交到 A 处置完」那一小段，B 得恰好在其间提交并处置完才撞得上。</li>
     *           </ul>
     *           <b>本类关不了这一类</b>：本类决定的是什么时候去刷，决定不了两次拉取落库与处置的先后——租期到了而那一条还没跑完、
     *           手工与定时同时点，都是合法的撞车，跨副本锁只让它少见，不让它不可能。<br>
     *           差别也在<b>返回值</b>上：一次消失只出现在<b>第一次</b>看到它的那次刷新的 diffs / semanticStaledByObject 里。
     *           先看到的若是定时刷新，超管随后手工点的那一次对这张表显示「无变化」——过期标记已经在语义行上了。
     *           要回答「哪些说明书因表消失而失效」，读语义行的 STALE，不读某一次刷新的返回值。</li>
     *     </ol></li>
     *   <li><b>与推导补拉（bootstrap）</b>：按构造互斥。补拉只在快照为空时发生，这里只挑有快照的；
     *       补拉刚写出的快照要 {@code intervalHours} 之后才到期。</li>
     *   <li><b>与正在跑的推导、刚 READY 的验证阶段</b>：跳过，理由见上；判的是轮到它那一刻重读的行，
     *       所以本轮开始之后才开始的推导也挡得住。手工点的「验证表关系」没有落库标记，
     *       挡不住；撞上时除了许可叠加，还有一处：{@code applyDrift} 用 {@code updateById} 回写<b>本轮状态翻转</b>的行，
     *       会把它读到之后才写进来的验证结论覆盖回旧值。方向是保守的（变回「未验证」，不会凭空声称验证过），
     *       且只影响恰好在这一轮发生结构漂移的行。</li>
     * </ul>
     *
     * @return 本轮计数
     */
    RefreshTally refreshDueSchemas() {
        RefreshTally tally = new RefreshTally();
        ConnectorProperties.SchemaRefresh cfg = properties.getSchemaRefresh();
        if (!cfg.isEnabled() || cfg.getIntervalHours() <= 0 || cfg.getMaxPerSweep() <= 0) {
            return tally;
        }
        int budgetMinutes = Math.max(1, cfg.getSweepTimeoutMinutes());
        HeldLock held = null;
        try {
            // waitTime=0：别的副本在刷就放弃本次检查。显式租期而不是看门狗续租，理由见
            // ConnectorProperties.SchemaRefresh#sweepTimeoutMinutes——卡死的刷新不能让全平台的定时刷新一起停摆。
            held = tryHold(REFRESH_LOCK_KEY, budgetMinutes, TimeUnit.MINUTES, "结构定时刷新");
            if (held == null) {
                log.debug("结构定时刷新：另一个副本正在执行（或本进程正在关闭），本次跳过");
                return tally;
            }
            runRefreshSweep(cfg, budgetMinutes, tally);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 被 shutdownNow 打断的这一轮走到这里时线程带着中断标记，直接调 Redisson 会抛，见 releaseQuietly；
            // 卡住的这一轮则可能早已由关闭回调替它放过，见 release。
            release(held);
        }
        return tally;
    }

    private void runRefreshSweep(ConnectorProperties.SchemaRefresh cfg, int budgetMinutes, RefreshTally tally) {
        long start = System.currentTimeMillis();
        Date dueBefore = new Date(start - cfg.getIntervalHours() * 3_600_000L);
        // 选人是跨租户的，所以 runAsSystem。刷新本身【不在】这里面，见 refreshOne。
        List<Connection> due = TenantContext.runAsSystem(() -> selectDue(dueBefore));
        tally.due = due.size();
        long checkMs = checkIntervalMs;
        long busyCooldownMs = busyCooldownMinutes(checkMs) * 60_000L;
        int busyBudget = busyRetryBudget(cfg);

        for (Connection candidate : due) {
            if (closing || Thread.currentThread().isInterrupted()) {
                // 进程在关闭：别再开始下一次客户库往返。两个都要看——中断标记可能被下游吞掉，
                // 而关闭回调此刻多半已经替本线程把锁放了，再刷一条就是在没有锁的情况下刷。
                break;
            }
            if (tally.attempted >= cfg.getMaxPerSweep()) {
                break;
            }
            if (System.currentTimeMillis() - start > budgetMinutes * 60_000L) {
                log.info("结构定时刷新：本轮时间预算 {} 分钟已用完，其余到期的连接留给下一次检查", budgetMinutes);
                break;
            }
            // ★ 跳过规则必须对着此刻的行判：candidate 是本轮开始时读的，前面几条刷新的几十秒里它可能已经变了。
            //   理由见 refreshDueSchemas 的「判定用的是轮到它那一刻重读的连接行」。
            Connection row;
            try {
                row = rereadForRefresh(candidate.getId());
            } catch (Exception e) {
                tally.skipped++;
                log.warn("结构定时刷新：重读连接行失败，这条本轮跳过 connectorId={}", candidate.getId(), e);
                continue;
            }
            if (row == null) {
                tally.skipped++;
                log.debug("结构定时刷新跳过 connectorId={}：连接已被删除", candidate.getId());
                continue;
            }
            String skip = skipReason(row, System.currentTimeMillis());
            if (skip != null) {
                tally.skipped++;
                log.debug("结构定时刷新跳过 connectorId={}：{}", row.getId(), skip);
                continue;
            }
            MarkState mark = readMark(row.getId(), System.currentTimeMillis(), busyCooldownMs);
            if (mark == MarkState.COOLING) {
                tally.cooling++;
                continue;
            }
            if (mark == MarkState.BUSY_BEFORE && tally.busy >= busyBudget) {
                // 有繁忙前科、本轮的繁忙预算已经用完：这一轮不去撞它。不写标记——它下一轮照样是「有前科、可以重试」。
                // 没有前科的连接不走这条：见 refreshDueSchemas「繁忙不占名额」。
                tally.deferred++;
                continue;
            }
            refreshOne(row, cfg, tally, mark, checkMs);
        }

        if (tally.attempted > 0 || tally.busy > 0) {
            log.info("结构定时刷新：到期 {} 条，占名额 {} 条（成功 {}、拒绝落库 {}、失败 {}、被中断 {}），繁忙 {} 条（不占名额），"
                            + "跳过 {}，冷却中 {}，有繁忙前科本轮未重试 {}",
                    tally.due, tally.attempted, tally.refreshed, tally.guarded, tally.failed, tally.aborted, tally.busy,
                    tally.skipped, tally.cooling, tally.deferred);
        } else if (tally.due > 0) {
            // 有到期的但一条都没刷：每 10 分钟一条 INFO 就是噪音，原因逐条在上面的 debug 里。
            log.debug("结构定时刷新：到期 {} 条，均被跳过（{}）、在冷却中（{}）或暂缓重试（{}）",
                    tally.due, tally.skipped, tally.cooling, tally.deferred);
        }
    }

    /**
     * 挑出快照过期的连接，<b>按快照从旧到新</b>排好。只读我们自己的库。
     *
     * <p>一条连接的快照时间取它所有行里最大的 synced_at：{@code refresh()} 整批删了重插、每行同一个 now，
     * 最大值就是最近一次刷新的时间（与推导那边 snapshotStamp 的取法一致）。synced_at 为空的存量行当作到期、排最前。
     *
     * <p>这里用字符串列名的 {@code QueryWrapper}，而不是本仓库惯用的 LambdaQueryWrapper：
     * 聚合投影和 HAVING 没法用方法引用表达。
     *
     * <p>连接那一侧只投影判定要用的列：{@code connection} 行带着凭据密文和配置 JSON，选人用不上，
     * 没理由让密文在内存里多走一遍。{@code refresh()} 自己会再读完整的行。
     */
    List<Connection> selectDue(Date dueBefore) {
        List<ConnectorSchema> ages = schemaMapper.selectList(new QueryWrapper<ConnectorSchema>()
                .select("connector_id", "MAX(synced_at) AS synced_at")
                .groupBy("connector_id")
                .having("MAX(synced_at) IS NULL OR MAX(synced_at) < {0}", dueBefore)
                .orderByAsc("MAX(synced_at)"));
        if (ages == null || ages.isEmpty()) {
            return List.of();
        }
        Set<Long> idsOldestFirst = new LinkedHashSet<>();
        for (ConnectorSchema a : ages) {
            if (a.getConnectorId() != null) {
                idsOldestFirst.add(a.getConnectorId());
            }
        }
        if (idsOldestFirst.isEmpty()) {
            return List.of();
        }
        Map<Long, Connection> byId = new HashMap<>();
        for (Connection c : loadForRefresh(idsOldestFirst)) {
            if (c != null && c.getId() != null) {
                byId.put(c.getId(), c);
            }
        }
        // 顺序以快照年龄为准，不以连接表的返回顺序为准；连接行不在了（被删）的快照行直接略过。
        List<Connection> out = new ArrayList<>(idsOldestFirst.size());
        for (Long id : idsOldestFirst) {
            Connection c = byId.get(id);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 按 id 重读一条连接<b>此刻</b>的状态，给 {@link #skipReason} 判定用。理由见 {@link #refreshDueSchemas()}
     * 「判定用的是轮到它那一刻重读的连接行」。
     *
     * <p>在系统模式里读，与选人同理：这时线程上还没设租户（租户在 {@link #refreshOne} 里才设），
     * 不开系统模式的话租户拦截器回落到哨兵，一行都读不到——每条连接都会被当成「已删除」静默跳过。
     *
     * @return 连接行（只含判定要用的列）；连接已被删除时 {@code null}。读失败照常抛出，由调用方按跳过处理。
     */
    Connection rereadForRefresh(Long connectorId) {
        List<Connection> rows = TenantContext.runAsSystem(() -> loadForRefresh(List.of(connectorId)));
        if (rows != null) {
            for (Connection c : rows) {
                if (c != null && connectorId.equals(c.getId())) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * 判定要用的那几列。选人与重读<b>共用这一份投影</b>：两处各写一份迟早分叉，
     * 漏投的那一列读出来是 null，对应的跳过规则就不声不响地不再生效。
     */
    private List<Connection> loadForRefresh(Collection<Long> ids) {
        return connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .select(Connection::getId, Connection::getTenantId, Connection::getName, Connection::getKind,
                        Connection::getStatus, Connection::getTransport, Connection::getCapabilityFlags,
                        Connection::getHealthState, Connection::getSemanticStatus,
                        Connection::getSemanticClaimAt, Connection::getSemanticSyncedAt)
                .in(Connection::getId, ids));
    }

    /** @return 为什么这条这一轮不该刷；{@code null} = 可以刷。理由见 {@link #refreshDueSchemas()}。 */
    String skipReason(Connection row, long nowMs) {
        if (row.getTenantId() == null || row.getTenantId().isBlank()) {
            return "这一行没有租户，无法以真实租户身份访问";
        }
        if (!"ACTIVE".equals(row.getStatus())) {
            return "连接已停用";
        }
        String transport = row.getTransport();
        if (transport != null && !transport.isBlank() && !"direct".equalsIgnoreCase(transport)) {
            return "内网隧道尚未实现";
        }
        try {
            Connector connector = registry.require(ConnectorInstanceLoader.normalizeKind(row.getKind()));
            if (!connector.declaredCapabilities().contains(Capability.DESCRIBE)) {
                return "该连接器类型不提供结构自描述";
            }
        } catch (RuntimeException e) {
            return "无法识别的连接器类型";
        }
        if (!ConnectorGateway.parseCapabilities(row.getCapabilityFlags()).contains(Capability.DESCRIBE)) {
            return "接入探测没有确认自描述能力";
        }
        if (properties.getHealth().isEnabled() && "UNHEALTHY".equals(row.getHealthState())) {
            return "健康探测判为异常";
        }
        if (ConnectorSemanticDeriveService.SEM_RUNNING.equals(row.getSemanticStatus())
                && row.getSemanticClaimAt() != null
                && nowMs - row.getSemanticClaimAt().getTime() < DERIVE_CLAIM_LIVE_MINUTES * 60_000L) {
            return "语义层推导进行中";
        }
        if (row.getSemanticSyncedAt() != null
                && nowMs - row.getSemanticSyncedAt().getTime() < VALIDATION_QUIET_MINUTES * 60_000L) {
            return "语义层刚生成，采样验证可能还在跑";
        }
        return null;
    }

    /**
     * 刷一条。<b>不抛异常</b>：一条失败不能中断这一轮。
     *
     * <h3>★ 以那一行的真实租户身份跑，而且刻意<b>不在</b> runAsSystem 里跑</h3>
     * {@code refresh()} 走 {@link ConnectorGateway#executeAsPlatform}，它要的是<b>真实</b>租户：
     * {@code runAsSystem} 只打开系统模式，{@code CURRENT_TENANT} 仍是空的，网关第 2 步照样拒——
     * 而且拒的文案是「缺少租户上下文」，读起来像是客户那边出了问题。
     *
     * <p>在「设了租户」之外还要<b>退出系统模式</b>（所以选人的 runAsSystem 在调本方法之前就已经结束了）：
     * 系统模式下租户拦截器对 {@code connection} / {@code connector_schema} / {@code connector_semantic}
     * 一律不加租户条件，第二道篱笆就没了。现在这条路径的线程上下文与超管手工点「刷新结构」时完全一样。
     *
     * <p>原来的租户在 finally 里恢复（定时线程上本来是空的，就清掉），异常路径同样恢复——
     * 这是一个复用的线程，漏清一次，下一条连接就会带着上一个租户的身份去刷。
     *
     * @param mark 刷之前读到的标记；{@link MarkState#BUSY_BEFORE} 的这条若刷成了，前科当场清掉
     */
    private void refreshOne(Connection row, ConnectorProperties.SchemaRefresh cfg, RefreshTally tally,
                            MarkState mark, long checkMs) {
        String previousTenant = TenantContext.get();
        TenantContext.set(row.getTenantId());
        try {
            ConnectorSchemaService.SnapshotResult r = schemaService.refresh(row.getId());
            tally.attempted++;
            if (r != null && r.getGuardNote() != null) {
                // refresh() 不肯拿这次拉回来的结果覆盖快照（空目录，或已有更晚开始的拉取先落了库）。不是失败。
                // 空目录那一种 synced_at 没前进：不记标记，它下一次检查仍然排第一、再撞一次、得出同一个结论。
                // 记一个间隔，与正常刷新完的连接同一个节奏；对「更晚的拉取已落库」那一种多余但无害。
                tally.guarded++;
                startCooldown(row.getId(), cfg.getIntervalHours(), TimeUnit.HOURS, OUTCOME_GUARDED);
                log.info("结构定时刷新：refresh() 拒绝落库，原快照保持不变，{} 小时后再看 connectorId={} tenantId={}：{}",
                        cfg.getIntervalHours(), row.getId(), row.getTenantId(), r.getGuardNote());
            } else {
                tally.refreshed++;
                if (mark == MarkState.BUSY_BEFORE) {
                    // 有前科的这条这次刷成了：它已经不忙了。不清的话，前科窗口里它仍按「已知繁忙」受繁忙预算约束。
                    clearMark(row.getId());
                }
                log.debug("结构定时刷新完成 connectorId={} 对象数={}", row.getId(), r == null ? null : r.getObjectCount());
            }
        } catch (Exception e) {
            if (closing || interruptedBy(e)) {
                // 进程在关闭（@PreDestroy 打断了这条线程，或关闭已经开始、这次失败多半是被拆掉的依赖造成的）。
                // 那不是这条连接的问题：不算失败、不记冷却——记了的话，每次部署恰好撞上的那条连接都会被平白推迟一个间隔，
                // 而本仓库 push main 即部署。中断标记还回去，循环看到它（或 closing）就不再开始下一条。
                if (interruptedBy(e)) {
                    Thread.currentThread().interrupt();
                }
                tally.attempted++;
                tally.aborted++;
                log.info("结构定时刷新被中断（进程关闭中），这条留给下一次 connectorId={}", row.getId());
            } else if (isBusy(e)) {
                // 繁忙不是故障：不算失败、不占名额。但要让位，并留下前科，见 refreshDueSchemas「繁忙不占名额」。
                tally.busy++;
                long minutes = busyCooldownMinutes(checkMs);
                startCooldown(row.getId(), busyMemoryMinutes(checkMs, cfg.getIntervalHours()), TimeUnit.MINUTES,
                        OUTCOME_BUSY);
                log.info("结构定时刷新遇到繁忙（不占本轮名额），{} 分钟内让位给其它到期的连接 connectorId={}：{}",
                        minutes, row.getId(), e instanceof ServiceException se ? se.getRespMsg() : e.getMessage());
            } else if (e instanceof ServiceException se) {
                tally.attempted++;
                tally.failed++;
                startCooldown(row.getId(), cfg.getIntervalHours(), TimeUnit.HOURS, OUTCOME_FAILED);
                log.warn("结构定时刷新失败，{} 小时内不再自动重试 connectorId={} tenantId={} code={}：{}",
                        cfg.getIntervalHours(), row.getId(), row.getTenantId(), se.getRespCode(), se.getRespMsg());
            } else {
                // 没归类的异常（比如落库那一段撞了我们自己库的约束）。原始异常只进日志。
                tally.attempted++;
                tally.failed++;
                startCooldown(row.getId(), cfg.getIntervalHours(), TimeUnit.HOURS, OUTCOME_FAILED);
                log.warn("结构定时刷新出现未归类异常，{} 小时内不再自动重试 connectorId={} tenantId={}",
                        cfg.getIntervalHours(), row.getId(), row.getTenantId(), e);
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 这次失败是不是「繁忙」。
     *
     * <p><b>有原件就不读复印件。</b>cause 链上只要出现了 {@link ConnectorException}，就<b>只看它的错误码</b>是不是
     * {@code RATE_LIMITED}，不再看文案：文案是写给人看的，任何一处把「……请稍后重试」写进别的错误码
     * （仓库里熔断的文案就这么写），文案匹配就会把一次真失败认成繁忙。{@code refresh()} 今天带着 cause，走的就是这条。
     *
     * <p>cause 链上没有 {@code ConnectorException} 时，才退回认码 + {@link #BUSY_MARK}，且只认 INVALID_REQUEST /
     * SERVER_BUSY 两种码：宁可把繁忙错认成失败（多一条 WARN、晚一个间隔），也不要把失败错认成繁忙（只让位半小时就重撞、日志只有 INFO）。
     */
    static boolean isBusy(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (t instanceof ConnectorException ce) {
                return ce.getCode() == ConnectorErrorCode.RATE_LIMITED;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        if (!(e instanceof ServiceException se)) {
            return false;
        }
        if (ExceptionCode.SERVER_BUSY.getResultCode().equals(se.getRespCode())) {
            return true;
        }
        return ExceptionCode.INVALID_REQUEST.getResultCode().equals(se.getRespCode())
                && se.getRespMsg() != null
                && se.getRespMsg().contains(BUSY_MARK);
    }

    /**
     * 这次失败是不是进程关闭时的中断造成的。中断标记可能已经被下游清掉了（{@code await} 抛出时会清），
     * 所以除了看线程标记还要看 cause 链。
     */
    static boolean interruptedBy(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (t instanceof InterruptedException) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /** 一条连接的刷新标记此刻读出来是什么。读法见 {@link #classifyMark}。 */
    enum MarkState {
        /** 没有标记：照常刷。 */
        NONE,
        /** 冷却中：这一轮不刷、不占名额也不占繁忙预算。 */
        COOLING,
        /** 繁忙冷却已过、前科还在：可以重试，但本轮繁忙预算用完之后就不去撞它。 */
        BUSY_BEFORE
    }

    /** 读标记。<b>读失败按没有标记处理</b>（放行）：最坏是某条连接多试几次，不影响正确性。 */
    private MarkState readMark(Long connectorId, long nowMs, long busyCooldownMs) {
        Object raw;
        try {
            RBucket<Object> mark = redissonClient.getBucket(REFRESH_COOLDOWN_KEY_PREFIX + connectorId);
            raw = mark.get();
        } catch (Exception e) {
            log.debug("读取结构刷新标记失败，按没有标记处理 connectorId={}", connectorId, e);
            return MarkState.NONE;
        }
        return classifyMark(raw == null ? null : String.valueOf(raw), nowMs, busyCooldownMs);
    }

    /**
     * 标记值 → 此刻的处境。<b>读法只有这一份</b>，写法见 {@link #startCooldown}。
     *
     * <p>认不出来的值（将来别的写法、被人手工改过的键）一律按冷却：键有 TTL，最坏是这条连接晚一个 TTL 再刷；
     * 按「没有标记」处理的话，一个坏值就能让冷却整个失效。
     */
    static MarkState classifyMark(String value, long nowMs, long busyCooldownMs) {
        if (value == null) {
            return MarkState.NONE;
        }
        String busyPrefix = OUTCOME_BUSY + "@";
        if (!value.startsWith(busyPrefix)) {
            return MarkState.COOLING;
        }
        long at;
        try {
            at = Long.parseLong(value.substring(busyPrefix.length()));
        } catch (NumberFormatException e) {
            return MarkState.COOLING;
        }
        return nowMs - at < busyCooldownMs ? MarkState.COOLING : MarkState.BUSY_BEFORE;
    }

    /**
     * 标记放在 Redis 而不是进程内存：本仓库 push main 即部署，进程内的冷却每次部署都清零，
     * 与「时钟用落库的 synced_at」是同一个理由——进程的存活时长不是一个可靠的时钟。
     *
     * @param outcome 写进值里的结局名（{@value #OUTCOME_FAILED} / {@value #OUTCOME_BUSY} / {@value #OUTCOME_GUARDED}）
     */
    private void startCooldown(Long connectorId, long ttl, TimeUnit unit, String outcome) {
        try {
            RBucket<String> mark = redissonClient.getBucket(REFRESH_COOLDOWN_KEY_PREFIX + connectorId);
            mark.set(outcome + "@" + System.currentTimeMillis(), ttl, unit);
        } catch (Exception e) {
            log.debug("写结构刷新冷却标记失败 connectorId={} outcome={}", connectorId, outcome, e);
        }
    }

    /** 清掉一条连接的标记。清不掉只记 debug：最坏是它在前科窗口里多受几次繁忙预算的约束。 */
    private void clearMark(Long connectorId) {
        try {
            redissonClient.getBucket(REFRESH_COOLDOWN_KEY_PREFIX + connectorId).delete();
        } catch (Exception e) {
            log.debug("清结构刷新标记失败 connectorId={}", connectorId, e);
        }
    }

    /**
     * 繁忙冷却的实际分钟数：{@link #BUSY_COOLDOWN_MINUTES}，但<b>不短于 3 个检查间隔</b>。
     *
     * <p>冷却若短于检查间隔，下一次检查时它已经过期、照样排第一，等于没让。检查间隔是 Nacos 可调的，
     * 有人把它调大时这条让位规则会不声不响地失效——所以跟着检查间隔算，不指望谁记得一起改。
     * 默认配置（10 分钟一查）下是 30 分钟：一条持续繁忙的连接大约每 3～4 次检查才被重试一次。
     */
    static long busyCooldownMinutes(long checkIntervalMs) {
        long intervalMinutes = Math.max(1L, (checkIntervalMs + 59_999L) / 60_000L);
        return Math.max(BUSY_COOLDOWN_MINUTES, 3L * intervalMinutes);
    }

    /**
     * BUSY 标记的 TTL（分钟）= 繁忙冷却 + 一个刷新间隔。冷却之后多留的那一个间隔就是「繁忙前科」。
     *
     * <p><b>为什么要记前科：</b>要给「重试已知繁忙的连接」设上限，就得在撞之前认出它；只有冷却那一段记忆的话，
     * 冷却一过它就和一条从没忙过的连接无法区分——要么不设上限，要么设了上限就在健康的连接前面重新立起一堵墙。
     *
     * <p><b>为什么是一个间隔：</b>前科也是一条已经恢复的连接被「按已知繁忙排队」的最长时间。
     * 取失败的冷却时长——一条忙过的连接，最坏也不会比一条失败过的连接等得更久。
     */
    static long busyMemoryMinutes(long checkIntervalMs, int intervalHours) {
        return busyCooldownMinutes(checkIntervalMs) + Math.max(1, intervalHours) * 60L;
    }

    /**
     * 每一轮里「重试有繁忙前科的连接」的预算：本轮繁忙结局（首次撞上的也算）数到它，就不再去碰有前科的。
     *
     * <p>取 {@code maxPerSweep}，不单独开配置：一次繁忙多半是秒级（等 2 秒许可、限流桶直接拒、连接数满直接拒），
     * 与一次真刷新（1 + N 次往返）不在一个量级，没必要让人再多想一个数；跟着 maxPerSweep 走，调大吞吐时重试也跟着变多。
     */
    static int busyRetryBudget(ConnectorProperties.SchemaRefresh cfg) {
        return Math.max(1, cfg.getMaxPerSweep());
    }

    /**
     * 照抄 Spring 6.0 {@code ScheduledAnnotationBeanPostProcessor#toDuration(String, TimeUnit)} 的判法：
     * 前两个字符里有 {@code P} / {@code p} 就按 ISO-8601 时长解析，否则按毫秒整数（{@code @Scheduled} 的默认 timeUnit）。
     * 与它<b>完全一样</b>，才保证得了「{@code @Scheduled} 接受的写法，这里一定接受」。
     *
     * @return 毫秒；解析不了时 {@code null}
     */
    static Long parseCheckIntervalMs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            if (raw.length() > 1 && (isP(raw.charAt(0)) || isP(raw.charAt(1)))) {
                return Duration.parse(raw).toMillis();
            }
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isP(char ch) {
        return ch == 'P' || ch == 'p';
    }

    /**
     * 抢一把跨副本锁（{@code waitTime=0}），抢到就记进 {@link #heldLocks}。
     *
     * <p>「抢到」与「记下」之间若恰好插进了关闭回调，回调遍历时看不到这一项；所以记下之后再看一眼 {@link #closing}，
     * 看到了就自己当场放掉。{@code closing} 是 volatile、集合是并发集合：两边至少有一边看得见对方，
     * 而谁摘掉那一项谁放锁，所以也不会放两次。
     *
     * @return 抢到的锁；没抢到或进程正在关闭时 {@code null}
     */
    private HeldLock tryHold(String key, long leaseTime, TimeUnit unit, String what) throws InterruptedException {
        if (closing) {
            return null;
        }
        RLock lock = redissonClient.getLock(key);
        if (!lock.tryLock(0L, leaseTime, unit)) {
            return null;
        }
        HeldLock held = new HeldLock(lock, Thread.currentThread().getId(), what);
        heldLocks.add(held);
        if (closing) {
            release(held);
            return null;
        }
        return held;
    }

    /** 正常收尾放锁。已经被关闭回调摘走（并替本线程放过）的，这里什么都不做。 */
    private void release(HeldLock held) {
        if (held != null && heldLocks.remove(held)) {
            releaseQuietly(held.lock, held.what);
        }
    }

    /** 关闭回调替拿锁的那条线程放锁。只放那条线程的那一份，不抛异常，最多等 {@link #FORCE_RELEASE_WAIT_MS}。 */
    private static void forceRelease(HeldLock held) {
        try {
            RFuture<Void> done = held.lock.unlockAsync(held.threadId);
            if (!done.awaitUninterruptibly(FORCE_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("{}：进程关闭时释放跨副本锁超时（{} 毫秒），锁会在租期到后自动过期", held.what, FORCE_RELEASE_WAIT_MS);
            } else if (done.isSuccess()) {
                log.info("{}：进程关闭，已替仍在执行中的线程（id={}）释放跨副本锁，下一次检查不必等租期", held.what, held.threadId);
            } else if (done.cause() instanceof IllegalMonitorStateException) {
                log.info("{}：进程关闭时这把锁已不在本实例手上（租期已过，可能已被别的副本拿走），不动它", held.what);
            } else {
                log.warn("{}：进程关闭时释放跨副本锁失败，锁会在租期到后自动过期", held.what, done.cause());
            }
        } catch (Exception e) {
            log.warn("{}：进程关闭时释放跨副本锁失败，锁会在租期到后自动过期", held.what, e);
        }
    }

    /**
     * 释放跨副本锁：<b>先清掉中断标记、放完再还回去</b>，并且<b>不抛异常</b>。拿锁的线程自己收尾时走这里。
     *
     * <p>本类的两件活都可能在进程关闭时被中断：刷新线程被 {@code @PreDestroy} 的 {@code shutdownNow} 打断，
     * ping 所在的调度线程在调度器关闭时同理。Redisson 的 {@code isHeldByCurrentThread()} / {@code unlock()}
     * 都是「发一条命令 + 在当前线程上等响应」：线程带着中断标记时那一步等待立刻被打断、命令还没回结果，
     * 于是抛 {@code RedisException}（本仓库的 Redisson 3.13.4 如此）。直接在 finally 里调，后果是两条：
     * <ul>
     *   <li><b>锁放不掉</b>——{@code isHeldByCurrentThread()} 先抛，{@code unlock()} 根本没发出去，锁要等租期到了才过期。
     *       结构刷新的租期是 {@code sweepTimeoutMinutes}（默认 30 分钟），新部署起来的进程前几次检查都会以为
     *       「另一个副本正在执行」而静默跳过；</li>
     *   <li><b>异常从 finally 里抛出去</b>，盖掉「被中断：不算失败、记 INFO」的处理，在关闭日志里留一段 ERROR 栈——
     *       本仓库 push main 即部署，每次部署撞上一轮刷新就是一段假警报。</li>
     * </ul>
     * 清标记不会让关闭拖延：要不要继续干活由调用方在循环里看标记决定、此时早已停下，这里只是让放锁这一次
     * Redis 往返能完成，放完立即把标记还回去。放锁因为别的原因失败（Redis 抖动）只记 WARN——锁有租期，最坏是晚一个租期。
     *
     * <p>这里只管「线程醒着、走得到 finally」的情形。线程卡在 socket 读上、根本走不到 finally 的情形归
     * {@link #releaseOnShutdown()}。
     */
    private static void releaseQuietly(RLock lock, String what) {
        boolean interrupted = Thread.interrupted();
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("{}：释放跨副本锁失败，锁会在租期到后自动过期", what, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 本实例拿着的一把锁。<b>按身份比较</b>（刻意不覆写 equals）：谁从 {@link #heldLocks} 里摘掉这一个对象，谁放锁。 */
    private static final class HeldLock {
        private final RLock lock;
        /** 拿锁那条线程的 id。Redisson 的锁归属按它记，关闭回调要靠它才放得掉别的线程拿的锁。 */
        private final long threadId;
        private final String what;

        private HeldLock(RLock lock, long threadId, String what) {
            this.lock = lock;
            this.threadId = threadId;
            this.what = what;
        }
    }

    /** 一轮的计数，给日志和单测用。不是对外 DTO。 */
    static final class RefreshTally {
        /** 快照过期、连接行还在的条数 */
        int due;
        /** 占本轮名额的尝试：成功 + 拒绝落库 + 失败 + 被中断。<b>繁忙不在其中</b>，见 {@link #busy}。 */
        int attempted;
        int refreshed;
        /** refresh() 拒绝落库可疑快照的（guardNote 非空）：成功的无变化，一个间隔后再看 */
        int guarded;
        /** 繁忙结局（首次撞上的与有前科重试的都算）。不占名额；数到繁忙预算后不再重试有前科的连接。 */
        int busy;
        int failed;
        /** 刷之前就判掉的（不适用、推导进行中等） */
        int skipped;
        int cooling;
        /** 有繁忙前科、冷却已过，但本轮繁忙预算已用完而没去重试的 */
        int deferred;
        /** 进程关闭时被打断的（不算失败、不记冷却） */
        int aborted;
    }
}

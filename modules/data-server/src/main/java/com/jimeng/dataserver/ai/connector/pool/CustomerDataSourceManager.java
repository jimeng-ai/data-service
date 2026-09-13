package com.jimeng.dataserver.ai.connector.pool;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 客户库连接池的持有者。<b>每个连接器实例一个池，池由本类手工 new、手工持有、手工关闭。</b>
 *
 * <h3>★ 为什么绝不能把 DataSource 注册成 Spring bean —— 这是本类存在的全部理由</h3>
 * Spring Boot 的 {@code DataSourceAutoConfiguration$PooledDataSourceConfiguration} 上挂的是
 * {@code @ConditionalOnMissingBean({DataSource.class, XADataSource.class})}（javap 实测
 * spring-boot-autoconfigure-3.0.2）。只要容器里出现<b>第一个</b>自建的 DataSource bean，
 * 这段自动配置就整体不生效：<b>平台自己的主库 DataSource 干脆不会被创建，
 * MyBatis 的 SqlSessionFactory 会静默绑到客户的库上</b>——平台的表全查到客户库里去。
 *
 * <p>最要命的地方在于<b>它不会启动失败，它会启动成功</b>：没有异常、没有警告，只有一堆
 * 「表不存在」或者更糟——查到了同名表，返回了客户的数据。所以客户库的连接池一律
 * {@code new HikariDataSource(...)}，存在本类的一个 {@code Map} 里，
 * <b>绝不加 {@code @Bean}、绝不放进容器、绝不被任何 {@code @Qualifier} 注入</b>。
 * 本类自身是普通 {@code @Component}（它不是 DataSource，不触发上面那条条件）。
 *
 * <h3>分工：JDBC URL 由调用方拼，本类不碰</h3>
 * URL 上的安全参数（禁多语句、禁 local infile、禁自动反序列化、socket/查询超时、时区、
 * 字符集……）属于「某一种数据库的方言」，由对应的连接器实现（{@code MySqlConnector}）
 * 拼好后原样传进来。本类<b>不解析、不改写、不补默认参数</b>——它只管池的生命周期与安全项，
 * 对 URL 内容一无所知，所以将来接 PostgreSQL 时这个类一行都不用改。
 *
 * <h3>不做的事</h3>
 * 不做健康探测（那是框架探测三步走的职责）、不执行任何 SQL（包括 {@code connectionInitSql}，
 * 见 {@link #buildPool}）、不感知租户（池按 {@code connection.id} 分，而 id 本身就是租户内唯一的）。
 */
@Slf4j
@Component
public class CustomerDataSourceManager {

    /** key = {@code connection.id}。value 里存着当初那把 poolKey，用来判断「参数或凭据是不是变了」。 */
    private final Map<Long, PoolEntry> pools = new ConcurrentHashMap<>();

    /**
     * 关池专用线程。理由是 {@code HikariDataSource.close()} 会等在途连接归还，最坏能阻塞十秒级，
     * 而两个调用点都不该被它拖住：{@link #invalidate} 跑在管理面的 HTTP 线程上，
     * {@link #evictIdle} 跑在 Spring <b>单线程</b>的 TaskScheduler 上——后者一旦被堵住，
     * 同一个调度器里的 {@code OrphanRunReconciler} 会跟着延迟。
     */
    private final ExecutorService closer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connector-pool-closer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 按实例取池，不存在就建。{@code inst.poolKey()} 变了（参数或凭据被改过）就作废旧池重建。
     *
     * <p>返回的 DataSource <b>用完即弃，不要长期持有</b>：空闲回收只看最后一次 acquire 的时间，
     * 拿着一个引用放几分钟再用，可能正好撞上池被回收（{@link PoolSpec#idleEvictMinutes()}
     * 因此有 1 分钟下限，远大于单次查询耗时）。
     *
     * @throws ConnectorException 建池失败，已按 {@link ConnectorErrorCode} 归类并脱敏
     */
    public DataSource acquire(ConnectorInstance inst, String jdbcUrl, String username, String password, PoolSpec spec) {
        Objects.requireNonNull(inst, "inst");
        // id 为空是编码错误（实例没落库就拿来用），不是连接配置问题，所以不包装成 ConnectorException——
        // 包装了反而会把一个 bug 当成「客户填错参数」提示给用户和模型。
        Objects.requireNonNull(inst.id(), "connectorId");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "缺少数据库连接地址");
        }
        PoolSpec effective = spec == null ? PoolSpec.defaults() : spec;
        String key = inst.poolKey();

        // 用 compute 而不是 synchronized 方法：ConcurrentHashMap 只锁这一个 key 所在的 bin，
        // 别的连接器实例照常并发建池。代价是同 bin 的另一个 id 会被这次建连（最多 connectionTimeoutMs）挡住，
        // 接受——连接器实例数量是个位数到几十，且只有首次建池才走到这里。
        PoolEntry entry = pools.compute(inst.id(), (id, current) -> {
            if (current != null && current.poolKey.equals(key) && !current.dataSource.isClosed()) {
                current.lastUsedAt = System.currentTimeMillis();
                return current;
            }
            // 先建新的再关旧的：建池抛异常时 compute 不会改动映射，旧池还能继续用；
            // 反过来写的话，失败后 map 里会留着一个已关闭的池，下一次 acquire 拿到就是 "pool has been closed"。
            PoolEntry fresh = buildPool(inst, jdbcUrl, username, password, effective);
            if (current != null) {
                closeAsync(current, "参数或凭据已变更，旧池作废");
            }
            return fresh;
        });
        return entry.dataSource;
    }

    /** 连接被删 / 改 / 停用时立刻作废。找不到就是空操作。 */
    public void invalidate(Long connectorId) {
        if (connectorId == null) {
            return;
        }
        PoolEntry removed = pools.remove(connectorId);
        if (removed != null) {
            closeAsync(removed, "连接已被删除、修改或停用");
        }
    }

    /**
     * 空闲回收：整个池超过 {@code idleEvictMinutes} 没被 acquire 过就关掉。
     *
     * <p>为什么池要整体关掉、而不是只靠 Hikari 的 idleTimeout 回收物理连接：物理连接归零之后，
     * 池对象本身（House-keeping 定时线程 + 内部队列）还在，客户那边也还留着我们的痕迹。
     * 一个几个月没人用的连接不该常驻。
     *
     * <p><b>定时线程里 {@code TenantContext} / MDC / {@code RequestContextHolder} 全是空的</b>
     * ——它们是请求作用域的 ThreadLocal，不会传播到调度线程。本方法只读内存里的 Map、不碰任何数据库，
     * 所以不受影响；<b>后来者若要在这里加落库（比如把回收记进审计），必须自己
     * {@code TenantContext.runAsSystem(...)} 包起来</b>，否则租户过滤会回落到 {@code __no_tenant__} 哨兵，
     * 语句照常执行但一行都匹配不到，且不报错。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 300_000L)
    public void evictIdle() {
        long now = System.currentTimeMillis();
        // 用 computeIfPresent 而不是「先判断再 remove」：判断和摘除必须在同一把 bin 锁里，
        // 否则会和一个正在 acquire 的线程擦肩——它刚拿到引用，我们就把池关了。
        for (Long id : pools.keySet()) {
            pools.computeIfPresent(id, (k, e) -> {
                long idleMs = now - e.lastUsedAt;
                if (idleMs < e.idleEvictMs) {
                    return e;
                }
                closeAsync(e, "空闲 " + (idleMs / 60_000L) + " 分钟未使用");
                return null;
            });
        }
    }

    /** 应用关闭时同步关掉所有池：客户库那边应该看到我们干净地断开，而不是一堆等超时的半开连接。 */
    @PreDestroy
    public void shutdown() {
        closer.shutdown();
        pools.values().forEach(e -> closeNow(e, "应用关闭"));
        pools.clear();
        try {
            closer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 建池

    private PoolEntry buildPool(ConnectorInstance inst, String jdbcUrl, String username, String password, PoolSpec spec) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);

        // ---- 安全项：以下四条写死，不接受连接器实现覆盖 ----

        // 1) readOnly：Hikari 会对每条新连接调 Connection.setReadOnly(true)，MySQL 驱动据此把会话置为
        //    READ ONLY。这是只读的第三道防线（前两道是「客户给的账号本身只读」和「SQL 静态校验」）。
        //    三道都可能单独失效——账号权限是客户配的、静态校验有绕过面——所以一道都不省。
        cfg.setReadOnly(true);

        // 2) autoCommit：Hikari 默认就是 true，这里显式写死是为了钉住它。改成 false 之后每条只读查询
        //    都会隐式开启事务，而我们这条路径上没有任何人负责 commit/rollback——在客户的 MySQL 上
        //    就是一串长事务，顶住 undo purge，最终是客户的 DBA 来找我们。
        cfg.setAutoCommit(true);

        // 3) poolName 带上 kind + connectorId：Hikari 的每一条日志和它自己的线程名都用 poolName。
        //    默认的 "HikariPool-1/2/3..." 在有十几个客户连接时完全无法定位是哪一条。
        //    刻意只放 kind 和 id，不放主机名 / 库名 / 租户——poolName 会随日志进 Kibana。
        cfg.setPoolName("connector-" + inst.kind() + "-" + inst.id());

        // 4) connectionInitSql 故意不设。它会对每条新建的物理连接执行一次，等于我们在客户的生产库上
        //    跑一条自己编的 SQL；一旦写错（方言不兼容、权限不足）表现为「建池成功但每次取连接都失败」，
        //    极难排查。会话级设置该由连接器实现在自己的语句里做，别塞在池的构造里。

        cfg.setMaximumPoolSize(spec.maxPoolSize());
        // minimumIdle=0：客户库的连接是客户的资源，没活就一条都不占。
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(spec.connectionTimeoutMs());
        cfg.setValidationTimeout(Math.min(spec.connectionTimeoutMs(), 3_000L));
        cfg.setMaxLifetime(spec.maxLifetimeMs());
        // 单条物理连接空闲 1 分钟即回收（受 maxLifetime 约束，Hikari 要求 idleTimeout 明显小于它）；
        // 整个池的回收是另一回事，见 evictIdle()。
        cfg.setIdleTimeout(Math.min(60_000L, spec.maxLifetimeMs() / 2));
        // 没有 actuator、没有 MeterRegistry，连接泄漏唯一的信号就是这条警告日志（它打的是借出方的堆栈，
        // 不含 SQL 和凭据，可以安全落日志）。只读查询占着连接超过 1 分钟，不是跑飞就是忘了 close。
        cfg.setLeakDetectionThreshold(60_000L);
        // >0 表示构造 HikariDataSource 时就先连一次、连不上直接抛。要的就是这个：
        // 把「地址填错 / 密码错」在保存连接的当场报出来，而不是等 Agent 查询时才炸。
        cfg.setInitializationFailTimeout(spec.connectionTimeoutMs());

        try {
            HikariDataSource ds = new HikariDataSource(cfg);
            log.info("客户库连接池已建立 connectorId={} kind={} tenantId={} maxPoolSize={}",
                    inst.id(), inst.kind(), inst.tenantId(), spec.maxPoolSize());
            return new PoolEntry(inst.poolKey(), ds, spec.idleEvictMs());
        } catch (Exception e) {
            throw classify(e, inst);
        }
    }

    // ---------------------------------------------------------------- 错误归类

    /**
     * 把建池失败归到统一错误语义，<b>并且只留脱敏文案</b>。
     *
     * <p>安全约束在这里体现得最直接：JDBC 的异常消息里常带完整 URL、主机端口、用户名，
     * 有时还带连接参数；而工具结果会被完整 JSON 化回灌模型并落进 {@code ai_model_call_content}。
     * 所以 {@code safeDetail} 一律是本方法里写死的常量句子——<b>不拼 jdbcUrl、不拼 username、
     * 不拼 e.getMessage()</b>。原始异常只走 {@code log.warn} 的 cause。
     */
    private ConnectorException classify(Exception e, ConnectorInstance inst) {
        log.warn("客户库建池失败 connectorId={} kind={} tenantId={}", inst.id(), inst.kind(), inst.tenantId(), e);

        // 先按 MySQL 的具体错误码判：它们比 SQLState 精确得多，而「库名填错」是最常见的一种配错，
        // 兜底文案（「请检查主机、端口、库名与驱动参数」）等于让人四个方向一起试。
        Integer errorCode = findErrorCode(e);
        if (errorCode != null) {
            switch (errorCode) {
                // 1049 / 1044 给同一句话，是因为 MySQL 侧本来就不可区分：
                // 对一个没有该库权限的账号，MySQL 返回的是 1044（access denied）而不是 1049（unknown database）——
                // 这是它刻意的信息隐藏，不想泄露「这个库存不存在」。我们照实说两种可能，
                // 不要假定库一定存在（那会让一个手抖打错库名的人一直去查授权）。
                case 1049:  // ER_BAD_DB_ERROR
                case 1044:  // ER_DBACCESS_DENIED_ERROR
                    return ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                            "这个账号访问不了该库：库名可能填错了，或者这个账号还没有被授予该库的只读权限"
                                    + "（数据库侧 GRANT SELECT ON 库名.* TO 账号）");
                case 1045:  // ER_ACCESS_DENIED_ERROR
                    return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED,
                            "数据库拒绝了这把凭据：用户名或密码不正确");
                default:
                    break;
            }
        }

        String sqlState = findSqlState(e);
        if (sqlState != null) {
            // 28xxx = invalid authorization specification（MySQL 密码错 / 该账号不允许从本机登录）
            if (sqlState.startsWith("28")) {
                return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED,
                        "数据库拒绝了这把凭据：用户名或密码不正确，或该账号不允许从平台所在网络登录");
            }
            // 08xxx = connection exception（含 MySQL 的 08S01 Communications link failure）
            if (sqlState.startsWith("08")) {
                return ConnectorException.of(ConnectorErrorCode.UNREACHABLE,
                        "无法建立到数据库的网络连接：主机不可达、端口未开放或被防火墙拦截");
            }
        }
        if (hasCause(e, UnknownHostException.class)
                || hasCause(e, ConnectException.class)
                || hasCause(e, NoRouteToHostException.class)
                || hasCause(e, SocketTimeoutException.class)
                || messageHints(e)) {
            return ConnectorException.of(ConnectorErrorCode.UNREACHABLE,
                    "无法建立到数据库的网络连接：域名解析失败、主机不可达或连接超时");
        }
        // 兜底走 CONFIG_ERROR 而不是 UPSTREAM_ERROR：建池阶段还没执行任何业务语句，
        // 此时失败几乎都是参数问题（库名不存在、端口指向了别的服务、驱动参数非法）。
        return ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                "建立数据库连接池失败，请检查主机、端口、库名与驱动参数是否正确");
    }

    /** 同 {@link #findSqlState}：错误码也可能被 Hikari 包了好几层。0 不算有效码（驱动自造的异常常是 0）。 */
    private static Integer findErrorCode(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException sqlEx && sqlEx.getErrorCode() != 0) {
                return sqlEx.getErrorCode();
            }
        }
        return null;
    }

    /** 驱动不一定把 SQLState 挂在最外层（Hikari 会用 PoolInitializationException 包一层），要顺着 cause 找。 */
    private static String findSqlState(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if (state != null && state.length() >= 2) {
                    return state;
                }
            }
        }
        return null;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 有些驱动把网络故障包成了普通异常，只有文案能认（典型：JDK 的 "Connect timed out"、
     * Connector/J 的 "Communications link failure"）。<b>只用来分类，文案本身绝不外传。</b>
     */
    private static boolean messageHints(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            String msg = c.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("connect timed out")
                    || lower.contains("communications link failure")
                    || lower.contains("connection refused")
                    || lower.contains("unknownhost")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 关池

    private void closeAsync(PoolEntry entry, String reason) {
        try {
            closer.execute(() -> closeNow(entry, reason));
        } catch (RuntimeException e) {
            // closer 已经 shutdown（应用正在关闭）：退化成同步关，宁可慢一点也不要泄漏一个池。
            closeNow(entry, reason);
        }
    }

    private void closeNow(PoolEntry entry, String reason) {
        try {
            entry.dataSource.close();
            log.info("客户库连接池已关闭 pool={} 原因={}", entry.dataSource.getPoolName(), reason);
        } catch (Exception e) {
            // 关池失败不影响任何调用方，只记日志：条目早已从 map 摘除，不会再被取到。
            log.warn("客户库连接池关闭失败 原因={}", reason, e);
        }
    }

    /** 一个池条目。{@code lastUsedAt} 用 volatile 而不是 AtomicLong：只有「最后写赢」的语义，不需要 CAS。 */
    private static final class PoolEntry {
        private final String poolKey;
        private final HikariDataSource dataSource;
        private final long idleEvictMs;
        private volatile long lastUsedAt;

        private PoolEntry(String poolKey, HikariDataSource dataSource, long idleEvictMs) {
            this.poolKey = poolKey;
            this.dataSource = dataSource;
            this.idleEvictMs = idleEvictMs;
            this.lastUsedAt = System.currentTimeMillis();
        }
    }
}

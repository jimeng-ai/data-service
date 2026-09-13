package com.jimeng.dataserver.ai.connector.pool;

/**
 * 客户库连接池的参数。<b>每个值都刻意小，因为池建在客户的生产库上。</b>
 *
 * <h3>为什么不照抄主库配置</h3>
 * 主库的 Hikari 配置里 {@code max-lifetime=30000}（30 秒）是配错的——正常量级是 30 <b>分钟</b>，
 * 30 秒意味着每条物理连接活不过半分钟就被回收重建。抄过来等于在客户库上持续建连断连，
 * 客户的 DBA 会先看到我们。所以这里的默认值全部独立给定，不从任何既有配置派生。
 *
 * <h3>为什么在这里夹逼（clamp）</h3>
 * 「资源保护由框架强制、实现方无法绕过」是这套接入层的既定约束。连接器实现类可以自己
 * 给参数，但给不出一个 200 大小的池或一个 0 毫秒的超时：越界值在紧凑构造器里被拉回区间，
 * 非正数按默认值处理。**这是刻意的静默修正**——把客户库打挂的代价，远大于「我填的 500
 * 怎么变成 8 了」的困惑。
 *
 * @param maxPoolSize         池上限。我们是「借客户的库跑只读查询」，不是自己的主库，
 *                            并发靠排队而不是靠加连接；2~3 足够，上限 8
 * @param connectionTimeoutMs 拿不到连接就放弃的时间。它同时被用作建池时的首连超时，
 *                            所以直接决定「配错地址」多久能报出来
 * @param maxLifetimeMinutes  物理连接最长寿命，应小于客户库的 {@code wait_timeout}，
 *                            否则会拿到被对端单方面掐掉的死连接
 * @param idleEvictMinutes    整个池多久没被用过就整体关掉（由 {@code CustomerDataSourceManager}
 *                            的定时任务执行），不是单条连接的 idleTimeout
 */
public record PoolSpec(int maxPoolSize, int connectionTimeoutMs, int maxLifetimeMinutes, int idleEvictMinutes) {

    private static final int DEFAULT_MAX_POOL_SIZE = 3;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_MAX_LIFETIME_MINUTES = 30;
    private static final int DEFAULT_IDLE_EVICT_MINUTES = 10;

    public PoolSpec {
        maxPoolSize = clamp(maxPoolSize, 1, 8, DEFAULT_MAX_POOL_SIZE);
        // 下限 1000ms：Hikari 自己的下限是 250ms，但公网到客户库的 TCP + TLS + 握手不可能在 250ms 内完成，
        // 配成那么小只会把「网络慢」误报成「连不上」。
        connectionTimeoutMs = clamp(connectionTimeoutMs, 1_000, 30_000, DEFAULT_CONNECTION_TIMEOUT_MS);
        maxLifetimeMinutes = clamp(maxLifetimeMinutes, 1, 120, DEFAULT_MAX_LIFETIME_MINUTES);
        idleEvictMinutes = clamp(idleEvictMinutes, 1, 120, DEFAULT_IDLE_EVICT_MINUTES);
    }

    /** 连接器实现没有特殊诉求时用这个；MySQL 首批就用它。 */
    public static PoolSpec defaults() {
        return new PoolSpec(DEFAULT_MAX_POOL_SIZE, DEFAULT_CONNECTION_TIMEOUT_MS,
                DEFAULT_MAX_LIFETIME_MINUTES, DEFAULT_IDLE_EVICT_MINUTES);
    }

    public long maxLifetimeMs() {
        return maxLifetimeMinutes * 60_000L;
    }

    public long idleEvictMs() {
        return idleEvictMinutes * 60_000L;
    }

    /** 非正数视为「没填」走默认值；填了但越界的拉回区间。 */
    private static int clamp(int value, int lo, int hi, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(hi, Math.max(lo, value));
    }
}

package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 登录失败次数限制，企业登录和运营登录共用。
 *
 * <p>两本账，都以「账号」为主体：
 * <ul>
 *   <li>账号 + 来源 IP：失败 {@value #MAX_FAILURES_PER_IP} 次，锁 {@link #IP_WINDOW}，挡单一来源猜密码；</li>
 *   <li>账号总数：失败 {@value #MAX_FAILURES_PER_ACCOUNT} 次，锁 {@link #ACCOUNT_WINDOW}，挡换 IP 的分布式猜测。</li>
 * </ul>
 *
 * <p>几个容易做错、而且做错了不会报错的地方：
 * <ul>
 *   <li><b>账号标识必须是库认定的身份</b>（调用方传 service 的 {@code loginAttemptKey}，库里有就是 {@code id:<id>}）。
 *       username 列是 utf8mb4_unicode_ci，ádmin、ＡＤＭＩＮ、admin 命中同一行；按输入字符串计数的话，换个写法就是一本新账。</li>
 *   <li><b>先占名额，再校验密码</b>（INCR 是原子的，和 {@code ConnectorGateway} 的限流同一写法）。先读计数、失败后再加，
 *       并发打进来的请求会全部读到同一个旧值，一个窗口能试的次数就变成了服务器的并发数。
 *       校验通过、或者不是密码错误（参数错误等）时，把占的名额还回去。</li>
 *   <li>窗口从第一次失败起算；<b>失败次数刚到上限的那一次，把锁重新设成一个完整窗口</b>（规格：失败 5 次，锁 15 分钟）。
 *       计数键意外没有过期时间时，下一次请求（包括被锁的请求）会补上，不会永久锁死。</li>
 *   <li><b>Redis 不可用或太慢时放行</b>，和 {@code ConnectorGateway} 的限流同一个取舍：这里限的是速率，不是权限。
 *       Redis 断线时 Lettuce 会把命令排队到超时（默认 60 秒），所以每次调用最多等 {@link #REDIS_CALL_TIMEOUT}，
 *       出过一次错就在 {@link #REDIS_BACKOFF} 内直接跳过限流，不能让登录跟着 Redis 一起卡住。</li>
 * </ul>
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    public enum Realm { ENTERPRISE, OPERATOR }

    static final int MAX_FAILURES_PER_IP = 5;
    static final Duration IP_WINDOW = Duration.ofMinutes(15);
    static final int MAX_FAILURES_PER_ACCOUNT = 20;
    static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    static final Duration REDIS_CALL_TIMEOUT = Duration.ofMillis(300);
    static final Duration REDIS_BACKOFF = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ThreadPoolExecutor redisCalls;
    private volatile long skipRedisUntilMillis = 0L;

    public LoginAttemptGuard(StringRedisTemplate redis) {
        this.redis = redis;
        AtomicInteger seq = new AtomicInteger();
        this.redisCalls = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000), r -> {
            Thread t = new Thread(r, "login-guard-redis-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        redisCalls.shutdownNow();
    }

    public <T> T guard(Realm realm, String accountId, String clientIp, Supplier<T> login) {
        String prefix = "login:fail:" + realm.name().toLowerCase(Locale.ROOT) + ":";
        String account = hash(accountId);
        String accountKey = prefix + "u:" + account;
        String pairKey = prefix + "ip:" + account + ":" + clientIp;

        long pair = reserve(pairKey, IP_WINDOW);
        long total = reserve(accountKey, ACCOUNT_WINDOW);
        if (pair > MAX_FAILURES_PER_IP || total > MAX_FAILURES_PER_ACCOUNT) {
            String lockedKey = pair > MAX_FAILURES_PER_IP ? pairKey : accountKey;
            ServiceException locked = lockedError(lockedKey);
            // 被拒的这次不算一次尝试：还回名额，免得另一本账被「锁定期间的重试」顶到上限
            release(pairKey, pair);
            release(accountKey, total);
            throw locked;
        }
        try {
            T result = login.get();
            call(() -> redis.delete(pairKey));
            release(accountKey, total);
            return result;
        } catch (ServiceException e) {
            if (ExceptionCode.AUTHENTICATION_FAIL.getResultCode().equals(e.getRespCode())) {
                if (pair == MAX_FAILURES_PER_IP) {
                    call(() -> redis.expire(pairKey, IP_WINDOW));
                }
                if (total == MAX_FAILURES_PER_ACCOUNT) {
                    call(() -> redis.expire(accountKey, ACCOUNT_WINDOW));
                }
            } else {
                release(pairKey, pair);
                release(accountKey, total);
            }
            throw e;
        } catch (RuntimeException e) {
            release(pairKey, pair);
            release(accountKey, total);
            throw e;
        }
    }

    private static String hash(String accountId) {
        String normalized = accountId == null ? "" : accountId.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 占一个名额并返回占后的计数；限流不可用时返回 -1（放行）。第一次计数或 key 没有过期时间时补上窗口。 */
    private long reserve(String key, Duration window) {
        Long count = call(() -> redis.opsForValue().increment(key));
        if (count == null) {
            return -1;
        }
        Long ttl = call(() -> redis.getExpire(key));
        if (count == 1L || ttl == null || ttl < 0) {
            call(() -> redis.expire(key, window));
        }
        return count;
    }

    private void release(String key, long reserved) {
        if (reserved > 0) {
            call(() -> redis.opsForValue().decrement(key));
        }
    }

    private ServiceException lockedError(String key) {
        Long ttl = call(() -> redis.getExpire(key));
        long seconds = ttl != null && ttl > 0 ? ttl : 60;
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "登录失败次数过多，请 " + minutes + " 分钟后再试");
    }

    /** 执行一次 Redis 调用，最多等 {@link #REDIS_CALL_TIMEOUT}；出错或超时返回 null，并在退避期内跳过后续调用。 */
    private <V> V call(Supplier<V> op) {
        if (System.currentTimeMillis() < skipRedisUntilMillis) {
            return null;
        }
        Future<V> future;
        try {
            future = redisCalls.submit(op::get);
        } catch (RejectedExecutionException e) {
            backOff(e);
            return null;
        }
        try {
            return future.get(REDIS_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            backOff(e);
        } catch (ExecutionException e) {
            backOff(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void backOff(Throwable cause) {
        skipRedisUntilMillis = System.currentTimeMillis() + REDIS_BACKOFF.toMillis();
        log.warn("登录限流暂停 {} 秒：Redis 不可用或太慢（这段时间登录不做限流，照常可用）", REDIS_BACKOFF.toSeconds(), cause);
    }
}

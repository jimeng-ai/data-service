package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 登录失败次数限制，企业登录和运营登录共用。
 *
 * <p>两本账，都以「账号」为主体。账号名统一小写、去掉首尾空格后做 SHA-256 再进 key，
 * 既不能靠大小写换号绕过，也不会让任意字符串进 Redis key：
 * <ul>
 *   <li>账号 + 来源 IP：{@value #MAX_FAILURES_PER_IP} 次失败锁 {@link #IP_WINDOW}，挡单一来源猜密码；</li>
 *   <li>账号总数：{@value #MAX_FAILURES_PER_ACCOUNT} 次失败锁 {@link #ACCOUNT_WINDOW}，挡换 IP 的分布式猜测。</li>
 * </ul>
 * 窗口从第一次失败起算，到期自动解锁。只有 {@link ExceptionCode#AUTHENTICATION_FAIL} 算失败（空用户名这类参数错误不算），
 * 锁定本身也不计数。登录成功清掉「账号 + IP」那本账。
 *
 * <p><b>Redis 不可用时放行</b>，和 {@code ConnectorGateway} 的限流同一个取舍：这里限的是速率，不是权限，
 * 不能因为 Redis 抖动就让所有人都登不上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    public enum Realm { ENTERPRISE, OPERATOR }

    static final int MAX_FAILURES_PER_IP = 5;
    static final Duration IP_WINDOW = Duration.ofMinutes(15);
    static final int MAX_FAILURES_PER_ACCOUNT = 20;
    static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public <T> T guard(Realm realm, String username, String clientIp, Supplier<T> login) {
        String prefix = "login:fail:" + realm.name().toLowerCase(Locale.ROOT) + ":";
        String account = accountId(username);
        String accountKey = prefix + "u:" + account;
        String pairKey = prefix + "ip:" + account + ":" + clientIp;

        rejectIfLocked(pairKey, MAX_FAILURES_PER_IP);
        rejectIfLocked(accountKey, MAX_FAILURES_PER_ACCOUNT);
        try {
            T result = login.get();
            quietly(() -> redis.delete(pairKey));
            return result;
        } catch (ServiceException e) {
            if (ExceptionCode.AUTHENTICATION_FAIL.getResultCode().equals(e.getRespCode())) {
                quietly(() -> {
                    bump(pairKey, IP_WINDOW);
                    bump(accountKey, ACCOUNT_WINDOW);
                });
            }
            throw e;
        }
    }

    private static String accountId(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private void rejectIfLocked(String key, int maxFailures) {
        long ttlSeconds;
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || Long.parseLong(value) < maxFailures) {
                return;
            }
            Long ttl = redis.getExpire(key);
            ttlSeconds = ttl == null ? -1 : ttl;
        } catch (RuntimeException e) {
            log.warn("登录限流检查失败，本次放行 key={}", key, e);
            return;
        }
        long seconds = ttlSeconds > 0 ? ttlSeconds : 60;
        long minutes = Math.max(1, (seconds + 59) / 60);
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL,
                "登录失败次数过多，请 " + minutes + " 分钟后再试");
    }

    /** 计一次失败。第一次失败、或 key 意外没有过期时间（INCR 成功而 EXPIRE 失败）时补上窗口，避免永久锁死。 */
    private void bump(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        Long ttl = redis.getExpire(key);
        if ((count != null && count == 1L) || ttl == null || ttl < 0) {
            redis.expire(key, window);
        }
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("登录限流记账失败（不影响本次登录结果）", e);
        }
    }
}

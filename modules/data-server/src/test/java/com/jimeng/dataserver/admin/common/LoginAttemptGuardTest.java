package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptGuardTest {

    private static final LoginAttemptGuard.Realm ENT = LoginAttemptGuard.Realm.ENTERPRISE;

    /** 用两个 Map 模拟 Redis：计数，以及剩余秒数（没有条目 = 没设过期时间）。 */
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Long> ttls = new HashMap<>();
    private final AtomicInteger loginCalls = new AtomicInteger();
    private StringRedisTemplate redis;
    private LoginAttemptGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> {
            Long v = counters.get(inv.<String>getArgument(0));
            return v == null ? null : String.valueOf(v);
        });
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(redis.getExpire(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (!counters.containsKey(key)) {
                return -2L;
            }
            return ttls.getOrDefault(key, -1L);
        });
        when(redis.expire(anyString(), any(Duration.class))).thenAnswer(inv -> {
            ttls.put(inv.getArgument(0), inv.<Duration>getArgument(1).getSeconds());
            return true;
        });
        when(redis.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            ttls.remove(key);
            return counters.remove(key) != null;
        });
        guard = new LoginAttemptGuard(redis);
    }

    private Supplier<String> wrongPassword() {
        return () -> {
            loginCalls.incrementAndGet();
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        };
    }

    private Supplier<String> correctPassword() {
        return () -> {
            loginCalls.incrementAndGet();
            return "token";
        };
    }

    private void failTimes(String user, String ip, int times) {
        for (int i = 0; i < times; i++) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> guard.guard(ENT, user, ip, wrongPassword()));
            assertEquals("用户名或密码错误", e.getRespMsg(), "锁定之前，返回的必须是原来的错误");
        }
    }

    @Test
    void fiveFailuresFromOneIpLockThatAccountOnThatIp() {
        failTimes("alice", "203.0.113.9", 5);
        loginCalls.set(0);
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
        assertEquals(ExceptionCode.AUTHENTICATION_FAIL.getResultCode(), e.getRespCode());
        assertTrue(e.getRespMsg().contains("登录失败次数过多"), e.getRespMsg());
        assertEquals(0, loginCalls.get(), "锁定期间不能再去校验密码");
    }

    @Test
    void lockOnOneIpDoesNotBlockAnotherIp() {
        failTimes("alice", "203.0.113.9", 5);
        assertEquals("token", guard.guard(ENT, "alice", "198.51.100.1", correctPassword()));
    }

    @Test
    void twentyFailuresAcrossIpsLockTheAccount() {
        for (int i = 1; i <= 20; i++) {
            failTimes("alice", "198.51.100." + i, 1);
        }
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "alice", "192.0.2.200", correctPassword()));
        assertTrue(e.getRespMsg().contains("登录失败次数过多"), e.getRespMsg());
    }

    @Test
    void successResetsTheIpCounter() {
        failTimes("alice", "203.0.113.9", 4);
        assertEquals("token", guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
        failTimes("alice", "203.0.113.9", 4);   // 成功后重新计数，再错 4 次也不锁
        assertEquals("token", guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
    }

    @Test
    void parameterErrorsAreNotCounted() {
        Supplier<String> blank = () -> {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        };
        for (int i = 0; i < 10; i++) {
            assertThrows(ServiceException.class, () -> guard.guard(ENT, "alice", "203.0.113.9", blank));
        }
        assertEquals("token", guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
    }

    @Test
    void usernameIgnoresCaseAndSurroundingSpaces() {
        failTimes("Alice ", "203.0.113.9", 5);
        assertThrows(ServiceException.class, () -> guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
    }

    @Test
    void realmsAreCountedSeparately() {
        failTimes("admin", "203.0.113.9", 5);
        assertEquals("token",
                guard.guard(LoginAttemptGuard.Realm.OPERATOR, "admin", "203.0.113.9", correctPassword()));
    }

    @Test
    void lockMessageShowsRemainingMinutes() {
        failTimes("alice", "203.0.113.9", 5);
        ttls.replaceAll((k, v) -> 600L);
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
        assertTrue(e.getRespMsg().contains("10 分钟"), e.getRespMsg());
    }

    @Test
    void lockExpiresWithWindow() {
        failTimes("alice", "203.0.113.9", 5);
        counters.clear();   // 窗口到期，Redis 把 key 删了
        ttls.clear();
        assertEquals("token", guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
    }

    @Test
    void windowIsSetOnFirstFailureAndRepairedWhenMissing() {
        failTimes("alice", "203.0.113.9", 1);
        assertTrue(ttls.containsValue(LoginAttemptGuard.IP_WINDOW.getSeconds()));
        assertTrue(ttls.containsValue(LoginAttemptGuard.ACCOUNT_WINDOW.getSeconds()));
        ttls.clear();   // 模拟 INCR 成功而 EXPIRE 丢了
        failTimes("alice", "203.0.113.9", 1);
        assertEquals(2, ttls.size(), "下一次失败要把丢掉的过期时间补上，否则会永久锁死");
    }

    @Test
    void redisDownFailsOpen() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        assertEquals("token", guard.guard(ENT, "alice", "203.0.113.9", correctPassword()));
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "alice", "203.0.113.9", wrongPassword()));
        assertEquals("用户名或密码错误", e.getRespMsg());
    }
}

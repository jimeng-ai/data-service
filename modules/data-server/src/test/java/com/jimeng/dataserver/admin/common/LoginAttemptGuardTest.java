package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptGuardTest {

    private static final LoginAttemptGuard.Realm ENT = LoginAttemptGuard.Realm.ENTERPRISE;

    /** 用两个 Map 模拟 Redis：计数，以及剩余秒数（没有条目 = 没设过期时间）。并发测试也会用到，所以是线程安全的。 */
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> ttls = new ConcurrentHashMap<>();
    private final AtomicInteger loginCalls = new AtomicInteger();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> {
            Long v = counters.get(inv.<String>getArgument(0));
            return v == null ? null : String.valueOf(v);
        });
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(ops.decrement(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), -1L, Long::sum));
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

    @AfterEach
    void tearDown() {
        guard.shutdown();
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

    private void failTimes(String account, String ip, int times) {
        for (int i = 0; i < times; i++) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> guard.guard(ENT, account, ip, wrongPassword()));
            assertEquals("用户名或密码错误", e.getRespMsg(), "锁定之前，返回的必须是原来的错误");
        }
    }

    private String pairKeyOf(String ip) {
        return counters.keySet().stream().filter(k -> k.contains(":ip:") && k.endsWith(":" + ip)).findFirst().orElseThrow();
    }

    @Test
    void fiveFailuresFromOneIpLockThatAccountOnThatIp() {
        failTimes("id:7", "203.0.113.9", 5);
        loginCalls.set(0);
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        assertEquals(ExceptionCode.AUTHENTICATION_FAIL.getResultCode(), e.getRespCode());
        assertTrue(e.getRespMsg().contains("登录失败次数过多"), e.getRespMsg());
        assertEquals(0, loginCalls.get(), "锁定期间不能再去校验密码");
    }

    @Test
    void lockOnOneIpDoesNotBlockAnotherIp() {
        failTimes("id:7", "203.0.113.9", 5);
        assertEquals("token", guard.guard(ENT, "id:7", "198.51.100.1", correctPassword()));
    }

    @Test
    void twentyFailuresAcrossIpsLockTheAccount() {
        for (int i = 1; i <= 20; i++) {
            failTimes("id:7", "198.51.100." + i, 1);
        }
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "id:7", "192.0.2.200", correctPassword()));
        assertTrue(e.getRespMsg().contains("登录失败次数过多"), e.getRespMsg());
    }

    @Test
    void successResetsTheIpCounter() {
        failTimes("id:7", "203.0.113.9", 4);
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        failTimes("id:7", "203.0.113.9", 4);   // 成功后重新计数，再错 4 次也不锁
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
    }

    @Test
    void parameterErrorsAreNotCounted() {
        Supplier<String> blank = () -> {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        };
        for (int i = 0; i < 10; i++) {
            assertThrows(ServiceException.class, () -> guard.guard(ENT, "id:7", "203.0.113.9", blank));
        }
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
    }

    @Test
    void accountIdIgnoresCaseAndSurroundingSpaces() {
        failTimes("Name:Alice ", "203.0.113.9", 5);
        assertThrows(ServiceException.class, () -> guard.guard(ENT, "name:alice", "203.0.113.9", correctPassword()));
    }

    @Test
    void realmsAreCountedSeparately() {
        failTimes("id:7", "203.0.113.9", 5);
        assertEquals("token",
                guard.guard(LoginAttemptGuard.Realm.OPERATOR, "id:7", "203.0.113.9", correctPassword()));
    }

    @Test
    void lockMessageShowsRemainingMinutes() {
        failTimes("id:7", "203.0.113.9", 5);
        ttls.replaceAll((k, v) -> 600L);
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        assertTrue(e.getRespMsg().contains("10 分钟"), e.getRespMsg());
    }

    @Test
    void lockExpiresWithWindow() {
        failTimes("id:7", "203.0.113.9", 5);
        counters.clear();   // 窗口到期，Redis 把 key 删了
        ttls.clear();
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
    }

    @Test
    void windowIsSetOnFirstFailureAndRepairedWhenMissing() {
        failTimes("id:7", "203.0.113.9", 1);
        assertTrue(ttls.containsValue(LoginAttemptGuard.IP_WINDOW.getSeconds()));
        assertTrue(ttls.containsValue(LoginAttemptGuard.ACCOUNT_WINDOW.getSeconds()));
        ttls.clear();   // 模拟 INCR 成功而 EXPIRE 丢了
        failTimes("id:7", "203.0.113.9", 1);
        assertEquals(2, ttls.size(), "下一次失败要把丢掉的过期时间补上，否则会永久锁死");
    }

    @Test
    void lockLastsAFullWindowFromTheFifthFailure() {
        failTimes("id:7", "203.0.113.9", 4);
        String pairKey = pairKeyOf("203.0.113.9");
        ttls.put(pairKey, 60L);   // 第一次失败已经是 14 分钟前，窗口只剩 1 分钟
        failTimes("id:7", "203.0.113.9", 1);
        assertEquals(LoginAttemptGuard.IP_WINDOW.getSeconds(), ttls.get(pairKey),
                "规格：失败 5 次锁 15 分钟——锁从第 5 次失败算起，而不是从第一次失败算起");
    }

    @Test
    void lockedKeyWithoutTtlGetsOneInsteadOfLockingForever() {
        failTimes("id:7", "203.0.113.9", 5);
        String pairKey = pairKeyOf("203.0.113.9");
        ttls.remove(pairKey);   // 已到上限的 key 丢了过期时间
        assertThrows(ServiceException.class, () -> guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        assertEquals(LoginAttemptGuard.IP_WINDOW.getSeconds(), ttls.get(pairKey), "被锁的请求也要补上过期时间");
    }

    @Test
    void concurrentWrongPasswordsCannotExceedTheLimit() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    guard.guard(ENT, "id:7", "203.0.113.9", wrongPassword());
                } catch (ServiceException ignored) {
                    // 密码错或已锁，两种都在预期内
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(loginCalls.get() <= LoginAttemptGuard.MAX_FAILURES_PER_IP,
                "并发请求真正去校验密码的次数不能超过上限，实际 " + loginCalls.get());
    }

    @Test
    void slowRedisDoesNotHoldLoginHostage() {
        when(ops.increment(anyString())).thenAnswer(inv -> {
            Thread.sleep(2_000);   // Redis 断线时 Lettuce 会把命令排队到超时
            return 1L;
        });
        long started = System.nanoTime();
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(tookMs < 1_000, "Redis 慢时登录不能跟着卡住，实际 " + tookMs + "ms");

        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        verify(ops, times(1)).increment(anyString());   // 出过一次超时后，退避期内不再碰 Redis
    }

    @Test
    void redisDownFailsOpen() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        assertEquals("token", guard.guard(ENT, "id:7", "203.0.113.9", correctPassword()));
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.guard(ENT, "id:7", "203.0.113.9", wrongPassword()));
        assertEquals("用户名或密码错误", e.getRespMsg());
    }
}

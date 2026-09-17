package com.jimeng.dataserver.ai.connector.generation;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 全平台单个语义层生成排空器的 Redis 值租约。
 *
 * <p>它不是 {@code RLock}：认领、续租和释放会发生在不同线程。值就是本轮排空 token，
 * 续租与释放必须先在 Redis 内原子比值，绝不能删掉后来持有者的租约。
 */
@Component
@RequiredArgsConstructor
public class SemanticGenerationLease {

    public static final String KEY = "connector:semantic:agent:runner";
    public static final long TTL_SECONDS = 180L;
    private static final String TTL_MILLIS = "180000";

    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedissonClient redissonClient;

    public boolean acquire(String token) {
        if (blank(token)) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(KEY, StringCodec.INSTANCE);
        return bucket.trySet(token, TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean renew(String token) {
        if (blank(token)) {
            return false;
        }
        Long renewed = script().eval(RScript.Mode.READ_WRITE, RENEW_SCRIPT, RScript.ReturnType.INTEGER,
                keys(), token, TTL_MILLIS);
        return renewed != null && renewed > 0L;
    }

    public boolean release(String token) {
        if (blank(token)) {
            return false;
        }
        Long deleted = script().eval(RScript.Mode.READ_WRITE, RELEASE_SCRIPT, RScript.ReturnType.INTEGER,
                keys(), token);
        return deleted != null && deleted > 0L;
    }

    private RScript script() {
        return redissonClient.getScript(StringCodec.INSTANCE);
    }

    private static List<Object> keys() {
        return Collections.singletonList(KEY);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

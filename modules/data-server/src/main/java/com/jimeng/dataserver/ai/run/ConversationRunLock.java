package com.jimeng.dataserver.ai.run;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 「每会话单活跃」锁：值=runId 的 SET-NX-PX。
 *
 * <p>刻意不用 Redisson {@code RLock}——抢锁在请求线程、释放在生成/泵线程，{@code RLock} 是线程私有的、
 * 异线程 unlock 会抛。这里用 {@link RBucket} 值锁，任意线程都能释放；带租约，进程崩溃自动过期。
 */
@Component
@RequiredArgsConstructor
public class ConversationRunLock {

    private static final String PREFIX = "chat:conv:active:";
    /** 租约 > 单次生成上限（生成线程 5 分钟 latch）；崩溃后自动释放，避免会话被永久锁死。 */
    private static final long LEASE_MINUTES = 6;

    private final RedissonClient redissonClient;

    public boolean tryAcquire(Long conversationId, String runId) {
        return bucket(conversationId).trySet(runId, LEASE_MINUTES, TimeUnit.MINUTES);
    }

    public void release(Long conversationId, String runId) {
        RBucket<String> b = bucket(conversationId);
        if (runId != null && runId.equals(b.get())) {
            b.delete();
        }
    }

    private RBucket<String> bucket(Long conversationId) {
        return redissonClient.getBucket(PREFIX + conversationId, StringCodec.INSTANCE);
    }
}

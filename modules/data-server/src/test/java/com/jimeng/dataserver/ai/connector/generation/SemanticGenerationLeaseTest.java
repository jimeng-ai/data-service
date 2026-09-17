package com.jimeng.dataserver.ai.connector.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticGenerationLeaseTest {

    private RedissonClient redisson;
    private RBucket<String> bucket;
    private RScript script;
    private SemanticGenerationLease lease;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        script = mock(RScript.class);
        when(redisson.<String>getBucket(SemanticGenerationLease.KEY, StringCodec.INSTANCE)).thenReturn(bucket);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        lease = new SemanticGenerationLease(redisson);
    }

    @Test
    @DisplayName("bucket 与 Lua 脚本都强制使用 StringCodec")
    void bucket与脚本使用StringCodec() {
        when(bucket.trySet("owner", 180L, TimeUnit.SECONDS)).thenReturn(true);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any())).thenReturn(1L);

        assertTrue(lease.acquire("owner"));
        assertTrue(lease.release("owner"));

        verify(redisson).getBucket(SemanticGenerationLease.KEY, StringCodec.INSTANCE);
        verify(redisson).getScript(StringCodec.INSTANCE);
        verify(bucket).trySet("owner", 180L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Redis 中的值不符时既不续租也不删除别人的租约")
    void 值不符时不续租也不删除() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any())).thenReturn(0L);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any(), any())).thenReturn(0L);

        assertFalse(lease.renew("stale-owner"));
        assertFalse(lease.release("stale-owner"));

        ArgumentCaptor<String> renewLua = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> releaseLua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), renewLua.capture(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(SemanticGenerationLease.KEY)),
                eq("stale-owner"), eq("180000"));
        verify(script).eval(eq(RScript.Mode.READ_WRITE), releaseLua.capture(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(SemanticGenerationLease.KEY)),
                eq("stale-owner"));
        assertTrue(renewLua.getValue().contains("redis.call('get', KEYS[1]) == ARGV[1]"), renewLua.getValue());
        assertTrue(releaseLua.getValue().contains("redis.call('get', KEYS[1]) == ARGV[1]"), releaseLua.getValue());
        assertTrue(renewLua.getValue().contains("pexpire"), renewLua.getValue());
        assertTrue(releaseLua.getValue().contains("del"), releaseLua.getValue());
    }

    @Test
    @DisplayName("值相符时把租约续为 180 秒")
    void 值相符时续租180秒() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any(), any())).thenReturn(1L);

        assertTrue(lease.renew("owner"));

        verify(script, atLeastOnce()).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(SemanticGenerationLease.KEY)),
                eq("owner"), eq("180000"));
        assertEquals(180L, SemanticGenerationLease.TTL_SECONDS);
    }
}

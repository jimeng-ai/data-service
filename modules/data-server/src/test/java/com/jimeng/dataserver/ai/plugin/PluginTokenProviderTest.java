package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTemplateRenderer;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginTokenProviderTest {

    private MockWebServer server;
    private PluginTokenProvider provider;
    private RBucket<String> bucket;
    private RLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        RedissonClient redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        lock = mock(RLock.class);
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        provider = new PluginTokenProvider(new OkHttpClient(), new PluginTemplateRenderer(), redisson);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private PluginExecutionContext ctx(Map<String, Object> secrets) {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(), secrets,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private TokenFetchSpec genericSpec() {
        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(server.url("/login").toString());
        s.setContentType("application/json");
        s.getHeaders().put("Content-Type", "application/json");
        s.setBodyTemplate("{\"appKey\":\"{{secrets.appKey}}\"}");
        s.setTokenPath("$.data.token");
        s.setExpirePath("$.data.expire");
        return s;
    }

    @Test
    void cacheHit_returnsWithoutFetch() {
        when(bucket.get()).thenReturn("cached-tok");
        String tok = provider.resolveToken(genericSpec(), ctx(Map.of("appKey", "k")), "ck");
        assertEquals("cached-tok", tok);
        assertEquals(0, server.getRequestCount()); // 没打 token 接口
    }

    @Test
    void miss_fetchesExtractsAndCachesWithTtl() throws Exception {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse()
                .setBody("{\"data\":{\"token\":\"fresh-tok\",\"expire\":7200}}")
                .addHeader("Content-Type", "application/json"));

        String tok = provider.resolveToken(genericSpec(), ctx(Map.of("appKey", "KK")), "ck");

        assertEquals("fresh-tok", tok);
        RecordedRequest rr = server.takeRequest();
        assertEquals("POST", rr.getMethod());
        assertTrue(rr.getBody().readUtf8().contains("\"appKey\":\"KK\"")); // 模板渲染了 secrets
        verify(bucket).set(eq("fresh-tok"), eq(7140L), eq(TimeUnit.SECONDS)); // 7200 - 60
    }

    @Test
    void miss_noExpirePath_usesDefaultTtl() {
        when(bucket.get()).thenReturn(null);
        TokenFetchSpec s = genericSpec();
        s.setExpirePath(null);
        s.setDefaultTtlSec(1800);
        server.enqueue(new MockResponse().setBody("{\"data\":{\"token\":\"t\"}}"));
        provider.resolveToken(s, ctx(Map.of("appKey", "k")), "ck");
        verify(bucket).set(eq("t"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void tokenPathMissing_throws() {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse().setBody("{\"data\":{}}"));
        assertThrows(PluginTokenProvider.TokenFetchException.class,
                () -> provider.resolveToken(genericSpec(), ctx(Map.of("appKey", "k")), "ck"));
    }

    @Test
    void tokenEndpoint4xx_throws() {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse().setResponseCode(401).setBody("nope"));
        assertThrows(PluginTokenProvider.TokenFetchException.class,
                () -> provider.resolveToken(genericSpec(), ctx(Map.of("appKey", "k")), "ck"));
    }

    @Test
    void invalidate_deletesBucket() {
        provider.invalidate("ck");
        verify(bucket).delete();
    }

    @Test
    void cacheKey_changesWithAuthConfigOrSecrets() {
        String k1 = provider.cacheKey("t1", 1L, Map.of("u", "a"), Map.of("s", "x"));
        String k2 = provider.cacheKey("t1", 1L, Map.of("u", "a"), Map.of("s", "y"));
        String k3 = provider.cacheKey("t1", 1L, Map.of("u", "b"), Map.of("s", "x"));
        assertNotEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertTrue(k1.startsWith("plugin:auth:token:t1:1:"));
    }
}

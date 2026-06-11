package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.PluginAuthApplier;
import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.service.PluginCredentialService;
import com.jimeng.dataserver.ai.plugin.service.PluginHttpInvoker;
import com.jimeng.dataserver.ai.plugin.service.PluginResponseExtractor;
import com.jimeng.dataserver.ai.plugin.service.PluginTemplateRenderer;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginHttpInvokerTokenRetryTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** OAUTH2 鉴权、业务 URL 指向 MockWebServer。 */
    private PluginToolEntry entry() {
        Plugin plugin = new Plugin();
        plugin.setId(7L);
        plugin.setTenantId("t1");
        plugin.setAuthType("OAUTH2");
        plugin.setAuthConfig("{\"token_url\":\"https://auth/x\"}");
        plugin.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));

        PluginHttpMapping mapping = new PluginHttpMapping();
        mapping.setMethod("GET");
        mapping.setUrlTemplate("/biz");

        PluginTool tool = new PluginTool();
        tool.setName("test_tool");
        return new PluginToolEntry(plugin, tool, mapping);
    }

    private PluginCredentialService credService() {
        PluginCredentialService credService = mock(PluginCredentialService.class);
        when(credService.resolveSecrets(anyLong())).thenReturn(Map.of("client_id", "c", "client_secret", "s"));
        return credService;
    }

    private PluginHttpInvoker invoker(PluginAuthApplier applier, PluginCredentialService credService) {
        return new PluginHttpInvoker(
                new OkHttpClient(), credService,
                new PluginTemplateRenderer(), new PluginResponseExtractor(),
                List.of(applier));
    }

    @Test
    void on401_invalidatesAndRetriesOnce_thenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        AtomicInteger applyCount = new AtomicInteger();
        AtomicInteger invalidateCount = new AtomicInteger();

        TokenCachingAuthApplier applier = new TokenCachingAuthApplier() {
            public String authType() { return "OAUTH2"; }
            public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx, Long pluginId, Map<String, Object> ac) {
                applyCount.incrementAndGet();
                req.addHeader("Authorization", "Bearer TOK" + applyCount.get());
            }
            public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> ac) {
                invalidateCount.incrementAndGet();
            }
        };

        Object result = invoker(applier, credService()).invoke(entry(), Map.of());

        assertEquals(2, applyCount.get(), "应注入两次（首次 + 重试）");
        assertEquals(1, invalidateCount.get(), "401 后作废一次缓存");
        assertEquals(2, server.getRequestCount(), "业务接口被打两次");
        assertTrue(result.toString().contains("ok"));
    }

    @Test
    void second401_noInfiniteLoop_returnsHttp4xx() {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));

        TokenCachingAuthApplier applier = new TokenCachingAuthApplier() {
            public String authType() { return "OAUTH2"; }
            public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx, Long pluginId, Map<String, Object> ac) {
                req.addHeader("Authorization", "Bearer X");
            }
            public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> ac) {}
        };

        Object result = invoker(applier, credService()).invoke(entry(), Map.of());
        assertEquals(2, server.getRequestCount(), "最多重试一次");
        assertTrue(result.toString().contains("HTTP_4XX"));
    }
}

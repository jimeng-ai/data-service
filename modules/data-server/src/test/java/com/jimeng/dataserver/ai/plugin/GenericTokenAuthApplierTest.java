package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.GenericTokenAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenericTokenAuthApplierTest {

    private PluginExecutionContext ctx() {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(),
                new LinkedHashMap<>(Map.of("appKey", "KK")), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    @Test
    void authType_isTokenFetch() {
        assertEquals("TOKEN_FETCH", new GenericTokenAuthApplier(mock(PluginTokenProvider.class)).authType());
    }

    @Test
    void buildsSpecFromConfig_andInjectsDefaultBearer() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), eq("ck"))).thenReturn("TOK");

        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = new LinkedHashMap<>();
        Map<String, Object> tokenReq = new LinkedHashMap<>();
        tokenReq.put("method", "POST");
        tokenReq.put("url", "https://auth.x.com/login");
        tokenReq.put("content_type", "application/json");
        tokenReq.put("headers", Map.of("Content-Type", "application/json"));
        tokenReq.put("body", "{\"appKey\":\"{{secrets.appKey}}\"}");
        authConfig.put("token_request", tokenReq);
        authConfig.put("token_path", "$.data.token");
        authConfig.put("expire_path", "$.data.expire");

        applier.applyWithContext(req, ctx(), 9L, authConfig);

        assertEquals("Bearer TOK", req.getHeaders().get("Authorization"));

        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), eq("ck"));
        TokenFetchSpec s = cap.getValue();
        assertEquals("https://auth.x.com/login", s.getUrl());
        assertEquals("$.data.token", s.getTokenPath());
        assertEquals("$.data.expire", s.getExpirePath());
        assertEquals("{\"appKey\":\"{{secrets.appKey}}\"}", s.getBodyTemplate());
    }

    @Test
    void customInjection_xTokenNoPrefix() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), any())).thenReturn("RAW");
        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = new LinkedHashMap<>();
        authConfig.put("token_request", Map.of("url", "https://a/login"));
        authConfig.put("token_path", "$.token");
        authConfig.put("inject", Map.of("location", "header", "name", "X-Token", "prefix", ""));

        applier.applyWithContext(req, ctx(), 9L, authConfig);
        assertEquals("RAW", req.getHeaders().get("X-Token"));
    }

    @Test
    void missingTokenRequest_throwsIllegalArg() {
        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(mock(PluginTokenProvider.class));
        RenderedRequest req = new RenderedRequest();
        Map<String, Object> authConfig = Map.of("token_path", "$.token"); // 缺 token_request
        assertThrows(IllegalArgumentException.class,
                () -> applier.applyWithContext(req, ctx(), 9L, authConfig));
    }
}

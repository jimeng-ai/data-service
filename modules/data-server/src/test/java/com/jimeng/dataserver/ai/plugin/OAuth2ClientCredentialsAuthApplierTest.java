package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.OAuth2ClientCredentialsAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OAuth2ClientCredentialsAuthApplierTest {

    private PluginExecutionContext ctx() {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(),
                new LinkedHashMap<>(Map.of("client_id", "cid", "client_secret", "csec")),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    @Test
    void authType_isOauth2() {
        assertEquals("OAUTH2", new OAuth2ClientCredentialsAuthApplier(mock(PluginTokenProvider.class)).authType());
    }

    @Test
    void bodyMode_buildsFormSpec_injectsBearer() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), eq("ck"))).thenReturn("AT");
        OAuth2ClientCredentialsAuthApplier applier = new OAuth2ClientCredentialsAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = Map.of(
                "token_url", "https://auth.x.com/oauth/token", "scope", "read");
        applier.applyWithContext(req, ctx(), 5L, authConfig);

        assertEquals("Bearer AT", req.getHeaders().get("Authorization"));

        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), eq("ck"));
        TokenFetchSpec s = cap.getValue();
        assertEquals("https://auth.x.com/oauth/token", s.getUrl());
        assertEquals("$.access_token", s.getTokenPath());
        assertEquals("$.expires_in", s.getExpirePath());
        assertTrue(s.getContentType().contains("x-www-form-urlencoded"));
        assertTrue(s.getBodyTemplate().contains("grant_type=client_credentials"));
        assertTrue(s.getBodyTemplate().contains("client_id={{secrets.client_id}}"));
        assertTrue(s.getBodyTemplate().contains("client_secret={{secrets.client_secret}}"));
        assertTrue(s.getBodyTemplate().contains("scope=read"));
    }

    @Test
    void basicMode_putsBasicHeader_andOmitsSecretInBody() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), any())).thenReturn("AT");
        OAuth2ClientCredentialsAuthApplier applier = new OAuth2ClientCredentialsAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = Map.of(
                "token_url", "https://auth.x.com/oauth/token", "client_auth", "basic");
        applier.applyWithContext(req, ctx(), 5L, authConfig);

        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), any());
        TokenFetchSpec s = cap.getValue();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("cid:csec".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, s.getHeaders().get("Authorization"));
        assertTrue(s.getBodyTemplate().contains("grant_type=client_credentials"));
        assertFalse(s.getBodyTemplate().contains("client_secret"));
    }
}

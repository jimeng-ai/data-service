package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginAuthTestService;
import com.jimeng.dataserver.ai.plugin.service.PluginCredentialService;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import com.jimeng.persistence.entity.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginAuthTestServiceTest {

    private PluginTokenProvider tokenProvider;
    private PluginCredentialService credentialService;
    private PluginCrudService crudService;
    private TokenCachingAuthApplier applier;
    private PluginAuthTestService service;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(PluginTokenProvider.class);
        credentialService = mock(PluginCredentialService.class);
        crudService = mock(PluginCrudService.class);
        applier = mock(TokenCachingAuthApplier.class);
        when(applier.authType()).thenReturn("TOKEN_FETCH");
        service = new PluginAuthTestService(List.of(applier), tokenProvider, credentialService, crudService);
    }

    private Plugin plugin(String authType, String authConfig) {
        Plugin p = new Plugin();
        p.setAuthType(authType);
        p.setAuthConfig(authConfig);
        return p;
    }

    @Test
    void unsupportedAuthType_returnsError() {
        when(crudService.getPlugin(1L)).thenReturn(plugin("BEARER", null));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, null, null);

        assertNotNull(r.getError());
        assertTrue(r.getError().contains("不支持"));
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void draftAuthTypeOverride_usesDraftNotSaved() {
        // 已存 authType 还是 NONE（无处理器），但草稿改成 TOKEN_FETCH → 应按草稿走通
        when(crudService.getPlugin(1L)).thenReturn(plugin("NONE", null));
        when(credentialService.resolveSecrets(1L)).thenReturn(Map.of());
        when(applier.buildSpec(anyMap(), anyMap())).thenReturn(new TokenFetchSpec());
        when(tokenProvider.fetchRaw(any(), any()))
                .thenReturn(new PluginTokenProvider.RawResponse(200, "{\"ok\":true}"));

        PluginAuthTestService.TestFetchResult r =
                service.testFetch(1L, "{\"token_request\":{}}", "TOKEN_FETCH");

        assertNull(r.getError());
        assertEquals(200, r.getHttpStatus());
    }

    @Test
    void credentialMissing_proceedsWithEmptySecrets() {
        // 凭证缺失不再硬失败：用空 secrets 继续，真实打到第三方
        when(crudService.getPlugin(1L)).thenReturn(plugin("TOKEN_FETCH", "{}"));
        when(credentialService.resolveSecrets(1L))
                .thenThrow(new PluginCredentialService.CredentialMissingException("missing"));
        when(applier.buildSpec(anyMap(), anyMap())).thenReturn(new TokenFetchSpec());
        when(tokenProvider.fetchRaw(any(), any()))
                .thenReturn(new PluginTokenProvider.RawResponse(200, "{\"ok\":true}"));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, null, null);

        assertNull(r.getError());
        assertEquals(200, r.getHttpStatus());
        verify(applier).buildSpec(anyMap(), eq(Map.of()));
    }

    @Test
    void invalidAuthConfigJson_returnsError() {
        when(crudService.getPlugin(1L)).thenReturn(plugin("TOKEN_FETCH", "not-json"));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, null, null);

        assertNotNull(r.getError());
        assertTrue(r.getError().contains("auth_config"));
    }

    @Test
    void buildSpecRejects_passesThroughMessage() {
        when(crudService.getPlugin(1L)).thenReturn(plugin("TOKEN_FETCH", "{}"));
        when(credentialService.resolveSecrets(1L)).thenReturn(Map.of());
        when(applier.buildSpec(anyMap(), anyMap()))
                .thenThrow(new IllegalArgumentException("TOKEN_FETCH 缺少 token_request 配置"));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, null, null);

        assertEquals("TOKEN_FETCH 缺少 token_request 配置", r.getError());
    }

    @Test
    void happyPath_returnsStatusAndParsedJson() {
        when(crudService.getPlugin(1L)).thenReturn(plugin("TOKEN_FETCH", "{}"));
        when(credentialService.resolveSecrets(1L)).thenReturn(Map.of("appKey", "k"));
        when(applier.buildSpec(anyMap(), anyMap())).thenReturn(new TokenFetchSpec());
        when(tokenProvider.fetchRaw(any(), any()))
                .thenReturn(new PluginTokenProvider.RawResponse(200, "{\"data\":{\"token\":\"ey.x\",\"expire\":3600}}"));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, "{\"token_request\":{}}", null);

        assertNull(r.getError());
        assertEquals(200, r.getHttpStatus());
        assertNotNull(r.getDurationMs());
        assertInstanceOf(Map.class, r.getParsedJson());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) r.getParsedJson();
        assertInstanceOf(Map.class, parsed.get("data"));
    }

    @Test
    void nonJsonResponse_stillReturnsRawBody() {
        when(crudService.getPlugin(1L)).thenReturn(plugin("TOKEN_FETCH", "{}"));
        when(credentialService.resolveSecrets(1L)).thenReturn(Map.of());
        when(applier.buildSpec(anyMap(), anyMap())).thenReturn(new TokenFetchSpec());
        when(tokenProvider.fetchRaw(any(), any()))
                .thenReturn(new PluginTokenProvider.RawResponse(500, "Internal Server Error"));

        PluginAuthTestService.TestFetchResult r = service.testFetch(1L, null, null);

        assertNull(r.getError());
        assertEquals(500, r.getHttpStatus());
        assertNull(r.getParsedJson());
        assertEquals("Internal Server Error", r.getRawBody());
    }
}

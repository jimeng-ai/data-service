package com.jimeng.dataserver.ai.model;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import com.jimeng.persistence.entity.AiModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ModelResolver：命中→改写 upstream + 选对连接；协议不一致→报错；未命中→回落全局 active 不改写。 */
class ModelResolverTest {

    private AiModel model(String value, String protocol, String provider, String upstream) {
        AiModel m = new AiModel();
        m.setValue(value);
        m.setProtocol(protocol);
        m.setProvider(provider);
        m.setUpstreamModel(upstream);
        m.setEnabled(true);
        return m;
    }

    private ChatClient clientWithProtocol(String protocol) {
        ChatClient c = mock(ChatClient.class);
        when(c.capabilities()).thenReturn(new ChatCapabilities(protocol, "anthropic".equals(protocol), "302ai", "x"));
        return c;
    }

    @Test
    void hit_rewritesToUpstreamAndPicksProviderClient() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        when(registry.resolve("claude-opus-4-6")).thenReturn(
                model("claude-opus-4-6", "anthropic", "openrouter", "anthropic/claude-opus-4.6"));
        ChatClient orClient = clientWithProtocol("anthropic");
        when(providers.chat("openrouter")).thenReturn(orClient);

        ModelResolver resolver = new ModelResolver(registry, providers);
        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-opus-4-6");

        ChatClient picked = resolver.resolve(body, "anthropic");

        assertSame(orClient, picked);
        assertEquals("anthropic/claude-opus-4.6", body.get("model"));   // 已改写为上游名
    }

    @Test
    void protocolMismatch_throws() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        when(registry.resolve("gpt-4o")).thenReturn(model("gpt-4o", "openai", "302ai", "gpt-4o"));

        ModelResolver resolver = new ModelResolver(registry, providers);
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");

        // 入口是 anthropic，但模型协议是 openai → 报错
        assertThrows(ServiceException.class, () -> resolver.resolve(body, "anthropic"));
    }

    @Test
    void disabled_throws() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        AiModel disabled = model("claude-opus-4-7", "anthropic", "302ai", "claude-opus-4-7");
        disabled.setEnabled(false);
        when(registry.resolve("claude-opus-4-7")).thenReturn(disabled);

        ModelResolver resolver = new ModelResolver(registry, providers);
        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-opus-4-7");

        // 已下线：运行时直接报错（彻底停用语义）
        assertThrows(ServiceException.class, () -> resolver.resolve(body, "anthropic"));
    }

    @Test
    void miss_fallsBackToActiveAndKeepsModel() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        when(registry.resolve("unknown-model")).thenReturn(null);
        ChatClient active = clientWithProtocol("anthropic");
        when(providers.chat()).thenReturn(active);
        when(providers.activeProvider()).thenReturn("302ai");

        ModelResolver resolver = new ModelResolver(registry, providers);
        Map<String, Object> body = new HashMap<>();
        body.put("model", "unknown-model");

        ChatClient picked = resolver.resolve(body, "anthropic");

        assertSame(active, picked);
        assertEquals("unknown-model", body.get("model"));   // 未命中：不改写
    }
}

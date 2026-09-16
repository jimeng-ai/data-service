package com.jimeng.dataserver.ai.connector.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code connector.semantic.agent.*}（设计文档 6.12）：默认值与宽松绑定。
 *
 * <p>设计文档要求的验证是「不配 Nacos 启动 data-server，无报错，默认值生效」——那要真起进程。这里先在进程外钉住能钉的两件：
 * 一行不配时每一项都是 6.12 表里的默认值（其中总开关必须是关：push main 即部署，合入那一刻行为不能变）；
 * Nacos 里写的 kebab 键能绑到嵌套字段上（写错层级不会报错，只会静默取默认值）。
 * 绑定用的是 Spring Boot 自己的 {@link Binder}，与 {@code @ConfigurationProperties} 走同一套规则。
 */
class ConnectorPropertiesSemanticAgentTest {

    private static ConnectorProperties bind(Map<String, String> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bind("connector", Bindable.ofInstance(new ConnectorProperties())).orElseGet(ConnectorProperties::new);
    }

    @Test
    @DisplayName("★ 一行不配：每一项都是 6.12 表的默认值，总开关关、回调地址与 LLM 地址和 key 为空、模型 deepseek-flash")
    void 不配置时默认值与设计一致() {
        for (ConnectorProperties props : new ConnectorProperties[]{new ConnectorProperties(), bind(Map.of())}) {
            ConnectorProperties.SemanticAgent a = props.getSemantic().getAgent();
            assertFalse(a.isEnabled(), "agent 生成默认必须关闭");
            assertEquals("", a.getCallbackBaseUrl(), "callback-base-url 故意不给默认值（防跨平面回调）");
            assertEquals("", a.getLlm().getBaseUrl());
            assertEquals("", a.getLlm().getAuthToken());
            assertEquals("deepseek-flash", a.getLlm().getModel());
            assertEquals("api-key", a.getLlm().getAuthScheme());
            assertEquals(20, a.getSliceMaxTables());
            assertEquals(40000, a.getSliceMaxChars());
            assertEquals(900, a.getSliceWallClockSec());
            assertEquals(3, a.getSliceMaxTurnsPerTable());
            assertEquals(50.0, a.getSliceMaxBudgetUsd());
            assertEquals(3, a.getSliceMaxRetries());
            assertEquals(6, a.getBusyMaxRetries());
            assertEquals(15, a.getRetryBackoffSec());
            assertEquals(3, a.getTableMaxSubmits());
            assertEquals(3, a.getTableMaxDispatches());
            assertEquals(300000L, a.getMaxTokensPerTable());
            assertEquals(0.0, a.getMaxGaveUpRatio(), "max-gave-up-ratio 默认 0：有放弃表就不替换（用户已确认）");
            assertEquals(2000, a.getHealthTimeoutMs());
            // 新增的嵌套段不影响单次推导原有的默认值
            assertTrue(props.getSemantic().isEnabled());
            assertEquals(60000, props.getSemantic().getMaxDigestChars());
            assertEquals(900, props.getSemantic().getModelTimeoutSeconds());
        }
    }

    @Test
    @DisplayName("★ Nacos 的 kebab 键绑到嵌套字段：connector.semantic.agent.llm.auth-token 等；没写的项保持默认")
    void kebab键绑定到嵌套字段() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("connector.semantic.agent.enabled", "true");
        src.put("connector.semantic.agent.callback-base-url", "http://localhost:10011/data");
        src.put("connector.semantic.agent.llm.base-url", "https://api.deepseek.com/anthropic");
        src.put("connector.semantic.agent.llm.auth-token", "sk-SENTINEL");
        src.put("connector.semantic.agent.llm.model", "deepseek-flash");
        src.put("connector.semantic.agent.llm.auth-scheme", "bearer");
        src.put("connector.semantic.agent.slice-wall-clock-sec", "600");
        src.put("connector.semantic.agent.max-tokens-per-table", "123456");
        src.put("connector.semantic.agent.health-timeout-ms", "1500");
        src.put("connector.semantic.max-digest-chars", "50000");

        ConnectorProperties props = bind(src);
        ConnectorProperties.SemanticAgent a = props.getSemantic().getAgent();

        assertTrue(a.isEnabled());
        assertEquals("http://localhost:10011/data", a.getCallbackBaseUrl());
        assertEquals("https://api.deepseek.com/anthropic", a.getLlm().getBaseUrl());
        assertEquals("sk-SENTINEL", a.getLlm().getAuthToken());
        assertEquals("bearer", a.getLlm().getAuthScheme());
        assertEquals(600, a.getSliceWallClockSec());
        assertEquals(123456L, a.getMaxTokensPerTable());
        assertEquals(1500, a.getHealthTimeoutMs());
        assertEquals(20, a.getSliceMaxTables(), "没写的项应保持默认");
        assertEquals(50000, props.getSemantic().getMaxDigestChars());
    }

    @Test
    @DisplayName("llm.auth-token 不进 toString：整个配置对象被打进日志时带不出 DeepSeek key")
    void authToken不进toString() {
        ConnectorProperties props = bind(Map.of("connector.semantic.agent.llm.auth-token", "sk-SENTINEL-key"));
        assertEquals("sk-SENTINEL-key", props.getSemantic().getAgent().getLlm().getAuthToken());
        assertFalse(props.toString().contains("sk-SENTINEL-key"), props.toString());
        assertTrue(props.toString().contains("deepseek-flash"), "toString 本身应照常输出非敏感字段");
    }
}

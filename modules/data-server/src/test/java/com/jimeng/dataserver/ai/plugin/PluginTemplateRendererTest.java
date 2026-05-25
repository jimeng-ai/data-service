package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.service.PluginTemplateRenderer;
import com.jimeng.persistence.entity.PluginHttpMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTemplateRendererTest {

    private PluginTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new PluginTemplateRenderer();
    }

    private PluginExecutionContext ctx(Map<String, Object> input, Map<String, Object> secrets) {
        return new PluginExecutionContext(
                "tenant-a",
                input == null ? new LinkedHashMap<>() : input,
                secrets == null ? new LinkedHashMap<>() : secrets,
                new LinkedHashMap<>(),
                new LinkedHashMap<>()
        );
    }

    @Test
    void renderString_substitutesAllPlaceholders() {
        PluginExecutionContext ctx = ctx(Map.of("city", "Beijing", "units", "metric"), null);
        String out = renderer.renderString(
                "https://api.example.com/q?city={{input.city}}&u={{input.units}}", ctx);
        assertEquals("https://api.example.com/q?city=Beijing&u=metric", out);
    }

    @Test
    void renderString_missingKey_throwsTemplateRenderException() {
        PluginExecutionContext ctx = ctx(Map.of("city", "Beijing"), null);
        assertThrows(PluginTemplateRenderer.TemplateRenderException.class,
                () -> renderer.renderString("{{input.missing}}", ctx));
    }

    @Test
    void renderString_unknownNamespace_throws() {
        PluginExecutionContext ctx = ctx(Map.of(), null);
        assertThrows(PluginTemplateRenderer.TemplateRenderException.class,
                () -> renderer.renderString("{{wat.x}}", ctx));
    }

    @Test
    void renderMapping_url_headers_body_query() {
        PluginHttpMapping mapping = new PluginHttpMapping();
        mapping.setMethod("POST");
        mapping.setUrlTemplate("https://x.com/{{input.path}}");
        mapping.setHeadersTemplate("{\"X-Sig\":\"{{secrets.sig}}\"}");
        mapping.setQueryTemplate("{\"q\":\"{{input.kw}}\"}");
        mapping.setBodyTemplate("{\"name\":\"{{input.name}}\",\"age\":{{input.age}}}");
        mapping.setBodyContentType("application/json");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "create");
        input.put("kw", "hello");
        input.put("name", "Tom");
        input.put("age", 25);
        Map<String, Object> secrets = new LinkedHashMap<>();
        secrets.put("sig", "abc");

        RenderedRequest req = renderer.render(mapping, ctx(input, secrets));

        assertEquals("POST", req.getMethod());
        assertEquals("https://x.com/create", req.getUrl());
        assertEquals("abc", req.getHeaders().get("X-Sig"));
        assertEquals("hello", req.getQuery().get("q"));
        assertTrue(req.getBody().contains("\"name\":\"Tom\""));
        // 数字保留类型（不变成字符串"25"）
        assertTrue(req.getBody().contains("\"age\":25"));
    }

    @Test
    void renderValue_wholeStringPlaceholder_preservesNumberType() {
        PluginExecutionContext ctx = ctx(Map.of("count", 42), null);
        Object rendered = renderer.renderValue("{{input.count}}", ctx);
        // 整串就一个占位符 → 应该保留原类型
        assertEquals(42, rendered);
    }

    @Test
    void renderValue_nestedMap_recursesIntoLeaves() {
        Map<String, Object> input = Map.of("u", "alice", "ts", 123);
        PluginExecutionContext ctx = ctx(input, null);
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("user", "{{input.u}}");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("created_at", "{{input.ts}}");
        template.put("meta", nested);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) renderer.renderValue(template, ctx);
        assertEquals("alice", out.get("user"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outMeta = (Map<String, Object>) out.get("meta");
        assertEquals(123, outMeta.get("created_at"));
    }
}

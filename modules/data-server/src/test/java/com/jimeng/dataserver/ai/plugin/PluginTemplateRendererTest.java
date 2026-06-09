package com.jimeng.dataserver.ai.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.service.PluginTemplateRenderer;
import com.jimeng.persistence.entity.PluginHttpMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    private PluginHttpMapping jsonBodyMapping(String bodyTemplate) {
        PluginHttpMapping mapping = new PluginHttpMapping();
        mapping.setMethod("POST");
        mapping.setUrlTemplate("https://x.com/q");
        mapping.setBodyTemplate(bodyTemplate);
        mapping.setBodyContentType("application/json");
        return mapping;
    }

    /** Array<Object> 入参：List<Map> 必须序列化成合法 JSON 数组，而不是 Java toString。 */
    @Test
    void renderBody_arrayOfObjects_serializedAsValidJson() throws Exception {
        PluginHttpMapping mapping = jsonBodyMapping("{\"items\": {{input.items}}}");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", 1);
        a.put("name", "a");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", 2);
        b.put("name", "b");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", List.of(a, b));

        RenderedRequest req = renderer.render(mapping, ctx(input, null));

        JsonNode body = new ObjectMapper().readTree(req.getBody());
        assertTrue(body.get("items").isArray());
        assertEquals(2, body.get("items").size());
        assertEquals(1, body.get("items").get(0).get("id").asInt());
        assertEquals("a", body.get("items").get(0).get("name").asText());
        assertEquals("b", body.get("items").get(1).get("name").asText());
    }

    /** Object 入参：嵌套 Map 必须序列化成合法 JSON 对象。 */
    @Test
    void renderBody_nestedObject_serializedAsValidJson() throws Exception {
        PluginHttpMapping mapping = jsonBodyMapping("{\"obj\": {{input.obj}}}");
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("k", "v");
        obj.put("n", 3);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("obj", obj);

        RenderedRequest req = renderer.render(mapping, ctx(input, null));

        JsonNode body = new ObjectMapper().readTree(req.getBody());
        assertEquals("v", body.get("obj").get("k").asText());
        assertEquals(3, body.get("obj").get("n").asInt());
    }

    /** Array<String>：String.valueOf 老逻辑会渲染成 [a, b]（元素无引号）破坏 JSON，新逻辑修复。 */
    @Test
    void renderBody_arrayOfStrings_serializedAsValidJson() throws Exception {
        PluginHttpMapping mapping = jsonBodyMapping("{\"tags\": {{input.tags}}}");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tags", List.of("a", "b"));

        RenderedRequest req = renderer.render(mapping, ctx(input, null));

        JsonNode body = new ObjectMapper().readTree(req.getBody());
        assertEquals("a", body.get("tags").get(0).asText());
        assertEquals("b", body.get("tags").get(1).asText());
    }

    /** 字符串里含双引号/换行：必须被转义，渲染后仍是合法 JSON 且原值无损。 */
    @Test
    void renderBody_stringWithSpecialChars_escaped() throws Exception {
        PluginHttpMapping mapping = jsonBodyMapping("{\"msg\": \"{{input.msg}}\"}");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("msg", "he said \"hi\"\nbye");

        RenderedRequest req = renderer.render(mapping, ctx(input, null));

        JsonNode body = new ObjectMapper().readTree(req.getBody());
        assertEquals("he said \"hi\"\nbye", body.get("msg").asText());
    }

    /** 标量与复杂值混排：字符串带引号、数字保留类型、数组对象序列化，全部并存。 */
    @Test
    void renderBody_mixedScalarAndComplex() throws Exception {
        PluginHttpMapping mapping = jsonBodyMapping(
                "{\"name\": \"{{input.name}}\", \"age\": {{input.age}}, \"items\": {{input.items}}}");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Tom");
        input.put("age", 25);
        input.put("items", List.of(item));

        RenderedRequest req = renderer.render(mapping, ctx(input, null));

        JsonNode body = new ObjectMapper().readTree(req.getBody());
        assertEquals("Tom", body.get("name").asText());
        assertEquals(25, body.get("age").asInt());
        assertEquals(1, body.get("items").get(0).get("id").asInt());
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

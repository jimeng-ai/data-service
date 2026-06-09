package com.jimeng.dataserver.ai.plugingen;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDraftToolSchemaTest {

    private final PluginDraftToolSchema schema = new PluginDraftToolSchema();

    @Test
    @SuppressWarnings("unchecked")
    void anthropicToolDefShape() {
        Map<String, Object> tool = (Map<String, Object>) schema.buildEmitToolDef("anthropic");
        assertEquals("emit_plugin_draft", tool.get("name"));
        assertTrue(tool.containsKey("input_schema"));
        Map<String, Object> s = (Map<String, Object>) tool.get("input_schema");
        assertEquals("object", s.get("type"));
        assertTrue(s.containsKey("$defs"), "应有递归 $defs/param");
        assertNotNull(((Map<String, Object>) s.get("properties")).get("tools"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void openaiToolDefShape() {
        Map<String, Object> tool = (Map<String, Object>) schema.buildEmitToolDef("openai");
        assertEquals("function", tool.get("type"));
        Map<String, Object> fn = (Map<String, Object>) tool.get("function");
        assertEquals("emit_plugin_draft", fn.get("name"));
        assertTrue(fn.containsKey("parameters"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceToolPerProtocol() {
        Map<String, Object> a = new LinkedHashMap<>();
        schema.forceTool(a, "anthropic");
        Map<String, Object> at = (Map<String, Object>) a.get("tool_choice");
        assertEquals("tool", at.get("type"));
        assertEquals("emit_plugin_draft", at.get("name"));

        Map<String, Object> o = new LinkedHashMap<>();
        schema.forceTool(o, "openai");
        Map<String, Object> ot = (Map<String, Object>) o.get("tool_choice");
        assertEquals("function", ot.get("type"));
        assertEquals("emit_plugin_draft", ((Map<String, Object>) ot.get("function")).get("name"));
    }
}

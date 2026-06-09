package com.jimeng.dataserver.ai.plugingen;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.plugingen.dto.GenerateRequest;
import com.jimeng.dataserver.ai.plugingen.dto.ParamSpec;
import com.jimeng.dataserver.ai.plugingen.dto.PluginDraft;
import com.jimeng.dataserver.ai.plugingen.dto.ToolSpec;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParserRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginAiServiceTest {

    /** 草稿 input JSON：故意塞进需要兜底归一的脏数据（integer / 数字枚举 / 对象在 query / baseUrl / confidence 越界）。 */
    private static final String DRAFT_INPUT = """
            {
              "plugin": { "name": "企微营销", "baseUrl": "http://evil.example.com",
                          "auth": { "type": "BEARER", "notes": "需人工填 token" } },
              "tools": [{
                "name": "create_moment", "method": "post", "path": "/api/v2/wecom/moment/result",
                "confidence": 1.7,
                "params": [
                  { "name": "id", "type": "string", "location": "body", "required": true },
                  { "name": "count", "type": "integer", "location": "query" },
                  { "name": "scope", "type": "string", "location": "body", "enumValues": ["1","2"] },
                  { "name": "flag", "type": "number", "location": "query", "enumValues": ["x"] },
                  { "name": "obj", "type": "object", "location": "query",
                    "fields": [ { "name": "a", "type": "string" } ] },
                  { "name": "items", "type": "array", "location": "query", "itemType": "object",
                    "itemFields": [ { "name": "sku", "type": "string" } ] }
                ]
              }]
            }
            """;

    private ProviderConfig cfg(String protocol) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setBaseUrl("http://provider.test/v1");
        cfg.setApiKey("k");
        cfg.getChat().setProtocol(protocol);
        cfg.getChat().setModel("test-model");
        return cfg;
    }

    private PluginAiService service(RequestService rs, String protocol) {
        AiSelectionProperties selection = mock(AiSelectionProperties.class);
        when(selection.getProvider()).thenReturn("test");
        AiProviderProperties props = new AiProviderProperties();
        props.setProviders(Map.of("test", cfg(protocol)));
        return new PluginAiService(selection, props, rs,
                new ClaudeProtocolAdapter(), new OpenAiProtocolAdapter(), new PluginDraftToolSchema(),
                mock(DocumentParserRegistry.class));
    }

    private ParamSpec param(ToolSpec t, String name) {
        return t.getParams().stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    @Test
    void generate_anthropic_mapsAndSanitizes() {
        String resp = "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"emit_plugin_draft\",\"input\":"
                + DRAFT_INPUT + "}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}";
        RequestService rs = mock(RequestService.class);
        when(rs.post(anyString(), any(), any(), any())).thenReturn(new RequestService.HttpResp(200, resp));

        GenerateRequest req = new GenerateRequest();
        req.setText("POST /api/v2/wecom/moment/result ...");
        PluginDraft draft = service(rs, "anthropic").generate(req, "trace");

        // baseUrl 一律置空
        assertNull(draft.getPlugin().getBaseUrl());
        assertEquals("BEARER", draft.getPlugin().getAuth().getType());

        assertEquals(1, draft.getTools().size());
        ToolSpec t = draft.getTools().get(0);
        assertEquals("POST", t.getMethod());               // 大写归一

        assertEquals("number", param(t, "count").getType()); // integer → number
        assertEquals("query", param(t, "count").getLocation());

        ParamSpec scope = param(t, "scope");
        assertEquals(List.of("1", "2"), scope.getEnumValues()); // string 保留候选值

        assertNull(param(t, "flag").getEnumValues());        // 非 string 丢候选值

        ParamSpec obj = param(t, "obj");
        assertEquals("body", obj.getLocation());             // object 强制 body
        assertEquals(1, obj.getFields().size());

        ParamSpec items = param(t, "items");
        assertEquals("body", items.getLocation());           // array 强制 body
        assertEquals("object", items.getItemType());
        assertEquals(1, items.getItemFields().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_openai_forcesFunctionToolChoice() {
        // OpenAI 把 input 放在 choices[0].message.tool_calls[0].function.arguments（JSON 字符串）
        String args = DRAFT_INPUT.replace("\n", " ").replace("\"", "\\\"");
        String resp = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"emit_plugin_draft\",\"arguments\":\"" + args + "\"}}]}}]}";
        RequestService rs = mock(RequestService.class);
        ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.forClass(Map.class);
        when(rs.post(anyString(), any(), any(), bodyCap.capture()))
                .thenReturn(new RequestService.HttpResp(200, resp));

        GenerateRequest req = new GenerateRequest();
        req.setText("some doc");
        PluginDraft draft = service(rs, "openai").generate(req, "trace");

        assertEquals(1, draft.getTools().size());
        assertEquals("POST", draft.getTools().get(0).getMethod());

        // 断言强制了 openai 形态的 tool_choice
        Map<String, Object> sent = bodyCap.getValue();
        Object tc = sent.get("tool_choice");
        assertTrue(tc instanceof Map, "tool_choice 应为对象");
        Map<String, Object> tcm = (Map<String, Object>) tc;
        assertEquals("function", tcm.get("type"));
        Map<String, Object> fn = (Map<String, Object>) tcm.get("function");
        assertEquals("emit_plugin_draft", fn.get("name"));
        // openai 请求用 max_completion_tokens
        assertNotNull(sent.get("max_completion_tokens"));
    }
}

package com.jimeng.dataserver.ai.plugingen;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造强制 LLM 输出 {@link com.jimeng.dataserver.ai.plugingen.dto.PluginDraft} 的工具定义，
 * 以及对应协议的 tool_choice（强制只调这一个工具，拿到结构化结果而非自由文本）。
 *
 * <p>协议感知：anthropic 用 {@code {name, description, input_schema}}；
 * openai 用 {@code {type:function, function:{name, description, parameters}}}。
 * 参考已有的 {@code ClaudeProtocolAdapter.buildActivateSkillsToolDef}。
 */
@Component
public class PluginDraftToolSchema {

    public static final String TOOL_NAME = "emit_plugin_draft";
    private static final String ANTHROPIC = "anthropic";

    private static final List<String> PARAM_TYPES = List.of("string", "number", "boolean", "object", "array");
    private static final List<String> ITEM_TYPES = List.of("string", "number", "boolean", "object");
    private static final List<String> LOCATIONS = List.of("query", "body", "path");
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final List<String> AUTH_TYPES = List.of("NONE", "API_KEY", "BEARER", "BASIC", "HMAC");

    private static final String DESC =
            "把解析出的 API 文档转成插件草稿：每个 HTTP 端点抽成一个 tool，"
            + "含参数名/类型/传入位置/枚举候选值与输出字段。只通过此工具输出，不要输出其它文字。";

    /** 按协议返回工具定义。 */
    public Object buildEmitToolDef(String protocol) {
        Map<String, Object> schema = buildSchema();
        if (isAnthropic(protocol)) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", TOOL_NAME);
            tool.put("description", DESC);
            tool.put("input_schema", schema);
            return tool;
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", TOOL_NAME);
        function.put("description", DESC);
        function.put("parameters", schema);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    /** 按协议设置 tool_choice，强制只调 emit_plugin_draft。 */
    public void forceTool(Map<String, Object> body, String protocol) {
        if (isAnthropic(protocol)) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("type", "tool");
            tc.put("name", TOOL_NAME);
            body.put("tool_choice", tc);
        } else {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", TOOL_NAME);
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("type", "function");
            tc.put("function", fn);
            body.put("tool_choice", tc);
        }
    }

    private boolean isAnthropic(String protocol) {
        return ANTHROPIC.equalsIgnoreCase(protocol);
    }

    // ------------------------------------------------------------------ schema

    private Map<String, Object> buildSchema() {
        // 入参规格（递归）：放进 $defs，子字段用 $ref 引用，避免 Java Map 自引用导致序列化死循环。
        Map<String, Object> paramRef = ref("#/$defs/param");
        Map<String, Object> paramProps = new LinkedHashMap<>();
        paramProps.put("name", prim("string", "参数名"));
        paramProps.put("type", enumP(PARAM_TYPES, "参数类型；枚举值请用 string + enumValues 表达，integer 归 number"));
        paramProps.put("description", prim("string", "参数说明（供 LLM 理解；把枚举含义写进来）"));
        paramProps.put("required", prim("boolean", "是否必填"));
        paramProps.put("location", enumP(LOCATIONS, "传入位置；对象/数组只能放 body，拿不准用 body"));
        paramProps.put("enumValues", arr(prim("string", null), "候选值（仅 type=string 有意义）"));
        paramProps.put("fields", arr(paramRef, "type=object 时的子字段"));
        paramProps.put("itemType", enumP(ITEM_TYPES, "type=array 时的元素类型"));
        paramProps.put("itemFields", arr(paramRef, "元素为 object 时的子字段"));
        Map<String, Object> paramDef = obj(paramProps, List.of("name", "type"));

        // 输出字段（扁平）
        Map<String, Object> outProps = new LinkedHashMap<>();
        outProps.put("name", prim("string", "输出字段名"));
        outProps.put("type", enumP(PARAM_TYPES, "输出字段类型"));
        outProps.put("description", prim("string", "字段说明"));
        Map<String, Object> outDef = obj(outProps, List.of("name"));

        // 固定请求头
        Map<String, Object> hdrProps = new LinkedHashMap<>();
        hdrProps.put("key", prim("string", "Header 名"));
        hdrProps.put("value", prim("string", "Header 值（固定值，如 application/json）"));
        Map<String, Object> hdrDef = obj(hdrProps, List.of("key"));

        // 单个工具
        Map<String, Object> toolProps = new LinkedHashMap<>();
        toolProps.put("name", prim("string",
                "工具的函数名：必须是英文 snake_case（只含 a-z、0-9、_），供 LLM 调用，按接口语义命名"));
        toolProps.put("title", prim("string",
                "工具的中文展示名（简洁、给人看），如「创建朋友圈回调」"));
        toolProps.put("description", prim("string", "工具用途（供 LLM 决定是否调用）"));
        toolProps.put("method", enumP(METHODS, "HTTP 方法（大写）"));
        toolProps.put("path", prim("string", "接口路径，如 /api/v2/.../result（不含域名）"));
        toolProps.put("params", arr(paramRef, "入参列表"));
        toolProps.put("headers", arr(hdrDef, "固定请求头"));
        toolProps.put("bodyContentType", prim("string", "请求体类型，如 application/json"));
        toolProps.put("outputs", arr(outDef, "输出字段（来自示例响应，可留空）"));
        toolProps.put("warnings", arr(prim("string", null), "推断/不确定/需人工确认的点"));
        Map<String, Object> toolDef = obj(toolProps, List.of("name", "method", "path"));

        // 鉴权
        Map<String, Object> authProps = new LinkedHashMap<>();
        authProps.put("type", enumP(AUTH_TYPES, "鉴权方式"));
        authProps.put("in", enumP(List.of("header", "query"), "API_KEY 放在 header 还是 query"));
        authProps.put("name", prim("string", "鉴权字段名，如 Authorization / X-Api-Key"));
        authProps.put("notes", prim("string", "鉴权说明（密钥由人工填，不要编造）"));
        Map<String, Object> authDef = obj(authProps, List.of());

        // 插件元信息（注意：不含 baseUrl，绝不让模型填域名）
        Map<String, Object> pluginProps = new LinkedHashMap<>();
        pluginProps.put("name", prim("string", "插件名"));
        pluginProps.put("description", prim("string", "插件说明"));
        pluginProps.put("auth", authDef);
        Map<String, Object> pluginDef = obj(pluginProps, List.of("name"));

        // 根
        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("plugin", pluginDef);
        rootProps.put("tools", arr(toolDef, "解析出的工具列表"));
        rootProps.put("warnings", arr(prim("string", null), "整体层面的提示"));
        Map<String, Object> root = obj(rootProps, List.of("plugin", "tools"));

        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("param", paramDef);
        root.put("$defs", defs);
        return root;
    }

    // ------------------------------------------------------------------ schema helpers

    private Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (required != null && !required.isEmpty()) m.put("required", required);
        return m;
    }

    private Map<String, Object> arr(Object items, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        if (description != null) m.put("description", description);
        return m;
    }

    private Map<String, Object> prim(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (description != null) m.put("description", description);
        return m;
    }

    private Map<String, Object> enumP(List<String> values, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", values);
        if (description != null) m.put("description", description);
        return m;
    }

    private Map<String, Object> ref(String pointer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$ref", pointer);
        return m;
    }
}

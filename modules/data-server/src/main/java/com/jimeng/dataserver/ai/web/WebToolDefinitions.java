package com.jimeng.dataserver.ai.web;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 web 工具的工具定义（模型可见名 web_search / web_fetch）。由 AiConversationLoop 在
 * ai.web-search 启用时无条件注入进请求体的 tools 列表，对所有 Agent 永远在场（不走 skill 发现流程）。
 */
public final class WebToolDefinitions {

    private WebToolDefinitions() {
    }

    public static final SkillToolDefinition WEB_SEARCH = buildSearch();
    public static final SkillToolDefinition WEB_FETCH = buildFetch();

    private static SkillToolDefinition buildSearch() {
        Map<String, Object> query = prop("string", "the natural-language search query");
        Map<String, Object> count = prop("integer", "max results to return (1-10)");
        count.put("minimum", 1);
        count.put("maximum", 10);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        properties.put("count", count);
        return new SkillToolDefinition(
                "web_search",
                "Search the public web for up-to-date information. Returns a ranked list of {title, url, snippet}. "
                        + "Use web_fetch to read a result's full page when the snippet is not enough.",
                objectSchema(properties, List.of("query")));
    }

    private static SkillToolDefinition buildFetch() {
        Map<String, Object> url = prop("string", "the absolute http(s) URL to fetch (usually from a web_search result)");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", url);
        return new SkillToolDefinition(
                "web_fetch",
                "Fetch a single public web page by URL and return its readable text. Private/internal addresses are blocked.",
                objectSchema(properties, List.of("url")));
    }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }
}

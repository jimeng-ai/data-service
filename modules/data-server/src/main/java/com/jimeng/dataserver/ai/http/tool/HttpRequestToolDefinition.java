package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code http_request} 工具（设计文档 §4.2）。对话平面与沙箱平面同名、同参数、同返回形状
 * （沙箱那边是宿主侧 MCP 工具，回调同一个网关）。定义是固定的：多个已激活 skill 都并入它时，按名字去重不会丢信息——
 * 每个 skill 能用哪些 key、哪些域名，写在它自己的说明后面（{@link #notes}）。
 */
public final class HttpRequestToolDefinition {

    public static final String NAME = "http_request";

    public static final SkillToolDefinition DEF = new SkillToolDefinition(NAME,
            "调用已激活技能里声明过的外部接口。key 写成 {{名字}}（例如请求头 Authorization: Bearer {{CRM_TOKEN}}），"
                    + "平台发出时替换成真值，回包里出现的 key 会被抹回 {{名字}}。只能访问技能声明过的域名；带 key 的请求不跟随重定向。"
                    + "返回 status、主要响应头和正文：文本超过 2 万字会截断，二进制只返回类型和大小。",
            schema());

    private HttpRequestToolDefinition() {}

    private static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("method", Map.of("type", "string", "description", "GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS"));
        props.put("url", Map.of("type", "string", "description", "完整网址，http:// 或 https:// 开头"));
        props.put("headers", Map.of("type", "object", "description", "请求头，键和值都是字符串"));
        props.put("body", Map.of("type", "string", "description", "请求体原文；JSON 也按字符串传"));
        props.put("as_text", Map.of("type", "boolean", "description", "true：把 HTML 回包转成纯文本（读网页时用）"));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", List.of("method", "url"));
        return s;
    }

    /** 附在技能说明后面、告诉模型这个技能能用哪些 key 和域名。没有 HTTP 声明返回 null。只有名字和域名，没有值。 */
    public static String notes(SkillManifest m) {
        if (m == null || !m.hasHttp()) return null;
        StringBuilder sb = new StringBuilder("（平台说明）这个技能可以调用外部接口：用 http_request 工具，网址写完整的 https://…");
        for (SkillManifest.KeyDecl k : m.keys()) {
            sb.append("\n- 可用的 key：{{").append(k.name()).append("}}，只能发往 ").append(String.join("、", k.hosts()));
            if (k.methods() != null) sb.append("；方法 ").append(String.join("、", k.methods()));
            if (k.paths() != null) sb.append("；路径 ").append(String.join("、", k.paths()));
        }
        if (!m.hosts().isEmpty()) sb.append("\n- 不带 key 也能访问：").append(String.join("、", m.hosts()));
        if (Boolean.TRUE.equals(m.web())) sb.append("\n- 可以访问任意公网网址（只限 80、443 端口）");
        sb.append("\nkey 只写 {{名字}}，平台发出时替换成真值，回包里的 key 会被抹成 {{名字}}。不要向用户索要或复述 key。");
        return sb.toString();
    }
}

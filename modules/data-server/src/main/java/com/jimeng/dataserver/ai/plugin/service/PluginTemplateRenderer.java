package com.jimeng.dataserver.ai.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.persistence.entity.PluginHttpMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板渲染器：将 {@code {{namespace.path}}} 占位符替换为 {@link PluginExecutionContext} 中的值。
 *
 * <p>命名空间：{@code input} / {@code secrets} / {@code env} / {@code meta}。
 * 缺失键走严格模式抛 {@link TemplateRenderException}，由上层包成 PluginError 返回。
 */
@Slf4j
@Service
public class PluginTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_.]*)\\s*}}");

    /** 异常：模板渲染失败（占位符缺失/格式错） */
    public static class TemplateRenderException extends RuntimeException {
        public TemplateRenderException(String message) {
            super(message);
        }
    }

    public RenderedRequest render(PluginHttpMapping mapping, PluginExecutionContext ctx) {
        RenderedRequest req = new RenderedRequest();
        req.setMethod(mapping.getMethod() == null ? "GET" : mapping.getMethod().toUpperCase());
        req.setUrl(renderString(mapping.getUrlTemplate(), ctx));
        if (StringUtils.hasText(mapping.getBodyContentType())) {
            req.setContentType(mapping.getBodyContentType());
        }

        // headers
        Map<String, Object> headers = parseJsonMap(mapping.getHeadersTemplate());
        for (Map.Entry<String, Object> e : headers.entrySet()) {
            Object rendered = renderValue(e.getValue(), ctx);
            if (rendered != null) {
                req.addHeader(e.getKey(), String.valueOf(rendered));
            }
        }

        // query
        Map<String, Object> query = parseJsonMap(mapping.getQueryTemplate());
        for (Map.Entry<String, Object> e : query.entrySet()) {
            Object rendered = renderValue(e.getValue(), ctx);
            if (rendered != null) {
                req.addQuery(e.getKey(), rendered);
            }
        }

        // body —— 先字符串替换占位符再校验 JSON。
        // 原因：模板里 {{input.name}} 这类占位符不是合法 JSON，无法在替换前 parse。
        if (StringUtils.hasText(mapping.getBodyTemplate())) {
            String contentType = req.getContentType() == null ? "" : req.getContentType().toLowerCase();
            if (contentType.contains("json")) {
                // JSON body 用专用渲染：占位符按解析出的真实类型序列化成 JSON 片段，
                // 这样 Object / Array / Array<Object> 等复杂入参才能落成合法 JSON（renderString
                // 会把 List/Map 渲染成 Java toString，不是合法 JSON）。详见 renderJsonBody。
                String rendered = renderJsonBody(mapping.getBodyTemplate(), ctx);
                try {
                    // 渲染后再校验一遍并归一化输出，确保是合法 JSON
                    JsonNode parsed = CommonUtil.getObjectMapper().readTree(rendered);
                    req.setBody(CommonUtil.getObjectMapper().writeValueAsString(parsed));
                } catch (Exception e) {
                    throw new TemplateRenderException(
                            "body 渲染后不是合法 JSON: " + e.getMessage() + "，rendered=" + rendered);
                }
            } else {
                // 非 JSON content-type（form-urlencoded 等）直接用渲染后的字符串
                req.setBody(renderString(mapping.getBodyTemplate(), ctx));
            }
        }

        return req;
    }

    /** 渲染单个字符串（暴露给外部，便于 HmacAuthApplier 的 sign_template 等场景复用） */
    public String renderString(String template, PluginExecutionContext ctx) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(template, last, m.start());
            String path = m.group(1);
            Object value = resolve(path, ctx);
            sb.append(value == null ? "" : String.valueOf(value));
            last = m.end();
        }
        sb.append(template, last, template.length());
        return sb.toString();
    }

    /**
     * JSON body 专用渲染：把每个 {@code {{...}}} 占位符替换成「按真实类型序列化的 JSON 片段」。
     *
     * <p>与 {@link #renderString} 的关键区别：renderString 用 {@link String#valueOf} 拼接，
     * 对 List/Map 会得到 Java toString（{@code [{a=1}]} / {@code {k=v}}），不是合法 JSON；
     * 这里改用 Jackson 序列化，从而支持 Object / Array / Array&lt;Object&gt; 等复杂入参，
     * 同时字符串会被正确加引号 + 转义（修掉值里含 {@code "}、换行符时破坏 JSON 的老问题）。
     *
     * <p>前端生成的 body 模板里：字符串/枚举占位符被一对引号包裹（{@code "{{input.x}}"}），
     * 其余类型是裸占位符（{@code {{input.x}}}）。这里在占位符两侧恰好是一对引号时，连引号一起
     * 替换，统一交给序列化重新加引号，避免出现 {@code ""value""} 这种双引号。
     *
     * <p>仅供 JSON body 使用——url / headers / HMAC sign_template 仍走 {@link #renderString}，
     * 不能改它们的逐字节输出（HMAC 会对渲染后的原文签名）。
     */
    private String renderJsonBody(String template, PluginExecutionContext ctx) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            // 占位符恰好被一对引号完整包裹 → 视为字符串位，连引号一起吃掉，由序列化重新加引号
            boolean quoted = start > 0 && template.charAt(start - 1) == '"'
                    && end < template.length() && template.charAt(end) == '"';
            int cutStart = quoted ? start - 1 : start;
            int cutEnd = quoted ? end + 1 : end;
            sb.append(template, last, cutStart);
            sb.append(toJsonLiteral(resolve(m.group(1), ctx)));
            last = cutEnd;
        }
        sb.append(template, last, template.length());
        return sb.toString();
    }

    /** 把任意值序列化成合法 JSON 字面量：字符串自动加引号+转义，List/Map → 数组/对象，null → null。 */
    private String toJsonLiteral(Object value) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new TemplateRenderException("body 参数序列化为 JSON 失败: " + e.getMessage());
        }
    }

    /** 递归渲染任意值（String/Map/List/Number/Boolean），保留原类型 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object renderValue(Object value, PluginExecutionContext ctx) {
        if (value == null) return null;
        if (value instanceof String s) {
            // 整串占位符 → 保留原类型；其余 → 字符串拼接
            Matcher m = PLACEHOLDER.matcher(s);
            if (m.matches()) {
                return resolve(m.group(1), ctx);
            }
            return renderString(s, ctx);
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                Object rendered = renderValue(e.getValue(), ctx);
                if (rendered != null) {
                    out.put(String.valueOf(e.getKey()), rendered);
                }
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object rendered = renderValue(item, ctx);
                if (rendered != null) out.add(rendered);
            }
            return out;
        }
        if (value instanceof JsonNode node) {
            return renderValue(nodeToObject(node), ctx);
        }
        return value;
    }

    private Object resolve(String path, PluginExecutionContext ctx) {
        if (path == null || path.isEmpty()) {
            throw new TemplateRenderException("empty placeholder path");
        }
        int dot = path.indexOf('.');
        String namespace = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? "" : path.substring(dot + 1);

        Map<String, Object> source;
        switch (namespace) {
            case "input":   source = ctx.getInput(); break;
            case "secrets": source = ctx.getSecrets(); break;
            case "env":     source = ctx.getEnv(); break;
            case "meta":    source = ctx.getMeta(); break;
            default:
                throw new TemplateRenderException("unknown namespace: " + namespace);
        }
        if (rest.isEmpty()) {
            return source;
        }

        Object current = source;
        for (String part : rest.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                current = null;
            }
            if (current == null) {
                throw new TemplateRenderException("missing " + namespace + "." + rest);
            }
        }
        return current;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) return new LinkedHashMap<>();
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("解析模板 JSON 失败, json={}, error={}", json, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private JsonNode parseJsonNode(String json) {
        try {
            return CommonUtil.getObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new TemplateRenderException("body_template JSON 解析失败: " + e.getMessage());
        }
    }

    private Object nodeToObject(JsonNode node) {
        try {
            return CommonUtil.getObjectMapper().treeToValue(node, Object.class);
        } catch (Exception e) {
            throw new TemplateRenderException("JsonNode 转换失败: " + e.getMessage());
        }
    }
}

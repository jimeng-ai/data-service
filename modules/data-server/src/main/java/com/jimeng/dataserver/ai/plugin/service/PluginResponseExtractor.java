package com.jimeng.dataserver.ai.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.PluginHttpMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 响应抽取：按 JSONPath 截取子树 + 数组截断。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>多字段映射</b>：{@code response_extract} 为 {@code [{"name","path"}, ...]} JSON 数组时，
 *       逐字段抽取并返回 {@code {name: value}} 结构化对象。</li>
 *   <li><b>单条路径</b>（旧版/兼容）：直接是一条 JSONPath 字符串。</li>
 * </ul>
 *
 * <p>JSONPath 支持的子集：
 * <ul>
 *   <li>{@code $} 或留空 → 全文返回</li>
 *   <li>{@code $.foo.bar.baz} → 嵌套对象路径</li>
 *   <li>{@code $.foo[0]} → 数组下标</li>
 *   <li>{@code $.foo[*]} → 数组通配（返回整个列表）</li>
 * </ul>
 * 复杂表达式（过滤、切片等）不在 MVP 范围；后期需要可换成 jayway/json-path。
 */
@Slf4j
@Service
public class PluginResponseExtractor {

    private static final int DEFAULT_MAX_ITEMS = 50;

    public Object extract(String rawBody, PluginHttpMapping mapping) {
        if (!StringUtils.hasText(rawBody)) {
            return null;
        }

        JsonNode root;
        try {
            root = CommonUtil.getObjectMapper().readTree(rawBody);
        } catch (Exception e) {
            // 非 JSON 响应直接返字符串
            return rawBody;
        }

        String cfg = mapping == null ? null : mapping.getResponseExtract();
        Integer maxItemsCfg = mapping == null ? null : mapping.getResponseMaxItems();
        int maxItems = maxItemsCfg == null || maxItemsCfg <= 0 ? DEFAULT_MAX_ITEMS : maxItemsCfg;

        // 多字段映射模式：response_extract 为 [{"name","path"}, ...] 的 JSON 数组
        // → 逐个抽取，返回结构化对象 {name: value}，便于在 ToB 控制台按字段配置/展示
        Map<String, String> fieldMappings = parseFieldMappings(cfg);
        if (fieldMappings != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : fieldMappings.entrySet()) {
                JsonNode v = applyPath(root, e.getValue());
                result.put(e.getKey(), nodeToObjectWithTruncation(v, maxItems));
            }
            return result;
        }

        // 旧版：单条 JSONPath
        JsonNode extracted = applyPath(root, cfg);
        return nodeToObjectWithTruncation(extracted, maxItems);
    }

    /**
     * 解析多字段抽取配置：{@code [{"name":"city","path":"$.result.city"}, ...]}。
     *
     * <p>非 JSON 数组（旧版单条 JSONPath 如 {@code $.main}，或为空）返回 {@code null}，调用方走旧逻辑。
     */
    private Map<String, String> parseFieldMappings(String cfg) {
        if (!StringUtils.hasText(cfg)) {
            return null;
        }
        String s = cfg.trim();
        if (s.charAt(0) != '[') {
            return null;
        }
        try {
            JsonNode arr = CommonUtil.getObjectMapper().readTree(s);
            if (!arr.isArray()) {
                return null;
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode n : arr) {
                String name = n.path("name").asText("");
                String path = n.path("path").asText("");
                if (!name.isEmpty()) {
                    out.put(name, path);
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            log.warn("响应抽取多字段配置解析失败，回退单路径: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode applyPath(JsonNode root, String path) {
        if (!StringUtils.hasText(path) || "$".equals(path.trim())) {
            return root;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        else if (normalized.startsWith("$")) normalized = normalized.substring(1);

        JsonNode current = root;
        for (String segment : splitSegments(normalized)) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = stepInto(current, segment);
        }
        return current;
    }

    private JsonNode stepInto(JsonNode node, String segment) {
        // segment 形如：foo / foo[0] / foo[*] / [0] / [*]
        String key = segment;
        Integer index = null;
        boolean wildcard = false;

        int bracket = segment.indexOf('[');
        if (bracket >= 0) {
            key = segment.substring(0, bracket);
            int end = segment.indexOf(']', bracket);
            if (end < 0) {
                throw new IllegalArgumentException("非法 JSONPath: " + segment);
            }
            String idxStr = segment.substring(bracket + 1, end).trim();
            if ("*".equals(idxStr)) wildcard = true;
            else {
                try {
                    index = Integer.parseInt(idxStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("JSONPath 索引非法: " + idxStr);
                }
            }
        }

        if (!key.isEmpty()) {
            node = node.get(key);
            if (node == null) return null;
        }
        if (wildcard) {
            // 对数组返回原数组节点
            return node;
        }
        if (index != null) {
            return node.isArray() ? node.get(index) : null;
        }
        return node;
    }

    private List<String> splitSegments(String path) {
        // 支持 "foo.bar[0].baz" 或 "foo[*].bar"
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) segments.add(current.toString());
        return segments;
    }

    private Object nodeToObjectWithTruncation(JsonNode node, int maxItems) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray() && node.size() > maxItems) {
            List<Object> truncated = new ArrayList<>(maxItems);
            for (int i = 0; i < maxItems; i++) {
                truncated.add(nodeToObjectWithTruncation(node.get(i), maxItems));
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("items", truncated);
            wrapper.put("_truncated", Boolean.TRUE);
            wrapper.put("_total", node.size());
            wrapper.put("_returned", maxItems);
            return wrapper;
        }
        try {
            return CommonUtil.getObjectMapper().treeToValue(node, Object.class);
        } catch (Exception e) {
            log.warn("JsonNode 转 Object 失败, error={}", e.getMessage());
            return node.toString();
        }
    }
}

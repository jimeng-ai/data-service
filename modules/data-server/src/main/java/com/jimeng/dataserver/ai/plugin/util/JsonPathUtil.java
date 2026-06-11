package com.jimeng.dataserver.ai.plugin.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jimeng.common.core.utils.CommonUtil;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONPath 子集求值工具（从 {@link com.jimeng.dataserver.ai.plugin.service.PluginResponseExtractor}
 * 抽出，供响应抽取与 token 抽取共用）。
 *
 * <p>支持的子集：
 * <ul>
 *   <li>{@code $} 或留空 → 全文返回</li>
 *   <li>{@code $.foo.bar.baz} → 嵌套对象路径</li>
 *   <li>{@code $.foo[0]} → 数组下标</li>
 *   <li>{@code $.foo[*]} → 数组通配（剩余路径逐项映射后收成数组）</li>
 * </ul>
 * 复杂表达式（过滤、切片等）不在范围；后期需要可换成 jayway/json-path。
 */
public final class JsonPathUtil {

    private JsonPathUtil() {}

    /** 按 JSONPath 子集求值；命中返回对应 {@link JsonNode}，未命中返回 {@code null}。 */
    public static JsonNode apply(JsonNode root, String path) {
        if (!StringUtils.hasText(path) || "$".equals(path.trim())) {
            return root;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        else if (normalized.startsWith("$")) normalized = normalized.substring(1);
        return applySegments(root, splitSegments(normalized), 0);
    }

    private static JsonNode applySegments(JsonNode node, List<String> segments, int idx) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (idx >= segments.size()) {
            return node;
        }

        // segment 形如：foo / foo[0] / foo[*] / [0] / [*]
        String segment = segments.get(idx);
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

        JsonNode current = node;
        if (!key.isEmpty()) {
            current = current.get(key);
            if (current == null) return null;
        }

        if (wildcard) {
            if (current == null || !current.isArray()) {
                return null;
            }
            // 逐项映射剩余路径；剩余路径为空时即返回元素本身（等价旧的整数组返回）。
            ArrayNode out = CommonUtil.getObjectMapper().createArrayNode();
            for (JsonNode el : current) {
                JsonNode v = applySegments(el, segments, idx + 1);
                if (v != null && !v.isMissingNode() && !v.isNull()) {
                    out.add(v);
                }
            }
            return out;
        }
        if (index != null) {
            current = current.isArray() ? current.get(index) : null;
        }
        return applySegments(current, segments, idx + 1);
    }

    private static List<String> splitSegments(String path) {
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
}

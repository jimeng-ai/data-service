package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.http.policy.HostPattern;
import com.jimeng.dataserver.ai.http.policy.PathPattern;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * skill 根目录的 {@code secrets.json}（设计文档 §3.3）：
 * <pre>{"CRM_TOKEN": {"value": "真值", "hosts": ["api.customer.com"], "methods": ["GET"], "paths": ["/v1/orders/**"]}}</pre>
 *
 * <p>★ 这个类经手 key 的真值。任何报错文案、toString、日志里都<b>不准出现 value</b>：
 * 报错会回到构建器界面和接口响应里，toString 可能被顺手打进日志。
 */
public final class SkillSecretsFile {

    public static final String FILE_NAME = SkillBundleRules.SECRETS_JSON;
    public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 太短的值没法在回包里安全地抹掉（会把正常文字切碎）。代理和连接器工具对 token 用的也是 8。 */
    public static final int MIN_VALUE_LENGTH = 8;
    public static final int MAX_KEYS = 32;
    public static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> FIELDS = Set.of("value", "hosts", "methods", "paths");
    private static final ObjectMapper JSON = new ObjectMapper();

    private SkillSecretsFile() {}

    /** 一个 key。{@code value == null} = 沿用上一版的值（§3.4 第 4 条）；methods / paths 为 null = 不限。 */
    public record Entry(String name, String value, List<String> hosts, List<String> methods, List<String> paths) {
        public SkillManifest.KeyDecl decl() {
            return new SkillManifest.KeyDecl(name, hosts, methods, paths);
        }

        @Override
        public String toString() {
            return "Entry[" + name + " → " + hosts + (value == null ? "，沿用上一版的值" : "") + "]";
        }
    }

    public record Parsed(List<Entry> entries, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static List<SkillManifest.KeyDecl> decls(List<Entry> entries) {
        return entries == null ? List.of() : entries.stream().map(Entry::decl).toList();
    }

    public static Parsed parse(byte[] json) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return new Parsed(List.of(), List.of("secrets.json 不是合法的 JSON"));
        }
        if (root == null || !root.isObject()) {
            return new Parsed(List.of(), List.of("secrets.json 必须是一个 JSON 对象：键是 key 的名字，值是 {value, hosts, methods, paths}"));
        }
        if (root.size() > MAX_KEYS) {
            return new Parsed(List.of(), List.of("secrets.json 最多 " + MAX_KEYS + " 个 key"));
        }
        List<Entry> entries = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            Entry e = parseEntry(f.getKey(), f.getValue(), errors);
            if (e != null) entries.add(e);
        }
        return new Parsed(List.copyOf(entries), List.copyOf(errors));
    }

    private static Entry parseEntry(String name, JsonNode node, List<String> errors) {
        int before = errors.size();
        if (!NAME.matcher(name).matches()) {
            errors.add("key 名「" + name + "」不合法：只能用大写字母、数字、下划线，以字母开头，最长 64");
        }
        if (!node.isObject()) {
            errors.add("key " + name + " 必须是对象，例如 {\"value\": \"…\", \"hosts\": [\"api.example.com\"]}");
            return null;
        }
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k)) unknown.add(k); });
        if (!unknown.isEmpty()) {
            errors.add("key " + name + " 里有不认识的字段：" + String.join(", ", unknown) + "（只认 value、hosts、methods、paths）");
        }

        String value = null;
        JsonNode v = node.get("value");
        if (v != null && !v.isNull()) {
            if (!v.isTextual()) {
                errors.add("key " + name + " 的 value 必须是字符串");
            } else {
                value = v.asText();
                if (value.codePointCount(0, value.length()) < MIN_VALUE_LENGTH) {
                    errors.add("key " + name + " 的值太短（至少 " + MIN_VALUE_LENGTH + " 个字符）：太短的值没法在回包里安全地抹掉");
                }
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                    errors.add("key " + name + " 的值里不能有换行或空字符");
                }
            }
        }

        List<String> hosts = new ArrayList<>();
        JsonNode h = node.get("hosts");
        if (h == null || !h.isArray() || h.isEmpty()) {
            errors.add("key " + name + " 必须写 hosts（非空列表）：只能发往哪些域名");
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : h) {
                if (!item.isTextual()) {
                    errors.add("key " + name + " 的 hosts 里每一项都必须是字符串");
                    continue;
                }
                try {
                    seen.add(HostPattern.parse(item.asText()).text());
                } catch (IllegalArgumentException ex) {
                    errors.add("key " + name + " 的 hosts 里的「" + item.asText() + "」" + ex.getMessage());   // HostPattern 的原因本身是完整说法（「不是合法的域名」等），与解析器的拼法一致
                }
            }
            hosts.addAll(seen);
        }

        List<String> methods = optionalList(node.get("methods"), name, "methods", errors);
        if (methods != null) {
            List<String> upper = new ArrayList<>();
            for (String m : methods) {
                String u = m.toUpperCase(Locale.ROOT);
                if (!METHODS.contains(u)) errors.add("key " + name + " 的 methods 里的「" + m + "」不是支持的方法（" + String.join("、", METHODS.stream().sorted().toList()) + "）");
                else if (!upper.contains(u)) upper.add(u);
            }
            methods = upper;
        }
        List<String> paths = optionalList(node.get("paths"), name, "paths", errors);
        if (paths != null) {
            for (String p : paths) {
                try {
                    PathPattern.parse(p);
                } catch (IllegalArgumentException ex) {
                    errors.add("key " + name + " 的 paths 里的「" + p + "」不合法：" + ex.getMessage());
                }
            }
        }
        if (errors.size() > before) return null;
        return new Entry(name, value, List.copyOf(hosts), methods == null ? null : List.copyOf(methods),
                paths == null ? null : List.copyOf(paths));
    }

    private static List<String> optionalList(JsonNode n, String name, String field, List<String> errors) {
        if (n == null || n.isNull()) return null;
        if (!n.isArray() || n.isEmpty()) {
            errors.add("key " + name + " 的 " + field + " 必须是非空列表；不限就别写这个字段");
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) {
                errors.add("key " + name + " 的 " + field + " 里每一项都必须是字符串");
                return null;
            }
            out.add(item.asText());
        }
        return out;
    }
}

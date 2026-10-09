package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jimeng.dataserver.ai.http.policy.HostPattern;
import com.jimeng.dataserver.ai.http.policy.PathPattern;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;

import java.io.IOException;
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
 * 报错会回到构建器界面和接口响应里（{@code ServiceException} 构造时还会把文案记进 ERROR 日志），toString 可能被顺手打进日志。
 * 作者写的东西（key 名、不认识的字段名、hosts / methods / paths 的条目）回显进文案之前，先用同一个文件里的值抹一遍
 * （{@link SecretValues#replace}）。
 */
public final class SkillSecretsFile {

    public static final String FILE_NAME = SkillBundleRules.SECRETS_JSON;
    public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 太短的值没法在回包里安全地抹掉（会把正常文字切碎）。代理和连接器工具对 token 用的也是 8。 */
    public static final int MIN_VALUE_LENGTH = 8;
    /** 太长的不是 key（是把整份证书、整个文件贴进来了）；每次发布还要在每个文件里找它，加密后也要塞得进 TEXT 列。 */
    public static final int MAX_VALUE_LENGTH = 8192;
    public static final int MAX_KEYS = 32;
    /** hosts / paths 的个数和长度也有上限：网关每个请求都要遍历它们。 */
    public static final int MAX_HOSTS = 32;
    public static final int MAX_PATHS = 64;
    public static final int MAX_PATH_LENGTH = 512;
    public static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    /** 草稿预览里值被换成的样子。 */
    public static final String MASK = "******";
    private static final Set<String> FIELDS = Set.of("value", "hosts", "methods", "paths");
    private static final Set<String> DECLARATION_FIELDS = Set.of("hosts", "methods", "paths");
    /**
     * 严格解析：默认的 Jackson 对同名 key 静默只留最后一个、对第一个值后面的内容静默忽略，被吞掉的值既不会加密、
     * 也不会在文件里被替换，原文就原样进了 bundle。两个开关都要开：重复的键、第一个对象之后还有内容，一律拒绝。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

    /**
     * @param values   文件里出现过的所有字符串形式的值（不论那一条是否合规）：报错抹值、草稿预览、DRAFT 同步都用它
     * @param readable 文件是一个读得出来的 JSON 对象。false 时不知道有哪些值，调用方对外就只能给 secrets.json 自己的错误
     */
    public record Parsed(List<Entry> entries, List<String> errors, SecretValues values, boolean readable) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static List<SkillManifest.KeyDecl> decls(List<Entry> entries) {
        return entries == null ? List.of() : entries.stream().map(Entry::decl).toList();
    }

    public static Parsed parse(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return unreadable(describe(e));
        } catch (IOException e) {
            return unreadable("secrets.json 不是合法的 JSON");
        }
        if (root == null || !root.isObject()) {
            return unreadable("secrets.json 必须是一个 JSON 对象：键是 key 的名字，值是 {value, hosts, methods, paths}");
        }
        SecretValues known = valuesIn(root);
        if (root.size() > MAX_KEYS) {
            return new Parsed(List.of(), List.of("secrets.json 最多 " + MAX_KEYS + " 个 key"), known, true);
        }
        List<String> errors = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            Entry e = parseEntry(f.getKey(), f.getValue(), errors, known);
            if (e != null) entries.add(e);
        }
        if (errors.isEmpty()) errors.addAll(crossCheck(entries, known));   // 格式都对了，再看值有没有跑进声明里、有没有两个 key 共用一个值
        return new Parsed(List.copyOf(entries), List.copyOf(errors), known, true);
    }

    private static Parsed unreadable(String error) {
        return new Parsed(List.of(), List.of(error), SecretValues.NONE, false);
    }

    /** 不拼 Jackson 自己的文案（它会带上源码片段），只按类别给固定的话。 */
    private static String describe(JsonProcessingException e) {
        String m = e.getOriginalMessage() == null ? "" : e.getOriginalMessage();
        if (m.startsWith("Duplicate field")) return "secrets.json 里有重复的键：同一个 key（以及 key 里的同一个字段）只能写一次";
        if (m.contains("Trailing token")) return "secrets.json 的第一个 JSON 对象后面还有别的内容：一个文件只能有一个对象，要加 key 就写进同一个对象里";
        return "secrets.json 不是合法的 JSON";
    }

    /** 文件里所有字符串形式的值：{@code {"K":{"value":"…"}}} 的 value，以及直接把值写成字符串的 {@code {"K":"…"}}。 */
    private static SecretValues valuesIn(JsonNode root) {
        List<SecretValues.Item> items = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode n = f.getValue();
            if (n.isTextual()) {
                items.add(new SecretValues.Item(f.getKey(), n.asText()));
            } else if (n.isObject() && n.path("value").isTextual()) {
                items.add(new SecretValues.Item(f.getKey(), n.path("value").asText()));
            }
        }
        return SecretValues.of(items);
    }

    private static Entry parseEntry(String rawName, JsonNode node, List<String> errors, SecretValues known) {
        int before = errors.size();
        String name = known.replace(rawName);   // 作者写的东西回显之前先抹值
        if (!NAME.matcher(rawName).matches()) {
            errors.add("key 名「" + name + "」不合法：只能用大写字母、数字、下划线，以字母开头，最长 64");
        }
        if (!node.isObject()) {
            errors.add("key " + name + " 必须是对象，例如 {\"value\": \"…\", \"hosts\": [\"api.example.com\"]}");
            return null;
        }
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k)) unknown.add(known.replace(k)); });
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
                if (value.length() > MAX_VALUE_LENGTH) {
                    errors.add("key " + name + " 的值太长（最多 " + MAX_VALUE_LENGTH + " 个字符）：key 不是整份文件或证书");
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
        } else if (h.size() > MAX_HOSTS) {
            errors.add("key " + name + " 的 hosts 最多 " + MAX_HOSTS + " 条");
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
                    errors.add("key " + name + " 的 hosts 里的「" + known.replace(item.asText()) + "」" + ex.getMessage());   // HostPattern 的原因本身是完整说法（「不是合法的域名」等），与解析器的拼法一致
                }
            }
            hosts.addAll(seen);
        }

        List<String> methods = optionalList(node.get("methods"), name, "methods", errors);
        if (methods != null) {
            List<String> upper = new ArrayList<>();
            for (String m : methods) {
                String u = m.toUpperCase(Locale.ROOT);
                if (!METHODS.contains(u)) errors.add("key " + name + " 的 methods 里的「" + known.replace(m) + "」不是支持的方法（" + String.join("、", METHODS.stream().sorted().toList()) + "）");
                else if (!upper.contains(u)) upper.add(u);
            }
            methods = upper;
        }
        List<String> paths = optionalList(node.get("paths"), name, "paths", errors);
        if (paths != null) {
            if (paths.size() > MAX_PATHS) {
                errors.add("key " + name + " 的 paths 最多 " + MAX_PATHS + " 条");
            } else {
                for (String p : paths) {
                    if (p.length() > MAX_PATH_LENGTH) {
                        errors.add("key " + name + " 的 paths 里有一条超过 " + MAX_PATH_LENGTH + " 个字符");
                        continue;
                    }
                    try {
                        PathPattern.parse(p);
                    } catch (IllegalArgumentException ex) {
                        errors.add("key " + name + " 的 paths 里的「" + known.replace(p) + "」不合法：" + ex.getMessage());
                    }
                }
            }
        }
        if (errors.size() > before) return null;
        return new Entry(rawName, value, List.copyOf(hosts), methods == null ? null : List.copyOf(methods),
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

    /**
     * 值与声明的交叉检查（文案只点名 key 和位置，不带值）：
     * <ul>
     *   <li>两个 key 的值相同：文件里的原文没法判断该换成哪一个占位符；</li>
     *   <li>任何一个值作为子串出现在任何 key 的 hosts / methods / paths 里：声明会原样存进 manifest（给人和模型看），
     *       以 secret 当路径段或查询参数的接口（webhook）很常见，作者很自然会把整条带 token 的路径写进 paths。
     *       hosts 按小写比较（库里的域名一律是小写）。</li>
     * </ul>
     * {@code values} 里可以含有沿用上一版、解密出来的值：发布时同一个检查对它们再做一遍。
     */
    public static List<String> crossCheck(List<Entry> entries, SecretValues values) {
        List<String> errors = new ArrayList<>();
        for (String[] pair : values.duplicatePairs()) {
            errors.add("key " + pair[0] + " 和 key " + pair[1] + " 的值相同：每个 key 要有自己的值，否则文件里的原文没法判断该换成哪一个占位符");
        }
        for (Entry e : entries) {
            scanDeclaration(e.name(), "hosts", e.hosts(), true, values, errors);
            scanDeclaration(e.name(), "methods", e.methods(), false, values, errors);
            scanDeclaration(e.name(), "paths", e.paths(), false, values, errors);
        }
        return errors;
    }

    private static void scanDeclaration(String owner, String field, List<String> items, boolean ignoreCase,
                                        SecretValues values, List<String> errors) {
        if (items == null) return;
        Set<String> reported = new LinkedHashSet<>();
        for (String item : items) {
            reported.addAll(ignoreCase ? values.namesInIgnoreCase(item) : values.namesIn(item));
        }
        for (String x : reported) {
            String where = x.equals(owner) ? "它自己的 " + field : " key " + owner + " 的 " + field;   // 中文和 key 名之间的空格跟着 key 名走
            errors.add("key " + x + " 的值出现在了" + where + " 里：" + switch (field) {
                case "paths" -> "路径里用 * 通配，请求时在 URL 里写 {{" + x + "}}";
                case "hosts" -> "hosts 里只能写域名，key 的值不能放在这里";
                default -> "methods 里只能写 GET、POST 这类方法名";
            });
        }
    }

    /**
     * 草稿预览里的 secrets.json：每个 value（以及直接写成字符串的值、不认识的字段里的字符串和数字）换成 {@value #MASK}，
     * hosts / methods / paths 原样给（里面万一有值，先抹掉）。读不出来（不是合法 JSON、不是对象）返回 null：
     * 不知道哪些是值，就一个字都不给。工作区里的文件本身不动。
     */
    public static String maskedPreview(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        SecretValues known = valuesIn(root);
        ObjectNode out = JSON.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode n = f.getValue();
            String key = known.replace(f.getKey());
            if (!n.isObject()) {
                out.set(key, maskLeaves(n));
                continue;
            }
            ObjectNode o = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> fi = n.fields(); fi.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fi.next();
                o.set(known.replace(field.getKey()),
                        DECLARATION_FIELDS.contains(field.getKey()) ? scrubStrings(field.getValue(), known) : maskLeaves(field.getValue()));
            }
            out.set(key, o);
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static JsonNode maskLeaves(JsonNode n) {
        if (n.isTextual() || n.isNumber()) return TextNode.valueOf(MASK);
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(maskLeaves(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            n.fields().forEachRemaining(f -> o.set(f.getKey(), maskLeaves(f.getValue())));
            return o;
        }
        return n;
    }

    private static JsonNode scrubStrings(JsonNode n, SecretValues known) {
        if (n.isTextual()) return TextNode.valueOf(known.replace(n.asText()));
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(scrubStrings(x, known)));
            return a;
        }
        return n.isObject() ? maskLeaves(n) : n;
    }

    /**
     * 不带值的 secrets.json：只有 key 的名字和发送范围。「用 AI 改进」时放进工作区，发布后也用它把工作区里带值的那份改写掉。
     * 不限的 methods / paths 直接不写这个字段（写成 [] 会被 {@link #parse} 拒绝）；没有 value 字段 = 沿用上一版的值。
     */
    public static String valueLessJson(List<SkillManifest.KeyDecl> decls) {
        ObjectNode root = JSON.createObjectNode();
        for (SkillManifest.KeyDecl d : decls) {
            ObjectNode o = root.putObject(d.name());
            ArrayNode hosts = o.putArray("hosts");
            d.hosts().forEach(hosts::add);
            if (d.methods() != null) {
                ArrayNode m = o.putArray("methods");
                d.methods().forEach(m::add);
            }
            if (d.paths() != null) {
                ArrayNode p = o.putArray("paths");
                d.paths().forEach(p::add);
            }
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("secrets.json 序列化失败", e);
        }
    }
}

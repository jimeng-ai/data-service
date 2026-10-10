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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 *
 * <p><b>值集合要能确定，抹值的成本才有上限。</b>「候选值」是 value、直接写成字符串的值、不认识的字段里的字符串和数字
 * （短于 {@value #MIN_VALUE_LENGTH} 个字符的不算）。全部满足才算「能确定」：文件不超过 {@value #MAX_FILE_BYTES} 字节（先看大小，
 * 超了不读）、JSON 读得出来且嵌套不超过 {@value #MAX_DEPTH} 层、最多 {@value #MAX_KEYS} 个 key、候选值最多 {@value #MAX_CANDIDATES} 个、
 * 每个候选值最多 {@value #MAX_VALUE_LENGTH} 个字符。任何一条不满足就按「读不出来」处理（{@link Parsed#readable()} 为 false）：只给一句固定的话，
 * 不回显任何条目、不往下逐条校验；调用方对外一律不给 skill 文件的原文（预览、事件、校验文本），不同步 DRAFT 行。
 *
 * <p><b>key 名里不能出现任何已知的值</b>（{@link #keyNameProblem}）：名字会进 manifest、{@code skill_secret.name}、日志和占位符，
 * 值的形状符合名字规则时（AWS 的 access key id）能原样写成名字。查到了同样按「读不出来」处理，只给一句不带名字的话。
 */
public final class SkillSecretsFile {

    public static final String FILE_NAME = SkillBundleRules.SECRETS_JSON;
    public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 太短的值没法在回包里安全地抹掉（会把正常文字切碎）。代理和连接器工具对 token 用的也是 8。短于它的字符串不是 key 的值：校验会拒，任何地方都不拿它抹值。 */
    public static final int MIN_VALUE_LENGTH = SecretValues.MIN_VALUE_LENGTH;
    /** 太长的不是 key（是把整份证书、整个文件贴进来了）；每次发布还要在每个文件里找它，加密后也要塞得进 TEXT 列。 */
    public static final int MAX_VALUE_LENGTH = 8192;
    public static final int MAX_KEYS = 32;
    /** secrets.json 本身最大多大。与草稿预览单个文件的上限（skill.builder.preview-file-max-bytes，默认 512 KB）是同一个数。 */
    public static final int MAX_FILE_BYTES = 512 * 1024;
    /** 候选值（8 个字符以上的字符串和数字）最多几个。 */
    public static final int MAX_CANDIDATES = 64;
    /** JSON 嵌套最多几层（根对象算第 1 层）。正常的 key 文件是 3 层；限住它，预览里遍历结构的递归才有上界。 */
    public static final int MAX_DEPTH = 8;
    /** hosts / paths 的个数和长度也有上限：网关每个请求都要遍历它们。 */
    public static final int MAX_HOSTS = 32;
    public static final int MAX_PATHS = 64;
    public static final int MAX_PATH_LENGTH = 512;
    public static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    /** 草稿预览里值被换成的样子。 */
    public static final String MASK = "******";
    private static final Set<String> FIELDS = Set.of("value", "hosts", "methods", "paths");
    private static final Set<String> DECLARATION_FIELDS = Set.of("hosts", "methods", "paths");
    private static final String TOO_DEEP = "secrets.json 嵌套得太深，不是合法的 key 文件";
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
     * @param values   文件里的候选值（value、直接写成字符串的值、不认识的字段里的字符串和数字；8 个字符以上的，不论那一条是否合规）：
     *                 报错抹值、草稿预览、DRAFT 同步都用它
     * @param readable 值集合能确定（见类说明：大小、JSON、key 数、候选值个数和长度、嵌套层数都在上限内）。false 时不知道有哪些值，
     *                 {@code errors} 只有一句固定的话；调用方对外就只能给这句话，不给 skill 文件的原文，不同步 DRAFT 行
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
        return parse(json, SecretValues.NONE);
    }

    /**
     * @param alsoKnown 发布时上一版所有 key 的值：回显作者写的条目（hosts / paths / 方法 / 字段名 / key 名）时也要抹，
     *                  不论这一条合不合规、这一条写没写 value——旧值最常见的出口就是模型照别家写法把它拼进了 paths
     */
    public static Parsed parse(byte[] json, SecretValues alsoKnown) {
        Analysis a = analyze(json);
        if (a.problem() != null) return unreadable(a.problem());
        JsonNode root = a.root();
        SecretValues known = a.values();
        List<String> errors = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        SecretValues scrub = known.plus(alsoKnown);   // 回显用：文件里的值 + 上一版的值
        // ★ 名字里有值：后面所有会打出 key 名、{{名字}} 的地方（逐条报错、占位符、日志）都会把值打出来，所以最先查，
        // 查到了就只给一句不带名字的话，不往下逐条校验
        String nameProblem = keyNameProblem(root, scrub, alsoKnown);
        if (nameProblem != null) return unreadable(nameProblem);
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            Entry e = parseEntry(f.getKey(), f.getValue(), errors, scrub);
            if (e != null) entries.add(e);
        }
        if (errors.isEmpty()) errors.addAll(crossCheck(entries, known));   // 格式都对了，再看值有没有跑进声明里、有没有两个 key 共用一个值
        return new Parsed(List.copyOf(entries), List.copyOf(errors), known, true);
    }

    /**
     * key 的名字里含有任何已知的值（当前的 + 上一版的；区分大小写，子串）。值的形状正好符合 {@code [A-Z][A-Z0-9_]{0,63}} 时
     * （比如 AWS 的 access key id）能原样写成 key 名，名字会进 manifest 的 key 声明、{@code skill_secret.name}、日志，
     * 替换时的占位符 {@code {{名字}}} 也会把它打出来。文案<b>不回显名字</b>（名字本身就是值），只说「第 N 个 key 的名字」。
     *
     * @param alsoKnown 上一版的值：它们的名字（上一版 key 的名字）同样要查——名字会出现在残留检查的文案和占位符里
     * @return 一句固定的话，没有问题就是 null
     */
    private static String keyNameProblem(JsonNode root, SecretValues scrub, SecretValues alsoKnown) {
        List<Integer> positions = new ArrayList<>();
        int n = 0;
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            n++;
            if (!scrub.namesIn(it.next()).isEmpty()) positions.add(n);
        }
        boolean previous = alsoKnown.names().stream().anyMatch(name -> !scrub.namesIn(name).isEmpty());
        if (positions.isEmpty() && !previous) return null;
        StringBuilder sb = new StringBuilder();
        if (!positions.isEmpty()) {
            sb.append("secrets.json 里第 ").append(String.join("、", positions.stream().map(String::valueOf).toList()))
                    .append(" 个 key 的名字里含有 key 的值：key 的名字不能是值本身，也不能把值写进名字里，请换个名字");
        }
        if (previous) {
            if (sb.length() > 0) sb.append("。");
            sb.append("上一版有 key 的名字里含有这次 key 的值：请换一个值，或者联系管理员");
        }
        return sb.toString();
    }

    /** secrets.json 太大的固定文案（不带内容）。读取方（草稿预览）先看对象大小，超了不读，用同一句话。 */
    public static String tooLargeMessage(long limitBytes) {
        return "secrets.json 超过 " + limitBytes / 1024 + " KB：key 文件不会这么大，请检查是不是把别的内容放进来了";
    }

    /** 分析结果：{@code problem != null} 就是值集合确定不了，对外只给这一句固定的话。 */
    private record Analysis(JsonNode root, SecretValues values, String problem) {
        static Analysis problem(String text) {
            return new Analysis(null, SecretValues.NONE, text);
        }
    }

    /** 遍历用的栈帧：{@code depth} 是这个节点所在的嵌套层数（根对象 1），{@code candidate} 为 false 的子树只数层数、不取值。 */
    private record Frame(String name, JsonNode node, int depth, boolean candidate) {}

    private static Analysis analyze(byte[] json) {
        if (json.length > MAX_FILE_BYTES) {
            return Analysis.problem(tooLargeMessage(MAX_FILE_BYTES));
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return Analysis.problem(describe(e));
        } catch (IOException e) {
            return Analysis.problem("secrets.json 不是合法的 JSON");
        } catch (StackOverflowError e) {
            return Analysis.problem(TOO_DEEP);
        }
        if (root == null || !root.isObject()) {
            return Analysis.problem("secrets.json 必须是一个 JSON 对象：键是 key 的名字，值是 {value, hosts, methods, paths}");
        }
        if (root.size() > MAX_KEYS) {
            return Analysis.problem("secrets.json 最多 " + MAX_KEYS + " 个 key");
        }
        List<SecretValues.Item> candidates = new ArrayList<>();
        String problem = collectCandidates(root, candidates);
        return problem != null ? Analysis.problem(problem) : new Analysis(root, SecretValues.of(candidates), null);
    }

    /**
     * 候选值：value、直接写成字符串（或别的）的值、不认识的字段里的字符串和数字；hosts / methods / paths 里的是声明，不算。
     * 短于 {@value #MIN_VALUE_LENGTH} 个字符的不算。用显式的栈遍历，不递归（嵌套很深的文件不能让线程栈溢出），
     * 同时数嵌套层数：超过 {@value #MAX_DEPTH} 层就是读不出来，后面预览里遍历结构的递归因此有上界。
     *
     * @return 越界的话是一句固定的话（不带内容），否则 null
     */
    private static String collectCandidates(JsonNode root, List<SecretValues.Item> out) {
        Deque<Frame> stack = new ArrayDeque<>();
        List<Frame> children = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            children.add(new Frame(f.getKey(), f.getValue(), 2, true));
        }
        pushInOrder(stack, children);
        while (!stack.isEmpty()) {
            Frame fr = stack.pop();
            JsonNode n = fr.node();
            if (n.isContainerNode()) {
                if (fr.depth() > MAX_DEPTH) return TOO_DEEP;
                children.clear();
                if (n.isObject()) {
                    boolean entryObject = fr.depth() == 2;   // key 条目本身：它的 hosts / methods / paths 是声明
                    for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> field = it.next();
                        boolean declaration = entryObject && DECLARATION_FIELDS.contains(field.getKey());
                        children.add(new Frame(fr.name(), field.getValue(), fr.depth() + 1, fr.candidate() && !declaration));
                    }
                } else {
                    for (JsonNode child : n) children.add(new Frame(fr.name(), child, fr.depth() + 1, fr.candidate()));
                }
                pushInOrder(stack, children);
            } else if (fr.candidate() && (n.isTextual() || n.isNumber())) {
                String text = n.asText();
                if (text.codePointCount(0, text.length()) < MIN_VALUE_LENGTH) continue;
                if (text.length() > MAX_VALUE_LENGTH) return "secrets.json 里有超过 " + MAX_VALUE_LENGTH + " 个字符的值";
                out.add(new SecretValues.Item(fr.name(), text));
                if (out.size() > MAX_CANDIDATES) {
                    return "secrets.json 里 " + MIN_VALUE_LENGTH + " 个字符以上的字符串和数字超过了 " + MAX_CANDIDATES + " 个：key 文件里不该有这么多内容";
                }
            }
        }
        return null;
    }

    /** 倒着压栈，这样弹出来的顺序就是文件里的顺序（重名提示、key 名的先后因此稳定）。 */
    private static void pushInOrder(Deque<Frame> stack, List<Frame> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) stack.push(frames.get(i));
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
            scanDeclaration(e.name(), "hosts", e.hosts(), true, values, "的值", errors);
            scanDeclaration(e.name(), "methods", e.methods(), false, values, "的值", errors);
            scanDeclaration(e.name(), "paths", e.paths(), false, values, "的值", errors);
        }
        return errors;
    }

    /**
     * 残留检查的声明这一半：上一版的值（这一版没用 {@code {{名字}}} 替换掉的那些）不能出现在任何 key 的 hosts / methods / paths 里。
     * 文案点名 key 和位置，不带值。
     */
    public static List<String> residualCheck(List<Entry> entries, SecretValues residual) {
        List<String> errors = new ArrayList<>();
        for (Entry e : entries) {
            scanDeclaration(e.name(), "hosts", e.hosts(), true, residual, "上一版的值", errors);
            scanDeclaration(e.name(), "methods", e.methods(), false, residual, "上一版的值", errors);
            scanDeclaration(e.name(), "paths", e.paths(), false, residual, "上一版的值", errors);
        }
        return errors;
    }

    private static void scanDeclaration(String owner, String field, List<String> items, boolean ignoreCase,
                                        SecretValues values, String whose, List<String> errors) {
        if (items == null) return;
        Set<String> reported = new LinkedHashSet<>();
        for (String item : items) {
            reported.addAll(ignoreCase ? values.namesInIgnoreCase(item) : values.namesIn(item));
        }
        for (String x : reported) {
            String where = x.equals(owner) ? "它自己的 " + field : " key " + owner + " 的 " + field;   // 中文和 key 名之间的空格跟着 key 名走
            errors.add("key " + x + (whose.startsWith("上一版") ? " 上一版的值" : " 的值") + "出现在了" + where + " 里：" + switch (field) {
                case "paths" -> "路径里用 * 通配，请求时在 URL 里写 {{" + x + "}}";
                case "hosts" -> "hosts 里只能写域名，key 的值不能放在这里";
                default -> "methods 里只能写 GET、POST 这类方法名";
            });
        }
    }

    /**
     * 草稿预览里的 secrets.json：每个 value（以及直接写成字符串的值、不认识的字段里的字符串和数字）换成 {@value #MASK}，
     * hosts / methods / paths 原样给（里面万一有值，先抹掉）。读不出来（值集合确定不了，见类说明）返回 null：
     * 不知道哪些是值，就一个字都不给。工作区里的文件本身不动。
     */
    public static String maskedPreview(byte[] json) {
        Analysis a = analyze(json);
        if (a.problem() != null) return null;
        JsonNode root = a.root();
        SecretValues known = a.values();
        if (keyNameProblem(root, known, SecretValues.NONE) != null) return null;   // 预览里的 key 名和占位符会把名字（= 值）打出来
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

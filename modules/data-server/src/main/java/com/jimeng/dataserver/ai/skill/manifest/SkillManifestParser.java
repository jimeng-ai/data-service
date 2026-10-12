package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.dataserver.ai.http.policy.HostPattern;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SKILL.md 的唯一解析器（设计文档 §3.6）。所有入口都走这里，没有第二套：
 * <ul>
 *   <li>{@link Mode#STRICT}：构建器发布、平台 skill 的出厂版本、平台上传。规则与 skill-creator 的
 *       {@code scripts/quick_validate.py} <b>逐条一致，以下已知差异除外</b>（对拍用例：test resources 的
 *       {@code skill-manifest/quick-validate-cases.json}；对拍用例里只允许「平台规则」这一种差异，下面前四条不在其中），
 *       另加平台自己的三条：name、description 不能为空，metadata 必须是字典。已知差异：
 *       <ul>
 *         <li>闭合行 {@code ---junk}、{@code ----}：quick_validate.py 放过，这里要求 {@code ---} 后面只能是空白再换行
 *             （与原来的旧解析器是同一个正则）。</li>
 *         <li>{@code name: 1e3} 这类「数字 e 数字」：PyYAML 当字符串，SnakeYAML 当浮点数，这里报「name 必须是字符串」。</li>
 *         <li>name 末尾的 U+00A0（不间断空格）：Python 的 {@code strip()} 会去掉，Java 的不会，这里报 kebab-case。</li>
 *         <li>开头的 BOM：quick_validate.py 拒绝，这里接受（Windows 上编辑过的文件，设计如此，两种模式都接受）。</li>
 *         <li>纯空白的 name / description：quick_validate.py 的 {@code strip()} 之后为空就整段跳过检查，这里是平台规则
 *             「不能为空」（对拍用例 name-blank、description-blank 钉着）。</li>
 *         <li>metadata 不是字典（字符串、块标量、列表……）：quick_validate.py 不检查 metadata 的形状，这里是平台规则，
 *             <b>两种模式都报错</b>（对拍用例 metadata-not-a-map、metadata-quoted-flow-map、metadata-block-scalar 钉着）；
 *             {@code metadata:} 后面什么都没写（null）和 {@code metadata: {}} 不算错。</li>
 *       </ul></li>
 *   <li>{@link Mode#LENIENT}：GitHub 导入、对话里的 skill_install、单个 SKILL.md 上传。不认识的顶层键忽略并记日志；
 *       名字沿用上传 / 导入一直以来接受的形状，收紧会让以前能导入的 skill 导不进来。
 *       <b>frontmatter 不是合法 YAML 时，按 Claude Code 加载器的规则给「值里有特殊字符」的行补上引号，再解析一次</b>
 *       （见 {@link #quoteSpecialValues}，其中 {@code metadata} 行是唯一不补引号的）：LENIENT 接收的是别处写出来的
 *       skill，它拒绝的集合原则上不该比 Claude Code 自己能加载的更大（比如 {@code description: Use when: x}）。
 *       <b>有三个有意的例外</b>，理由相同：平台只从 {@code metadata.jm} 读声明，写错了位置，声明就会静默丢失，
 *       而 Claude Code 不读 metadata、会忽略不认识的顶层键，照样能加载这样的文件。
 *       <ol>
 *         <li>metadata 那一行自己不是合法 YAML（比如单行 flow 没闭合）：这里拒绝，不会替作者补引号把它吞成字符串。</li>
 *         <li>metadata 不是字典：这里拒绝（上面第三条平台规则）。</li>
 *         <li>顶层出现 {@code jm} 键（不管值是什么）：这里拒绝，提示要写到 metadata 下面（R12）。<b>只拦 jm</b>：
 *             它是平台自己的命名空间，不会撞上第三方 skill 的键，几乎没有误伤，也是最可能的写错方式——抄 {@code jm:}
 *             那一段时漏了外面的 {@code metadata:}。顶层的 hosts / web / timeout-seconds / requires / binding，
 *             以及 {@code metadata.hosts}（漏写 jm）<b>不拦</b>：这几个通用词会撞上第三方 skill 里同名的键，
 *             宽松模式照旧把顶层的当作被忽略的键（记进 {@code ignoredKeys}）；严格模式本来就拒绝顶层的这些键。</li>
 *       </ol>
 *       STRICT 不重试，继续和 quick_validate.py 对拍。</li>
 * </ul>
 *
 * <p>{@code metadata} 本身必须是字典（null 和空字典除外），{@code metadata.jm} 存在就必须是字典，里面在两种模式下都
 * 严格校验：那是平台自己的命名空间，写错一个键名（比如把 timeout-seconds 写成 timeout）、或者 metadata 写成了字符串，
 * 若被静默忽略，表现就是「声明了却不生效」，没有任何信号。
 *
 * <p>YAML 用 SnakeYAML 的 SafeConstructor（YAML 1.1，与 quick_validate.py 用的 PyYAML safe_load 同一个口径：
 * {@code yes} 是布尔、{@code 2024-01-01} 是日期，都不是字符串）。
 *
 * <p>★ {@link #parse} 本身不抛异常，也不构造 {@code ServiceException}：那个异常一构造就 {@code log.error}，
 * 草稿预览会反复走解析，每次写错都刷一条 ERROR 告警。只有 {@link ParsedSkill#orThrow()} 才抛。
 */
@Slf4j
public final class SkillManifestParser {

    public enum Mode { STRICT, LENIENT }

    public static final Set<String> ALLOWED_KEYS =
            Set.of("name", "description", "license", "allowed-tools", "metadata", "compatibility");
    /** 报错文案里按这个顺序列出。 */
    public static final List<String> JM_KEYS = List.of("hosts", "web", "timeout-seconds", "requires", "binding");
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int MAX_TIMEOUT_SECONDS = 180;
    public static final int MAX_HOSTS = 50;

    /**
     * 文件开头的 BOM。不写成字面字符或 unicode 转义：编辑器、格式化工具、写文件的工具链都可能把它吃掉，
     * 或把转义还原成一个看不见的字符。
     */
    private static final char BOM = (char) 0xFEFF;

    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
    private static final Pattern KEBAB = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern LENIENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** 补引号规则用：一行 {@code key: value}，用 matches() 整行匹配。 */
    private static final Pattern KEY_VALUE_LINE = Pattern.compile("([a-zA-Z_-]+):\\s+(.+)");
    /** 补引号规则用：值里出现这些 YAML 特殊字符之一，或「冒号 + 空格」，就要把整个值括起来；用 find()。 */
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[{}\\[\\]*&#!|>%@`]|: ");

    private static final String QUOTE_HINT = "（值里有冒号等符号时，用引号把整个值括起来）";

    /** 平台自己的命名空间：声明只从 metadata.jm 读。 */
    private static final String JM = "jm";
    /**
     * 顶层写了 jm 时的改法。最可能的写错方式是抄 jm: 那一段时漏了外面的 metadata:。
     * 严格模式附在「不允许的键」后面，宽松模式单独作为一条错误。
     */
    private static final String TOP_LEVEL_JM_HINT = "jm 要写在 metadata 下面（metadata: 的下一行缩进写 jm: …），写在顶层不会生效";

    private SkillManifestParser() {}

    public static ParsedSkill parse(String content, Mode mode) {
        return parse(content, mode, false);
    }

    /** 发布处理器按当前/历史 key 声明要求原始 source 完整覆盖；普通草稿/无 key 解析保留宽松恢复。 */
    static ParsedSkill parse(String content, Mode mode, boolean requireCompleteSource) {
        List<String> errors = new ArrayList<>();
        String text = content == null ? "" : content;
        if (!text.isEmpty() && text.charAt(0) == BOM) text = text.substring(1);
        if (!text.startsWith("---")) {
            errors.add("SKILL.md 缺少 YAML frontmatter（第一行必须是 ---）");
            return ParsedSkill.failed(text, errors);
        }
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.find()) {
            errors.add("frontmatter 格式不对：找不到结尾的 ---");
            return ParsedSkill.failed(text, errors);
        }
        String body = text.substring(m.end()).replaceFirst("\\A(\\r?\\n)+", "");
        String frontmatter = m.group(1);
        Object loaded;
        List<ParsedSkill.Decoded> decoded = new ArrayList<>();
        boolean requoted = false;
        try {
            SourceYaml.Loaded source = SourceYaml.load(frontmatter);
            loaded = source.value();
            decoded.addAll(source.decoded());
        } catch (SourceYaml.Limit exceeded) {
            return ParsedSkill.failed(body, List.of("frontmatter 不是合法的 YAML：超过安全解析预算"));
        } catch (Exception first) {
            // 补引号规则会不会改动这份 frontmatter：会，重试才有意义，「用引号括起来」的提示也才不是误导
            String quoted = quoteSpecialValues(frontmatter);
            boolean wouldChange = !quoted.equals(frontmatter.replace("\r\n", "\n"));
            loaded = null;
            if (mode == Mode.LENIENT && wouldChange) {
                try {
                    decoded.addAll(SourceYaml.beforeRetry(frontmatter, quoted, requireCompleteSource));
                    SourceYaml.Loaded source = SourceYaml.load(quoted);
                    loaded = source.value();
                    decoded.addAll(source.decoded());
                    requoted = true;
                } catch (SourceYaml.Limit exceeded) {
                    return ParsedSkill.failed(body, List.of("frontmatter 不是合法的 YAML：超过安全解析预算"));
                } catch (Exception second) {
                    // 补了引号也救不回来：照旧拒绝，只返回不包含源文本的语法提示
                }
            }
            if (!requoted) {
                errors.add("frontmatter 不是合法的 YAML：" + "请检查语法和缩进" + (wouldChange ? QUOTE_HINT : ""));
                return ParsedSkill.failed(body, errors);
            }
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            errors.add("frontmatter 必须是 YAML 字典");
            return ParsedSkill.failed(body, errors);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        raw.forEach((k, v) -> fields.put(String.valueOf(k), v));
        if (requoted) {
            // 不带 skill 名：名字是 YAML 解码出来的，这时还没有核对过它——可能是用转义拼出来的 key 值（R34）
            log.info("SKILL.md 宽松解析：frontmatter 不是合法 YAML，已按 Claude Code 的规则给特殊值补引号后解析");
        }

        List<String> unexpected = fields.keySet().stream().filter(k -> !ALLOWED_KEYS.contains(k)).sorted().toList();
        boolean topLevelJm = unexpected.contains(JM);
        List<String> ignored = unexpected;
        if (!unexpected.isEmpty()) {
            if (mode == Mode.STRICT) {
                errors.add("frontmatter 里有不允许的键：" + String.join(", ", unexpected)
                        + "（只允许 " + String.join(", ", ALLOWED_KEYS.stream().sorted().toList()) + "）"
                        + (topLevelJm ? "；" + TOP_LEVEL_JM_HINT : ""));
            } else {
                // 顶层的 jm 是宽松模式「忽略不认识的顶层键」的例外（R12）：被拒绝，不算被忽略。只拦它：理由见类注释。
                if (topLevelJm) {
                    errors.add(TOP_LEVEL_JM_HINT);
                    ignored = unexpected.stream().filter(k -> !JM.equals(k)).toList();
                }
                // 只记个数：键名也是 YAML 解码出来的，这时还没有核对过（R34）
                if (!ignored.isEmpty()) log.info("SKILL.md 宽松解析：忽略了 {} 个不认识的顶层键", ignored.size());
            }
        }

        String name = parseName(fields.get("name"), mode, errors);
        String description = parseDescription(fields.get("description"), mode, errors);
        checkCompatibility(fields.get("compatibility"), errors);
        JmMetadata jm = parseJm(fields.get("metadata"), errors);
        if (mode == Mode.LENIENT && body.isBlank()) {
            errors.add("SKILL.md 正文不能为空");
        }
        return new ParsedSkill(name, description, body, jm, mode == Mode.LENIENT ? ignored : List.of(), errors, decoded);
    }

    private static Object loadYaml(String text) {
        return SourceYaml.load(text).value();
    }

    /**
     * 把 frontmatter 里「键: 值」行中带 YAML 特殊字符的值整个加上双引号。宽松模式在 YAML 解析失败后用它重试一次；
     * 严格模式不重试，只用它判断提示要不要附（补引号会不会改动文本）。
     *
     * <p>逐条照搬 Claude Code 2.1.158 加载 SKILL.md 时的同名处理，只有第 5 条是我们加的：
     * <ol>
     *   <li>只看形如 {@code key: value} 的行：整行匹配 {@code ([a-zA-Z_-]+):\s+(.+)}。缩进的行、没有值的行、
     *       键里带数字的行、注释行都原样放过。</li>
     *   <li>值已经被双引号或单引号包住，原样放过。</li>
     *   <li>值以 {@code [} 开头、{@code ]} 结尾，且本身能被同一个 YAML 加载成序列，原样放过。</li>
     *   <li>值里有「冒号 + 空格」，或有 {@code {}[]*&#!|>%@} 和反引号之一：先把反斜杠换成两个反斜杠、双引号换成反斜杠加双引号，
     *       整行改写成 {@code key: "转义后的值"}（冒号后的多个空白归一成一个空格）；否则原样放过。</li>
     *   <li><b>相对 Claude Code 的唯一偏离</b>（在上面各条之前判断）：键恰好是 {@code metadata} 的行原样放过，不补引号。
     *       Claude Code 不读 metadata，所以这不影响它能加载什么；我们要读 {@code metadata.jm}。单行写法
     *       {@code metadata: {jm: {hosts: [api.example.com]}}} 的值含 {@code {}}，补引号会把整个 metadata 变成一个字符串，
     *       而 metadata 不是字典会被 {@link #parse} 直接拒绝（R11）——一份本来合法的文件，只因为别处有一行要补引号就被误拒。
     *       （R11 之前后果更糟：字符串 metadata 被当作没有 jm，声明的 hosts / web / timeout-seconds 悄悄退回默认。）块风格的
     *       {@code metadata:  # 注释} 同理：行尾的 {@code #} 会让它被改写，下面缩进的 jm 整段随之解析失败。
     *       跳过之后，metadata 自己写坏时第二次解析照样失败，照常拒绝。<b>这是有意的例外</b>：Claude Code 能加载这样的文件
     *       （它给这行补引号，又不读 metadata），这里拒绝——别按类注释里「不比 Claude Code 更严」的原则把它改回去。</li>
     * </ol>
     * 行数不变，所以第二次解析失败时报的行号仍然对得上作者的文件。CRLF 先当 LF 处理。
     *
     * <p>和 Claude Code 不同的几处：(1) 它在两次都失败时把 frontmatter 当空的照样加载，这里不照搬，
     * 平台必须有 name 和 description；(2) Java 正则的空白类只含 ASCII 空白，JS 的还含 U+00A0 等 Unicode 空白；
     * (3) Java 正则的 {@code .} 不匹配 U+0085，JS 的匹配。(2)(3) 两个差异接受：碰到的行只是不被补引号，
     * 和改动前一样被拒绝。键名大小写敏感：{@code Name:} 不是 name（Claude Code 也不认）。
     */
    static String quoteSpecialValues(String frontmatter) {
        String[] lines = frontmatter.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = quoteLine(lines[i]);
        }
        return String.join("\n", lines);
    }

    private static String quoteLine(String line) {
        Matcher kv = KEY_VALUE_LINE.matcher(line);
        if (!kv.matches()) return line;
        String key = kv.group(1);
        String value = kv.group(2);
        // 相对 Claude Code 的唯一偏离，理由见 quoteSpecialValues 的注释第 5 条
        if ("metadata".equals(key)) return line;
        boolean alreadyQuoted = (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
        if (alreadyQuoted) return line;
        if (value.startsWith("[") && value.endsWith("]") && isYamlSequence(value)) return line;
        if (!NEEDS_QUOTES.matcher(value).find()) return line;
        return key + ": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isYamlSequence(String value) {
        try {
            return loadYaml(value) instanceof List;
        } catch (Exception e) {
            return false;
        }
    }

    private static String parseName(Object n, Mode mode, List<String> errors) {
        if (n == null) {
            errors.add("frontmatter 缺少 name");
            return null;
        }
        if (!(n instanceof String s)) {
            errors.add("name 必须是字符串");
            return null;
        }
        String name = s.strip();
        if (name.isEmpty()) {
            errors.add("name 不能为空");
            return name;
        }
        if (mode == Mode.STRICT) {
            if (!KEBAB.matcher(name).matches()) {
                errors.add("name「" + name + "」必须是 kebab-case（小写字母、数字、连字符）");
            } else if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
                errors.add("name「" + name + "」不能以连字符开头或结尾，也不能有连续连字符");
            }
            if (name.length() > 64) errors.add("name 太长（" + name.length() + " 字符），最多 64");
        } else if (!LENIENT_NAME.matcher(name).matches()) {
            errors.add("skill 名称只能含字母、数字、下划线、中划线，且以字母开头，长度不超过 64");
        }
        return name;
    }

    private static String parseDescription(Object d, Mode mode, List<String> errors) {
        if (d == null) {
            errors.add("frontmatter 缺少 description");
            return null;
        }
        if (!(d instanceof String s)) {
            errors.add("description 必须是字符串");
            return null;
        }
        String description = s.strip();
        if (description.isEmpty()) {
            errors.add("description 不能为空");
            return description;
        }
        if (mode == Mode.STRICT && (description.contains("<") || description.contains(">"))) {
            errors.add("description 不能包含尖括号（< 或 >）");
        }
        // 按码点计：与 quick_validate.py 的 len()、MySQL VARCHAR(1024) 的字符数同一口径（emoji 不按 2 算）
        int len = description.codePointCount(0, description.length());
        if (len > 1024) errors.add("description 太长（" + len + " 字符），最多 1024");
        return description;
    }

    /** quick_validate.py 写的是 {@code if compatibility:}：0、false、空串、空列表都按「没写」处理，这里照抄 Python 的真值规则。 */
    private static void checkCompatibility(Object c, List<String> errors) {
        if (isFalsy(c)) return;
        if (!(c instanceof String s)) {
            errors.add("compatibility 必须是字符串");
        } else if (s.codePointCount(0, s.length()) > 500) {
            errors.add("compatibility 太长（" + s.codePointCount(0, s.length()) + " 字符），最多 500");
        }
    }

    private static JmMetadata parseJm(Object metadata, List<String> errors) {
        // metadata: 后面什么都没写（YAML null）= 没写任何声明，没有什么会丢，不算错。
        if (metadata == null) return JmMetadata.EMPTY;
        // 平台规则（两种模式都查）：metadata 是平台唯一按结构读的顶层键。quick_validate.py 不检查它的形状，
        // 但它不是字典（作者把单行写法整个加了引号、写成块标量……）时，写在 metadata.jm 下的声明会被静默当作「没有」，
        // 网关运行时才拒绝，作者查不出原因。Agent Skills 规范本来就要求 metadata 是字典。
        if (!(metadata instanceof Map<?, ?> md)) {
            errors.add("metadata 必须是字典（键: 值），现在是" + typeName(metadata)
                    + "；写在 metadata.jm 下的声明不会生效，请改成多行缩进写法");
            return JmMetadata.EMPTY;
        }
        if (!md.containsKey(JM)) return JmMetadata.EMPTY;
        // jm 存在就必须是字典，包括 jm: 后面空着——那多半是 hosts 等子项忘了缩进（写成了 jm 的兄弟），空着放过会丢声明。
        if (!(md.get(JM) instanceof Map<?, ?> jm)) {
            errors.add("metadata.jm 必须是字典");
            return JmMetadata.EMPTY;
        }
        List<String> unknown = jm.keySet().stream().map(String::valueOf).filter(k -> !JM_KEYS.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            errors.add("metadata.jm 里有不认识的键：" + String.join(", ", unknown) + "（只认 " + String.join("、", JM_KEYS) + "）");
        }
        return new JmMetadata(
                parseHosts(jm.get("hosts"), errors),
                parseWeb(jm.get("web"), errors),
                parseTimeout(jm.get("timeout-seconds"), errors),
                parseRequires(jm.get("requires"), errors),
                parseBinding(jm.get("binding"), errors));
    }

    /** 报错里说「现在是什么」：只区分作者看得懂的几类，其余（!!binary、!!set 之类）统称其他类型。 */
    private static String typeName(Object v) {
        if (v instanceof String) return "字符串";
        if (v instanceof Boolean) return "布尔值";
        if (v instanceof Number) return "数字";
        if (v instanceof List<?>) return "列表";
        if (v instanceof Date) return "日期";
        return "其他类型";
    }

    private static List<String> parseHosts(Object v, List<String> errors) {
        if (v == null) return List.of();
        if (!(v instanceof List<?> list)) {
            errors.add("metadata.jm.hosts 必须是列表，例如 [api.example.com]");
            return List.of();
        }
        if (list.size() > MAX_HOSTS) {
            errors.add("metadata.jm.hosts 最多写 " + MAX_HOSTS + " 个");
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                errors.add("metadata.jm.hosts 里的每一项都必须是字符串");
                continue;
            }
            try {
                out.add(HostPattern.parse(s).text());
            } catch (IllegalArgumentException e) {
                // HostPattern 的消息本身就是一句完整的原因（如「不是合法的域名」「只写域名，不要带…」），直接接在「…」后面，不再另起一句
                errors.add("metadata.jm.hosts 里的「" + s + "」" + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static boolean parseWeb(Object v, List<String> errors) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        errors.add("metadata.jm.web 只能是 true 或 false");
        return false;
    }

    private static int parseTimeout(Object v, List<String> errors) {
        if (v == null) return DEFAULT_TIMEOUT_SECONDS;
        if (v instanceof Integer i && i >= 1 && i <= MAX_TIMEOUT_SECONDS) return i;
        errors.add("metadata.jm.timeout-seconds 必须是 1 到 " + MAX_TIMEOUT_SECONDS + " 之间的整数");
        return DEFAULT_TIMEOUT_SECONDS;
    }

    private static SkillRequirement parseRequires(Object v, List<String> errors) {
        if (v == null) return null;
        if (!(v instanceof String s)) {
            errors.add("metadata.jm.requires 必须是字符串");
            return null;
        }
        // 空白 = 没有前置资源，与 SkillRequirement.parse 一致；tryParse 把空白和认不出来都返回 null，所以先判空。
        // 不能直接调 parse：它认不出来就抛 ServiceException，而那个异常一构造就 log.error。
        if (s.isBlank()) return null;
        SkillRequirement requirement = SkillRequirement.tryParse(s);
        if (requirement == null) {
            String allowed = Arrays.stream(SkillRequirement.values()).map(SkillRequirement::token).collect(Collectors.joining(" / "));
            errors.add("metadata.jm.requires 只能是 " + allowed + "，收到：" + s.strip());
        }
        return requirement;
    }

    private static String parseBinding(Object v, List<String> errors) {
        if (v == null) return JmMetadata.BINDING_OPTIONAL;
        if (JmMetadata.BINDING_ALWAYS.equals(v) || JmMetadata.BINDING_OPTIONAL.equals(v)) return (String) v;
        errors.add("metadata.jm.binding 只能是 always 或 optional");
        return JmMetadata.BINDING_OPTIONAL;
    }

    private static boolean isFalsy(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof Boolean b) return !b;
        if (v instanceof Number n) return n.doubleValue() == 0d;
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof Map<?, ?> mm) return mm.isEmpty();
        return false;
    }

}

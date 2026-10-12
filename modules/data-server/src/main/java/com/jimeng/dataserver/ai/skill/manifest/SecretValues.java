package com.jimeng.dataserver.ai.skill.manifest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一批 key 的值和它们的名字——全平台<b>只有这一处</b>负责「值 → {@code {{名字}}}」（设计文档 §3.4 第 3 步）。
 * 凡是要把可能带着 key 原文的东西放到别处，都先过这里，不要再写第二份替换逻辑：
 * <ol>
 *   <li>发布处理（{@code SkillPackageProcessor}）：所有文本文件、SKILL.md 解析之前；</li>
 *   <li>构建器草稿（{@code SkillWorkspaceReader.draft}）：预览、校验报错、同步进 DRAFT 行的 name / description / body；</li>
 *   <li>错误消息：{@code ServiceException} 构造时就把文案记进 ERROR 日志，data-server 的日志又会被 Filebeat 送进 ES，
 *       所以凡是可能带出文件内容、或作者写的 hosts / paths 的文案，先抹值再放进异常。</li>
 * </ol>
 *
 * <p>「值」指当前 {@code secrets.json} 里给出的值，以及发布时解密出来的上一版的值。<b>短于 {@value #MIN_VALUE_LENGTH} 个字符
 * （按码点数）的字符串不是值</b>：{@link #of} 直接丢掉它们，替换、检查、回显都不会拿它们抹值。否则「待填写」「...」「e」这类占位
 * 会把预览抹花（实测用「e」当值，报错文案被换成了「frontmatt{{K…}}r」），还会把输出放大几十倍；有了这条下限，
 * 占位符最长 68 个字符，输出最多是输入的 8.5 倍。
 *
 * <p>★ 这个类持有 key 的真值：{@code toString} 只列名字，{@link Item#toString} 也不带值。
 *
 * <p><b>成本有上限。</b>值最长 8192 个字符、发布文件最大 25 MB、网关正文最大 50 MB，查找必须是线性的，不然一次请求就能被拖上几分钟：
 * 所有查找用 KMP，<b>每个文本字符对每个值最多被看一次</b>。{@link #replace} 每个值保留自己的 KMP 状态和已经扫到的位置：
 * 被别的值「抢走」（最左的先换）之后，从上次停下的地方续跑、不回头，只把状态砍到「落在新起点之后」的那一段
 * （沿 failure 链往回退，退的步数不超过之前前进的步数）。
 * 以前每换掉一处，就把起点落在换掉的区间之前的值从头重扫：短值反复抢在长值前面时，长值每次都要重扫约 |L| 个字符
 * （8 字符的值配 8192 字符的值，5 MB 文本要 3.7 秒）。
 *
 * <p>替换是一遍扫过去、最左边的先换、同一位置长的先换（值互为前缀时不会只换掉一半）、不重叠；换进去的占位符不会被再扫一遍。
 * 两个不同的值首尾相接地重叠出现（A 的尾巴就是 B 的头）时，只换掉先出现的那个，后一个剩下它的后半截——替换本身不处理这种情况，
 * 由 {@link #replacement} 返回的 {@link Replacement#notFullyReplaced} 在原文上核对出来，发布处理器据此拒绝。
 */
public final class SecretValues {

    /** 短于这个长度（按码点数）的字符串不是 key 的值。与 {@code SkillSecretsFile.MIN_VALUE_LENGTH} 是同一个数。 */
    public static final int MIN_VALUE_LENGTH = 8;

    public static final SecretValues NONE = new SecretValues(List.of());

    /** 一个值和它的名字。toString 不带值。 */
    public record Item(String name, String value, boolean percentHexInsensitive) {
        public Item(String name, String value) { this(name, value, false); }
        @Override
        public String toString() {
            return "Item[" + name + "]";
        }
    }

    private final List<Item> items;       // 按值的长度从长到短，同样长的保持传入顺序
    private final char[][] chars;         // 每个值的字符
    private final int[][] charFailure;    // 每个值的 KMP 前缀表（按字符）
    private volatile Bytes bytes;         // UTF-8 字节版，查二进制文件时才建
    private volatile Strict strict;
    private volatile Lower lower;         // 小写版，查 hosts 时才建

    private SecretValues(List<Item> sorted) {
        this.items = sorted;
        this.chars = new char[sorted.size()][];
        this.charFailure = new int[sorted.size()][];
        for (int i = 0; i < sorted.size(); i++) {
            chars[i] = sorted.get(i).value().toCharArray();
            charFailure[i] = failure(chars[i]);
        }
    }

    /**
     * 不到 {@value #MIN_VALUE_LENGTH} 个码点的值（含空串）丢掉；两个名字的值相同时都留着（{@link #duplicatePairs} 要用），
     * 替换时排在前面的名字先到先得。
     */
    public static SecretValues of(Collection<Item> items) {
        List<Item> kept = new ArrayList<>();
        for (Item it : items) {
            if (it == null || it.name() == null || it.value() == null) continue;
            if (it.value().codePointCount(0, it.value().length()) < MIN_VALUE_LENGTH) continue;
            kept.add(it);
        }
        if (kept.isEmpty()) return NONE;
        kept.sort(Comparator.comparingInt((Item it) -> it.value().length()).reversed());   // sort 是稳定的
        return new SecretValues(List.copyOf(kept));
    }

    public static SecretValues of(Map<String, String> nameToValue) {
        List<Item> list = new ArrayList<>();
        nameToValue.forEach((n, v) -> list.add(new Item(n, v)));
        return of(list);
    }

    /** secrets.json 条目里给了值的那些。 */
    public static SecretValues ofEntries(List<SkillSecretsFile.Entry> entries) {
        List<Item> list = new ArrayList<>();
        for (SkillSecretsFile.Entry e : entries) list.add(new Item(e.name(), e.value()));
        return of(list);
    }

    public SecretValues plus(SecretValues other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;
        List<Item> all = new ArrayList<>(items);
        all.addAll(other.items);
        return of(all);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<String> names() {
        return items.stream().map(Item::name).distinct().toList();
    }

    /** 值相同的两个名字（每一对只报一次）。文件里的原文没法判断该换成哪一个，所以发布时不允许。 */
    public List<String[]> duplicatePairs() {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (items.get(i).value().equals(items.get(j).value()) && !items.get(i).name().equals(items.get(j).name())) {
                    out.add(new String[]{items.get(i).name(), items.get(j).name()});
                }
            }
        }
        return out;
    }

    /** 把文本里所有的值换成 {@code {{名字}}}。 */
    public String replace(String text) {
        if (text == null || text.isEmpty() || items.isEmpty()) return text;
        final char[] t = text.toCharArray();
        Spans spans = select(t);
        return spans.count == 0 ? text : build(t, spans);
    }

    /** 有界字节替换；stopped 表示仍有输入或替换输出未写入。 */
    public record ByteReplacement(byte[] bytes, boolean stopped) {}

    public byte[] replaceBytes(byte[] content) {
        return replaceBytes(content, Integer.MAX_VALUE).bytes();
    }

    /**
     * UTF-8 字节版，同字符串版的最左最长、非重叠、KMP 续跑循环；直接写有界输出，不缓存所有命中区间。
     * 时间 O(写法数 × 输入长度)，堆 O(maxOut + 最长写法)，另加写法本身；无命中且未截断时保留同一数组。
     */
    public ByteReplacement replaceBytes(byte[] t, int maxOut) {
        if (maxOut < 0) throw new IllegalArgumentException("输出上限不能为负");
        if (t == null) return new ByteReplacement(null, false);
        if (t.length == 0 || items.isEmpty()) {
            return new ByteReplacement(t.length <= maxOut ? t : Arrays.copyOf(t, maxOut), t.length > maxOut);
        }
        Bytes b = bytes();
        final int k = items.size();
        int[] next = new int[k], scanned = new int[k], state = new int[k];
        for (int i = 0; i < k; i++) next[i] = advance(t, b.bytes[i], b.failure[i], 0, scanned, state, i);
        java.io.ByteArrayOutputStream out = null;
        int pos = 0;
        while (true) {
            int best = -1;
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && (best < 0 || next[i] < next[best]
                        || next[i] == next[best] && b.bytes[i].length > b.bytes[best].length)) best = i;
            }
            if (best < 0) {
                if (out == null) return new ByteReplacement(t.length <= maxOut ? t : Arrays.copyOf(t, maxOut), t.length > maxOut);
                int n = Math.min(t.length - pos, maxOut - out.size());
                out.write(t, pos, n);
                return new ByteReplacement(out.toByteArray(), pos + n < t.length);
            }
            if (out == null) out = new java.io.ByteArrayOutputStream(Math.min(maxOut, Math.min(t.length, 8192)));
            int n = Math.min(next[best] - pos, maxOut - out.size());
            out.write(t, pos, n);
            if (pos + n < next[best]) return new ByteReplacement(out.toByteArray(), true);
            byte[] replacement = ("{{" + items.get(best).name() + "}}").getBytes(StandardCharsets.UTF_8);
            n = Math.min(replacement.length, maxOut - out.size());
            out.write(replacement, 0, n);
            pos = next[best] + b.bytes[best].length;
            if (n < replacement.length || out.size() == maxOut && pos < t.length) return new ByteReplacement(out.toByteArray(), true);
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && next[i] < pos) next[i] = advance(t, b.bytes[i], b.failure[i], pos, scanned, state, i);
            }
        }
    }

    /**
     * 网关专用的严格抹值：所有命中区间取并集（包括首尾重叠），不改变发布替换的非重叠语义。
     * 每字节维护各写法的 KMP 状态，差分环延迟 maxLength 个字节再输出，确保更晚结束的命中不会留下前半截。
     * 时间 O(写法数 × (输入长度 + 输出长度))，工作堆 O(maxOut + maxLength)，不按命中数量分配。
     * 默认标记仍是 {{名字}}；它含已知值时改用安全短标记。若拼接后的输出意外组成某个已知值，整段输出置空。
     * Item 的 percentHexInsensitive 只在编码写法的 %HH 位生效，原文和非编码字符仍区分大小写。
     * 编码模式最多有三个固定前缀变体（总模式数 ≤ 18 × key 数），完成候选时仅核对两字节原始前缀，保持线性成本。
     */
    public ByteReplacement scrubBytes(byte[] t, int maxOut) {
        if (maxOut < 0) throw new IllegalArgumentException("输出上限不能为负");
        if (t == null) return new ByteReplacement(null, false);
        if (items.isEmpty() || t.length == 0) return replaceBytes(t, maxOut);
        Strict patterns = strict();
        int longest = patterns.maxLength;
        int ringSize = longest + 1;
        int[] delta = new int[ringSize], ends = new int[ringSize], which = new int[ringSize], state = new int[patterns.patterns.length];
        java.io.ByteArrayOutputStream out = null;
        int active = 0;
        // 扫完原文后再推进最长窗口，吐出最后的安全字节；每个差分槽在吐出后清零。
        for (long scan = 0; scan < (long)t.length + longest - 1; scan++) {
            if (scan < t.length) {
                int i = (int)scan;
                byte raw = t[i], canonical = canonicalHex(t, i);
                for (int k = 0; k < state.length; k++) {
                    byte c = patterns.percent[k] ? canonical : raw;
                    byte[] pattern = patterns.patterns[k];
                    int q = state[k];
                    while (q > 0 && c != pattern[q]) q = patterns.failure[k][q - 1];
                    if (c == pattern[q]) q++;
                    if (q == pattern.length) {
                        int start = i - pattern.length + 1;
                        if (prefixMatches(t, start, patterns.prefixes[k])) {
                            delta[start % ringSize]++;
                            delta[(i + 1) % ringSize]--;
                            ends[(i + 1) % ringSize]++;
                            which[start % ringSize] = k;
                        }
                        q = patterns.failure[k][q - 1];
                    }
                    state[k] = q;
                }
            }
            long emit = scan - longest + 1;
            if (emit < 0) continue;
            int pos = (int)emit, slot = pos % ringSize, previous = active;
            active += delta[slot];
            boolean startsUnion = active > 0 && previous - ends[slot] == 0;
            delta[slot] = 0;
            ends[slot] = 0;
            if (active > 0) {
                if (startsUnion) {
                    if (out == null) {
                        out = new java.io.ByteArrayOutputStream(Math.min(maxOut, 8192));
                        int n = Math.min(pos, maxOut);
                        out.write(t, 0, n);
                        if (n < pos) return checkedStrict(out.toByteArray(), true, patterns);
                    }
                    byte[] marker = patterns.markers[which[slot]];
                    int n = Math.min(marker.length, maxOut - out.size());
                    out.write(marker, 0, n);
                    if (n < marker.length) return checkedStrict(out.toByteArray(), true, patterns);
                }
            } else if (out != null) {
                if (out.size() == maxOut) return checkedStrict(out.toByteArray(), true, patterns);
                out.write(t[pos]);
            } else if (pos >= maxOut) {
                return new ByteReplacement(Arrays.copyOf(t, maxOut), true);
            }
            if (out != null && out.size() == maxOut && pos + 1 < t.length) return checkedStrict(out.toByteArray(), true, patterns);
        }
        return out == null ? new ByteReplacement(t, false) : checkedStrict(out.toByteArray(), false, patterns);
    }

    public String scrub(String text) {
        if (text == null || text.isEmpty() || items.isEmpty()) return text;
        byte[] in = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = scrubBytes(in, Integer.MAX_VALUE).bytes();
        return out == in ? text : new String(out, StandardCharsets.UTF_8);
    }

    private ByteReplacement checkedStrict(byte[] out, boolean stopped, Strict patterns) {
        // 防止安全片段与生成标记拼接后意外重建一个值。拒绝带值的整段输出，不递归展开/替换生成的标记。
        for (int k = 0; k < patterns.patterns.length; k++) {
            if (containsCanonical(out, patterns.patterns[k], patterns.failure[k], patterns.percent[k], patterns.prefixes[k])) {
                return new ByteReplacement(new byte[0], stopped);
            }
        }
        return new ByteReplacement(out, stopped);
    }

    private record Strict(byte[][] patterns, int[][] failure, boolean[] percent, byte[][] prefixes,
                          byte[][] markers, int maxLength) {}

    private Strict strict() {
        Strict value = strict;
        if (value != null) return value;
        Bytes b = bytes();
        int max = 0;
        List<byte[]> patterns = new ArrayList<>(), prefixes = new ArrayList<>(), markers = new ArrayList<>();
        List<int[]> fail = new ArrayList<>();
        List<Boolean> percent = new ArrayList<>();
        Map<String, byte[]> byName = new java.util.HashMap<>();
        for (int k = 0; k < items.size(); k++) {
            Item item = items.get(k);
            boolean encoded = item.percentHexInsensitive();
            byte[] pattern = b.bytes[k];
            byte[] marker = byName.computeIfAbsent(item.name(), name -> {
                String text = "{{" + name + "}}";
                if (!namesIn(text).isEmpty()) text = namesIn("[redacted]").isEmpty() ? "[redacted]" : "*";
                return text.getBytes(StandardCharsets.UTF_8);
            });
            byte[] prefix = encoded && pattern[0] != '%' ? Arrays.copyOf(pattern, Math.min(2, pattern.length)) : new byte[0];
            List<byte[]> variants = encoded ? canonicalVariants(pattern) : List.of(pattern);
            for (byte[] variant : variants) {
                patterns.add(variant);
                fail.add(encoded ? failure(variant) : b.failure[k]);
                percent.add(encoded);
                prefixes.add(prefix);
                markers.add(marker);
            }
            max = Math.max(max, pattern.length);
        }
        boolean[] modes = new boolean[percent.size()];
        for (int k = 0; k < modes.length; k++) modes[k] = percent.get(k);
        strict = value = new Strict(patterns.toArray(byte[][]::new), fail.toArray(int[][]::new), modes,
                prefixes.toArray(byte[][]::new), markers.toArray(byte[][]::new), max);
        return value;
    }

    /**
     * 来源中紧邻候选起点前的 %，最多改变候选头一/两个字节的 canonical spelling。
     * 每个编码写法最多编译三个固定字节模式，使用正常 KMP failure 表；不引入位置敏感的相等规则。
     * 候选完成时再 O(1) 核对原始前两字节，防止把真正的小写 opaque 前缀当成大写值。
     */
    private static List<byte[]> canonicalVariants(byte[] original) {
        byte[] base = original.clone();
        for (int i = 0; i < base.length; i++) base[i] = canonicalHex(original, i);
        List<byte[]> variants = new ArrayList<>();
        variants.add(base);
        byte[] first = base.clone();
        first[0] = lowerHexLetter(first[0]);
        if (!Arrays.equals(base, first)) variants.add(first);
        if (base.length > 1 && hex(original[0]) && hex(original[1])) {
            byte[] both = first.clone();
            both[1] = lowerHexLetter(both[1]);
            if (variants.stream().noneMatch(v -> Arrays.equals(v, both))) variants.add(both);
        }
        return variants;
    }

    private static byte lowerHexLetter(byte c) {
        return c >= 'A' && c <= 'F' ? (byte)(c + 'a' - 'A') : c;
    }

    private static boolean prefixMatches(byte[] input, int start, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) if (input[start + i] != prefix[i]) return false;
        return true;
    }

    private static boolean containsCanonical(byte[] t, byte[] pattern, int[] fail, boolean percent, byte[] prefix) {
        int q = 0;
        for (int i = 0; i < t.length; i++) {
            byte c = percent ? canonicalHex(t, i) : t[i];
            while (q > 0 && c != pattern[q]) q = fail[q - 1];
            if (c == pattern[q]) q++;
            if (q == pattern.length) {
                if (prefixMatches(t, i - pattern.length + 1, prefix)) return true;
                q = fail[q - 1];
            }
        }
        return false;
    }

    private static byte canonicalHex(byte[] t, int i) {
        byte c = t[i];
        if (c < 'A' || c > 'F') return c;
        boolean first = i > 0 && i + 1 < t.length && t[i - 1] == '%' && hex(t[i + 1]);
        boolean second = i > 1 && t[i - 2] == '%' && hex(t[i - 1]);
        return first || second ? (byte)(c + ('a' - 'A')) : c;
    }

    private static boolean hex(byte c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    /**
     * 网关文本进入 JSON 前的最后一道检查。使用实际 Java 渲染器（模型协议 / Spring / Logstash），
     * 连查 payload、content、外层事件三层；JSON.stringify 对这些合法 Unicode 文本采用同一套转义。
     * 后续渲染若能重建已知值，保守丢弃该字段；不把密钥交给消费者。
     */
    public String scrubJson(String text) {
        String clean = scrub(text);
        return safeJson(clean) ? clean : "";
    }

    /** 最终 Map 已经完成头名归一化、正文截断及 note 拼接，检查包括字段边界的完整编码。 */
    public boolean safeJson(Object value) {
        return safeJson(value, value instanceof Map<?, ?>);
    }

    private boolean safeJson(Object value, boolean checkToolClip) {
        if (items.isEmpty()) return true;
        Set<String> layer = new LinkedHashSet<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            layer.add(json.writeValueAsString(value));
            layer.add(cn.hutool.json.JSONUtil.toJsonStr(value));
            for (int depth = 0; depth < 3; depth++) {
                Set<String> next = new LinkedHashSet<>();
                for (String rendered : layer) {
                    if (rendered.length() > 4 * 1024 * 1024 || !rendered.equals(scrub(rendered))) return false;
                    if (checkToolClip && depth == 0 && rendered.length() > 20_000) {
                        int end = Character.isHighSurrogate(rendered.charAt(19_999)) ? 19_999 : 20_000;
                        // 宿主 MCP 的最后一步 clip；同一回包必须在这个追加标记后仍然安全。
                        String clipped = rendered.substring(0, end) + "…（结果超过 20000 字已截断：只看到了前面一部分，不要据此下「总共 / 最多 / 没有」之类的结论）";
                        if (!clipped.equals(scrub(clipped)) || !safeJson(clipped, false)) return false;
                    }
                    if (depth < 2) {
                        next.add(json.writeValueAsString(rendered));
                        next.add(cn.hutool.json.JSONUtil.toJsonStr(rendered));
                    }
                }
                layer = next;
            }
            return true;
        } catch (Exception encoding) { return false; }
    }

    /**
     * 同 {@link #replace}，并且留下选中的替换区间，供 {@link Replacement#notFullyReplaced} 在原文上核对
     * 「别的值的每一处出现是不是都被这些替换盖住了」。
     */
    public Replacement replacement(String text) {
        if (text == null || text.isEmpty()) return new Replacement(text, new char[0], new Spans());
        final char[] t = text.toCharArray();
        return new Replacement(text, t, items.isEmpty() ? new Spans() : select(t));
    }

    /** 一遍扫描选出来的替换区间：按起点从小到大、互不重叠；第 j 个区间 [starts[j], ends[j]) 换成 items[which[j]] 的占位符。 */
    private static final class Spans {
        int[] starts = new int[16];
        int[] ends = new int[16];
        int[] which = new int[16];
        int count;

        void add(int start, int end, int item) {
            if (count == starts.length) {
                int n = count * 2;
                starts = Arrays.copyOf(starts, n);
                ends = Arrays.copyOf(ends, n);
                which = Arrays.copyOf(which, n);
            }
            starts[count] = start;
            ends[count] = end;
            which[count] = item;
            count++;
        }
    }

    /** 最左的先换；同一位置长的先换（排在前面的），从换掉的这一段后面接着找。 */
    private Spans select(char[] t) {
        final int k = items.size();
        final int[] next = new int[k];      // 每个值下一次出现的位置（不早于上一次替换的结尾）；-1 = 后面没有了
        final int[] scanned = new int[k];   // 这个值已经扫到 t 的哪里：t[0, scanned) 看过了
        final int[] state = new int[k];     // 扫到 scanned 时的 KMP 状态：t 里以 scanned 结尾、同时是这个值前缀的最长一段的长度
        for (int i = 0; i < k; i++) next[i] = advance(t, chars[i], charFailure[i], 0, scanned, state, i);
        Spans spans = new Spans();
        while (true) {
            int best = -1;
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && (best < 0 || next[i] < next[best])) best = i;   // 同一位置：排在前面的（更长的）先到先得
            }
            if (best < 0) break;
            final int pos = next[best] + chars[best].length;
            spans.add(next[best], pos, best);
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && next[i] < pos) next[i] = advance(t, chars[i], charFailure[i], pos, scanned, state, i);   // 续跑，不回头
            }
        }
        return spans;
    }

    private String build(char[] t, Spans spans) {
        StringBuilder out = new StringBuilder(t.length);
        int pos = 0;
        for (int j = 0; j < spans.count; j++) {
            out.append(t, pos, spans.starts[j] - pos).append("{{").append(items.get(spans.which[j]).name()).append("}}");
            pos = spans.ends[j];
        }
        return out.append(t, pos, t.length - pos).toString();
    }

    /**
     * 一次替换的结果，连同选中的替换区间。
     *
     * <p>★ 残留要在<b>原文</b>上判，不能在替换之后的文本里搜：当前值只盖住旧值的前一半时，替换之后旧值已经不再整段出现，
     * 剩下的后一半却原样留在文本里。{@link #notFullyReplaced} 对已知集合里的每个值、它在原文里的每一处出现
     * （含互相重叠的），看这一处的每个字符是不是都被选中的替换盖住了（相接的区间算一整片）；有一个字符没被盖住，
     * 就是有一截原文会留下来。同时兜住三种情况：旧值被当前值盖住一部分；旧值和当前值首尾相接；两个当前值在文本里互相重叠
     * （被抢掉的那个，剩下的部分原样留下）。旧值整段落在一个更长的当前值里（新值是「Bearer 旧值」）、或者旧值同时也是
     * 当前某个 key 的值（改了名），都整段被盖住，不报。
     */
    public final class Replacement {
        private final String original;
        private final char[] t;
        private final Spans spans;
        private String result;

        private Replacement(String original, char[] t, Spans spans) {
            this.original = original;
            this.t = t;
            this.spans = spans;
        }

        /** 替换之后的文本；原文里没有任何命中时是同一个对象。 */
        public String text() {
            if (result == null) result = spans.count == 0 ? original : build(t, spans);
            return result;
        }

        /**
         * 已知的值里，在原文里有一处出现没有被选中的替换整个盖住的那些，按已知集合里的顺序，只有名字。
         * 耗时与文本长度成正比：每个值一遍 KMP，区间用一个只往前走的指针对。
         */
        public List<String> notFullyReplaced(SecretValues known) {
            if (known == null || known.items.isEmpty() || t.length == 0) return List.of();
            // 相接的区间并成一片：[runStarts[r], runEnds[r])
            int[] runStarts = new int[spans.count];
            int[] runEnds = new int[spans.count];
            int runs = 0;
            for (int j = 0; j < spans.count; j++) {
                if (runs > 0 && runEnds[runs - 1] == spans.starts[j]) {
                    runEnds[runs - 1] = spans.ends[j];
                } else {
                    runStarts[runs] = spans.starts[j];
                    runEnds[runs] = spans.ends[j];
                    runs++;
                }
            }
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < known.items.size(); i++) {
                if (names.contains(known.items.get(i).name())) continue;
                if (hasOccurrenceOutside(t, known.chars[i], known.charFailure[i], runStarts, runEnds, runs)) names.add(known.items.get(i).name());
            }
            return List.copyOf(names);
        }
    }

    /** p 在 t 里有没有一处出现（含互相重叠的）不被某一片 [runStarts, runEnds) 整个盖住。 */
    private static boolean hasOccurrenceOutside(char[] t, char[] p, int[] fail, int[] runStarts, int[] runEnds, int runs) {
        final int m = p.length;
        int q = 0;
        int r = 0;   // 起点不晚于当前出现的最后一片；出现的起点只增不减，所以只往前走
        for (int i = 0; i < t.length; i++) {
            char c = t[i];
            while (q > 0 && c != p[q]) q = fail[q - 1];
            if (c == p[q]) q++;
            if (q == m) {
                int start = i - m + 1;
                while (r + 1 < runs && runStarts[r + 1] <= start) r++;
                if (runs == 0 || runStarts[r] > start || runEnds[r] < i + 1) return true;
                q = fail[m - 1];
            }
        }
        return false;
    }

    /**
     * 第 i 个值在 t 里从 {@code from} 起的第一次出现的位置；-1 = 没有了。从上次停下的地方（scanned[i]、state[i]）续跑：
     * 上次停在一次完整匹配的末尾，状态是这个值最长的真前缀边界，重叠着的下一次出现也找得到；起点在 from 之前的那一段
     * 匹配前缀要砍掉（沿 failure 链退到不超过 scanned - from）。
     */
    private static int advance(char[] t, char[] p, int[] fail, int from, int[] scanned, int[] state, int i) {
        int s = scanned[i];
        int q = state[i];
        if (s < from) {
            s = from;
            q = 0;
        } else {
            while (q > s - from) q = fail[q - 1];
        }
        final int n = t.length;
        final int m = p.length;
        for (; s < n; s++) {
            char c = t[s];
            while (q > 0 && c != p[q]) q = fail[q - 1];
            if (c == p[q]) q++;
            if (q == m) {
                scanned[i] = s + 1;
                state[i] = fail[m - 1];
                return s - m + 1;
            }
        }
        scanned[i] = n;
        state[i] = q;
        return -1;
    }

    private static int advance(byte[] t, byte[] p, int[] fail, int from, int[] scanned, int[] state, int i) {
        int s = scanned[i];
        int q = state[i];
        if (s < from) {
            s = from;
            q = 0;
        } else {
            while (q > s - from) q = fail[q - 1];
        }
        final int n = t.length;
        final int m = p.length;
        for (; s < n; s++) {
            byte c = t[s];
            while (q > 0 && c != p[q]) q = fail[q - 1];
            if (c == p[q]) q++;
            if (q == m) {
                scanned[i] = s + 1;
                state[i] = fail[m - 1];
                return s - m + 1;
            }
        }
        scanned[i] = n;
        state[i] = q;
        return -1;
    }

    /** 文本里出现了哪些 key 的值（区分大小写；给文件路径、paths、替换之后的残留检查用）。每个值最多把文本扫一遍。 */
    public List<String> namesIn(String text) {
        Set<String> hit = new LinkedHashSet<>();
        if (text != null && !text.isEmpty() && !items.isEmpty()) {
            char[] t = text.toCharArray();
            for (int i = 0; i < items.size(); i++) {
                if (contains(t, chars[i], charFailure[i])) hit.add(items.get(i).name());
            }
        }
        return List.copyOf(hit);
    }

    /** 同上，但不分大小写（给 hosts 用：域名在库里一律是小写的）。 */
    public List<String> namesInIgnoreCase(String text) {
        Set<String> hit = new LinkedHashSet<>();
        if (text != null && !text.isEmpty() && !items.isEmpty()) {
            char[] t = text.toLowerCase(Locale.ROOT).toCharArray();
            Lower lw = lower();
            for (int i = 0; i < items.size(); i++) {
                if (contains(t, lw.chars[i], lw.failure[i])) hit.add(items.get(i).name());
            }
        }
        return List.copyOf(hit);
    }

    /** 字节里出现了哪些 key 的值（UTF-8 原文；给二进制文件用）。 */
    public List<String> namesIn(byte[] content) {
        Set<String> hit = new LinkedHashSet<>();
        if (content != null && content.length > 0 && !items.isEmpty()) {
            Bytes b = bytes();
            for (int i = 0; i < items.size(); i++) {
                if (contains(content, b.bytes[i], b.failure[i])) hit.add(items.get(i).name());
            }
        }
        return List.copyOf(hit);
    }

    @Override
    public String toString() {
        return "SecretValues" + names();
    }

    // ---- 懒建的字节版、小写版（只有查二进制 / hosts 时才要） ----

    private record Bytes(byte[][] bytes, int[][] failure) {}

    private record Lower(char[][] chars, int[][] failure) {}

    private Bytes bytes() {
        Bytes b = bytes;
        if (b == null) {
            byte[][] arr = new byte[items.size()][];
            int[][] fail = new int[items.size()][];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = items.get(i).value().getBytes(StandardCharsets.UTF_8);
                fail[i] = failure(arr[i]);
            }
            bytes = b = new Bytes(arr, fail);
        }
        return b;
    }

    private Lower lower() {
        Lower l = lower;
        if (l == null) {
            char[][] arr = new char[items.size()][];
            int[][] fail = new int[items.size()][];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = items.get(i).value().toLowerCase(Locale.ROOT).toCharArray();
                fail[i] = failure(arr[i]);
            }
            lower = l = new Lower(arr, fail);
        }
        return l;
    }

    // ---- KMP ----

    private static int[] failure(char[] p) {
        int[] f = new int[p.length];
        for (int i = 1, k = 0; i < p.length; i++) {
            while (k > 0 && p[i] != p[k]) k = f[k - 1];
            if (p[i] == p[k]) k++;
            f[i] = k;
        }
        return f;
    }

    private static int[] failure(byte[] p) {
        int[] f = new int[p.length];
        for (int i = 1, k = 0; i < p.length; i++) {
            while (k > 0 && p[i] != p[k]) k = f[k - 1];
            if (p[i] == p[k]) k++;
            f[i] = k;
        }
        return f;
    }

    private static boolean contains(char[] t, char[] p, int[] fail) {
        final int m = p.length;
        int q = 0;
        for (char c : t) {
            while (q > 0 && c != p[q]) q = fail[q - 1];
            if (c == p[q]) q++;
            if (q == m) return true;
        }
        return false;
    }

    private static boolean contains(byte[] t, byte[] p, int[] fail) {
        final int m = p.length;
        int q = 0;
        for (byte c : t) {
            while (q > 0 && c != p[q]) q = fail[q - 1];
            if (c == p[q]) q++;
            if (q == m) return true;
        }
        return false;
    }
}

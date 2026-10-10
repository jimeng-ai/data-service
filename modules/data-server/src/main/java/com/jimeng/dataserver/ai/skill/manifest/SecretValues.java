package com.jimeng.dataserver.ai.skill.manifest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p><b>成本有上限。</b>值最长 8192 个字符、文件最大 25 MB，查找必须是线性的，不然一次请求就能被拖上几分钟：
 * 所有查找用 KMP，<b>每个文本字符对每个值最多被看一次</b>。{@link #replace} 每个值保留自己的 KMP 状态和已经扫到的位置：
 * 被别的值「抢走」（最左的先换）之后，从上次停下的地方续跑、不回头，只把状态砍到「落在新起点之后」的那一段
 * （沿 failure 链往回退，退的步数不超过之前前进的步数）。
 * 以前每换掉一处，就把起点落在换掉的区间之前的值从头重扫：短值反复抢在长值前面时，长值每次都要重扫约 |L| 个字符
 * （8 字符的值配 8192 字符的值，5 MB 文本要 3.7 秒）。
 *
 * <p>替换是一遍扫过去、最左边的先换、同一位置长的先换（值互为前缀时不会只换掉一半）、不重叠；换进去的占位符不会被再扫一遍。
 * 已知的缺口：两个不同的值首尾相接地重叠出现（A 的尾巴就是 B 的头）时，只换掉先出现的那个，后一个剩下它的后半截。
 */
public final class SecretValues {

    /** 短于这个长度（按码点数）的字符串不是 key 的值。与 {@code SkillSecretsFile.MIN_VALUE_LENGTH} 是同一个数。 */
    public static final int MIN_VALUE_LENGTH = 8;

    public static final SecretValues NONE = new SecretValues(List.of());

    /** 一个值和它的名字。toString 不带值。 */
    public record Item(String name, String value) {
        @Override
        public String toString() {
            return "Item[" + name + "]";
        }
    }

    private final List<Item> items;       // 按值的长度从长到短，同样长的保持传入顺序
    private final char[][] chars;         // 每个值的字符
    private final int[][] charFailure;    // 每个值的 KMP 前缀表（按字符）
    private volatile Bytes bytes;         // UTF-8 字节版，查二进制文件时才建
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
        final int k = items.size();
        final int[] next = new int[k];      // 每个值下一次出现的位置（不早于上一次替换的结尾）；-1 = 后面没有了
        final int[] scanned = new int[k];   // 这个值已经扫到 t 的哪里：t[0, scanned) 看过了
        final int[] state = new int[k];     // 扫到 scanned 时的 KMP 状态：t 里以 scanned 结尾、同时是这个值前缀的最长一段的长度
        for (int i = 0; i < k; i++) next[i] = advance(t, chars[i], charFailure[i], 0, scanned, state, i);
        StringBuilder out = null;
        int pos = 0;
        while (true) {
            int best = -1;
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && (best < 0 || next[i] < next[best])) best = i;   // 同一位置：排在前面的（更长的）先到先得
            }
            if (best < 0) break;
            if (out == null) out = new StringBuilder(text.length());
            Item it = items.get(best);
            out.append(t, pos, next[best] - pos).append("{{").append(it.name()).append("}}");
            pos = next[best] + chars[best].length;
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && next[i] < pos) next[i] = advance(t, chars[i], charFailure[i], pos, scanned, state, i);   // 续跑，不回头
            }
        }
        return out == null ? text : out.append(t, pos, t.length - pos).toString();
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

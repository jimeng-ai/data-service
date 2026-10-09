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
 * <p>「值」指当前 {@code secrets.json} 里给出的值，以及沿用上一版、发布时解密出来的值。
 *
 * <p>★ 这个类持有 key 的真值：{@code toString} 只列名字，{@link Item#toString} 也不带值。
 *
 * <p>查找用 KMP，耗时与文本长度成正比：值最长 8192 个字符，文件最大 25 MB，朴素的 {@code indexOf} 在
 * 「aaaa…ab」这类构造出来的输入上是二次方的，一次发布就能把一个请求线程拖上几分钟。
 *
 * <p>替换是一遍扫过去、最左边的先换、同一位置长的先换（值互为前缀时不会只换掉一半）；换进去的占位符不会被再扫一遍。
 * 已知的缺口：两个不同的值首尾相接地重叠出现（A 的尾巴就是 B 的头）时，只换掉先出现的那个，后一个剩下它的后半截。
 */
public final class SecretValues {

    public static final SecretValues NONE = new SecretValues(List.of());

    /** 一个值和它的名字。toString 不带值。 */
    public record Item(String name, String value) {
        @Override
        public String toString() {
            return "Item[" + name + "]";
        }
    }

    private final List<Item> items;       // 按值的长度从长到短，同样长的保持传入顺序
    private final int[][] charFailure;    // 每个值的 KMP 前缀表（按字符）
    private final byte[][] bytes;         // 每个值的 UTF-8 字节
    private final int[][] byteFailure;

    private SecretValues(List<Item> sorted) {
        this.items = sorted;
        this.charFailure = new int[sorted.size()][];
        this.bytes = new byte[sorted.size()][];
        this.byteFailure = new int[sorted.size()][];
        for (int i = 0; i < sorted.size(); i++) {
            String v = sorted.get(i).value();
            charFailure[i] = failure(v);
            bytes[i] = v.getBytes(StandardCharsets.UTF_8);
            byteFailure[i] = failure(bytes[i]);
        }
    }

    /** 空值丢掉；两个名字的值相同时都留着（{@link #duplicatePairs} 要用），替换时排在前面的名字先到先得。 */
    public static SecretValues of(Collection<Item> items) {
        List<Item> kept = new ArrayList<>();
        for (Item it : items) {
            if (it != null && it.name() != null && it.value() != null && !it.value().isEmpty()) kept.add(it);
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
        int k = items.size();
        int[] next = new int[k];     // 每个值下一次出现的位置，-1 = 后面没有了
        for (int i = 0; i < k; i++) next[i] = find(text, 0, items.get(i).value(), charFailure[i]);
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
            out.append(text, pos, next[best]).append("{{").append(it.name()).append("}}");
            pos = next[best] + it.value().length();
            for (int i = 0; i < k; i++) {
                if (next[i] >= 0 && next[i] < pos) next[i] = find(text, pos, items.get(i).value(), charFailure[i]);
            }
        }
        return out == null ? text : out.append(text, pos, text.length()).toString();
    }

    /** 文本里出现了哪些 key 的值（区分大小写；给文件路径、paths 用）。 */
    public List<String> namesIn(String text) {
        Set<String> hit = new LinkedHashSet<>();
        if (text != null) {
            for (int i = 0; i < items.size(); i++) {
                if (find(text, 0, items.get(i).value(), charFailure[i]) >= 0) hit.add(items.get(i).name());
            }
        }
        return List.copyOf(hit);
    }

    /** 同上，但不分大小写（给 hosts 用：域名在库里一律是小写的）。 */
    public List<String> namesInIgnoreCase(String text) {
        if (text == null) return List.of();
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> hit = new LinkedHashSet<>();
        for (Item it : items) {
            String v = it.value().toLowerCase(Locale.ROOT);
            if (lower.contains(v)) hit.add(it.name());
        }
        return List.copyOf(hit);
    }

    /** 字节里出现了哪些 key 的值（UTF-8 原文；给二进制文件用）。 */
    public List<String> namesIn(byte[] content) {
        Set<String> hit = new LinkedHashSet<>();
        if (content != null) {
            for (int i = 0; i < items.size(); i++) {
                if (find(content, bytes[i], byteFailure[i]) >= 0) hit.add(items.get(i).name());
            }
        }
        return List.copyOf(hit);
    }

    @Override
    public String toString() {
        return "SecretValues" + names();
    }

    // ---- KMP ----

    private static int[] failure(String p) {
        int[] f = new int[p.length()];
        for (int i = 1, k = 0; i < p.length(); i++) {
            while (k > 0 && p.charAt(i) != p.charAt(k)) k = f[k - 1];
            if (p.charAt(i) == p.charAt(k)) k++;
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

    private static int find(String hay, int from, String needle, int[] fail) {
        int m = needle.length();
        int q = 0;
        for (int i = from; i < hay.length(); i++) {
            char c = hay.charAt(i);
            while (q > 0 && c != needle.charAt(q)) q = fail[q - 1];
            if (c == needle.charAt(q)) q++;
            if (q == m) return i - m + 1;
        }
        return -1;
    }

    private static int find(byte[] hay, byte[] needle, int[] fail) {
        int m = needle.length;
        int q = 0;
        for (int i = 0; i < hay.length; i++) {
            byte c = hay[i];
            while (q > 0 && c != needle[q]) q = fail[q - 1];
            if (c == needle[q]) q++;
            if (q == m) return i - m + 1;
        }
        return -1;
    }
}

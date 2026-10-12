package com.jimeng.dataserver.ai.skill.manifest;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 构造器消除重复键 / merge 前的源节点审查。所有预算同时用于主解析与宽松重试。 */
final class SourceYaml {
    private static final int MAX_SOURCE = 512 * 1024, MAX_NODES = 16384, MAX_ALIASES = 256;
    private static final int MAX_DEPTH = 50, MAX_DECODED = 1024 * 1024;
    static final class Limit extends IllegalArgumentException {}
    record Loaded(Object value, List<ParsedSkill.Decoded> decoded) {}
    private static LoaderOptions options() {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(MAX_SOURCE);
        o.setNestingDepthLimit(MAX_DEPTH);
        o.setMaxAliasesForCollections(50);
        return o;
    }
    static Loaded load(String source) {
        scan(source, false); // 标量别名也计数，不能依赖库的 collection-only 限制
        Node root = new Yaml(new SafeConstructor(options())).compose(new StringReader(source));
        Budget budget = new Budget();
        ScalarConstructor constructor = new ScalarConstructor();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (root instanceof MappingNode map) {
            seen.add(root);
            for (NodeTuple tuple : map.getValue()) {
                Node key = tuple.getKeyNode();
                String top = key instanceof ScalarNode scalar ? scalar.getValue() : "frontmatter";
                walk(key, "顶层的键名", "顶层的键名", 1, seen, constructor, budget);
                walk(tuple.getValueNode(), top, top + " 里的键名", 1, seen, constructor, budget);
            }
        } else walk(root, "frontmatter", "frontmatter 里的键名", 0, seen, constructor, budget);
        // 只有有界的源图通过后才允许构造 Map；不能用这个 Map 代替源图来核对值。
        return new Loaded(new Yaml(new SafeConstructor(options())).load(source), List.copyOf(budget.out));
    }
    private static void walk(Node node, String where, String keys, int depth, Set<Node> seen,
                             ScalarConstructor constructor, Budget budget) {
        if (node == null || !seen.add(node)) return; // 包括标量和 !!binary，只解码一次
        if (depth > MAX_DEPTH || seen.size() > MAX_NODES) throw new Limit();
        if (node instanceof MappingNode map) {
            for (NodeTuple tuple : map.getValue()) {
                walk(tuple.getKeyNode(), keys, keys, depth + 1, seen, constructor, budget);
                walk(tuple.getValueNode(), where, keys, depth + 1, seen, constructor, budget);
            }
        } else if (node instanceof SequenceNode seq) {
            for (Node child : seq.getValue()) walk(child, where, keys, depth + 1, seen, constructor, budget);
        } else if (node instanceof ScalarNode scalar) {
            budget.add(where, scalar.getValue());
            if (!Tag.STR.equals(scalar.getTag()) && !Tag.MERGE.equals(scalar.getTag())) {
                Object value = constructor.scalar(scalar);
                String decoded = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
                if (!decoded.equals(scalar.getValue())) budget.add(where, decoded);
            }
        }
    }
    private static final class ScalarConstructor extends SafeConstructor {
        ScalarConstructor() { super(options()); }
        Object scalar(ScalarNode node) { return constructObject(node); }
    }
    private static final class Budget {
        final List<ParsedSkill.Decoded> out = new ArrayList<>();
        int bytes;
        void add(String where, String value) {
            // 最多三字节/UTF-16 单位；在创建 UTF-8 临时数组之前检查上界。
            if (value.length() > MAX_DECODED || out.size() >= MAX_NODES) throw new Limit();
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_DECODED) throw new Limit();
            out.add(new ParsedSkill.Decoded(where, value));
        }
    }
    /** Scanner 不丢弃被重复键覆盖的标量，也能读出语法不合法 flow 后面重试将转义的原始 scalar token。 */
    static List<ParsedSkill.Decoded> scan(String source, boolean tolerateSyntax) {
        if (source.length() > MAX_SOURCE) throw new Limit();
        ScannerImpl scanner = new ScannerImpl(new StreamReader(source), options());
        Budget budget = new Budget();
        int tokens = 0, aliases = 0, depth = 0;
        Tag explicit = null;
        ScalarConstructor constructor = new ScalarConstructor();
        var resolver = new org.yaml.snakeyaml.resolver.Resolver();
        try {
            while (scanner.checkToken()) {
                Token token = scanner.getToken();
                if (++tokens > MAX_NODES * 4) throw new Limit();
                if (token instanceof AliasToken && ++aliases > MAX_ALIASES) throw new Limit();
                if (token instanceof FlowSequenceStartToken || token instanceof FlowMappingStartToken
                        || token instanceof BlockSequenceStartToken || token instanceof BlockMappingStartToken) {
                    if (++depth > MAX_DEPTH) throw new Limit();
                } else if (token instanceof FlowSequenceEndToken || token instanceof FlowMappingEndToken || token instanceof BlockEndToken) depth--;
                if (token instanceof TagToken tag) {
                    String handle = tag.getValue().getHandle();
                    explicit = new Tag("!!".equals(handle) ? Tag.PREFIX + tag.getValue().getSuffix() : tag.getValue().getSuffix());
                }
                if (token instanceof ScalarToken scalar) {
                    budget.add("原始 frontmatter", scalar.getValue());
                    Tag tag = explicit == null ? resolver.resolve(NodeId.scalar, scalar.getValue(), scalar.getPlain()) : explicit;
                    if (!Tag.STR.equals(tag) && !Tag.MERGE.equals(tag)) {
                        Object value = constructor.scalar(new ScalarNode(tag, scalar.getValue(), scalar.getStartMark(), scalar.getEndMark(), scalar.getStyle()));
                        String decoded = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
                        if (!decoded.equals(scalar.getValue())) budget.add("原始 frontmatter", decoded);
                    }
                    explicit = null;
                } else if (!(token instanceof TagToken) && !(token instanceof AnchorToken)) explicit = null;
                if (token instanceof StreamEndToken) break;
            }
        } catch (Limit exceeded) { throw exceeded;
        } catch (RuntimeException syntax) { if (!tolerateSyntax) throw syntax; }
        return budget.out;
    }
    /** 重试前逐个检查实际被补引号的原行，避免首个 scanner 错误遮住后面的原始转义。 */
    static List<ParsedSkill.Decoded> beforeRetry(String source, String quoted) {
        List<ParsedSkill.Decoded> all = new ArrayList<>(scan(source, true));
        String[] before = source.replace("\r\n", "\n").split("\n", -1), after = quoted.split("\n", -1);
        Budget total = new Budget();
        for (var item : all) total.add(item.where(), item.text());
        for (int i = 0; i < before.length; i++) {
            if (!before[i].equals(after[i])) {
                int colon = before[i].indexOf(':');
                String value = before[i].substring(colon + 1).stripLeading();
                for (var item : scan(value, true)) total.add(item.where(), item.text());
                // `code` 等非法首字符会令 scanner 提前停止。仍逐个用同一 YAML scanner 解码原行的完整引号标量。
                scanQuotedScalars(value, total);
            }
        }
        return List.copyOf(total.out);
    }
    private static void scanQuotedScalars(String value, Budget budget) {
        for (int i = 0; i < value.length(); i++) {
            char quote = value.charAt(i);
            if (quote != 34 && quote != 39) continue;
            int start = i;
            for (i++; i < value.length(); i++) {
                char c = value.charAt(i);
                if (quote == 34 && c == 92) { i++; continue; }
                if (c != quote) continue;
                if (quote == 39 && i + 1 < value.length() && value.charAt(i + 1) == 39) { i++; continue; }
                for (var item : scan(value.substring(start, i + 1), false)) budget.add(item.where(), item.text());
                break;
            }
        }
    }
}

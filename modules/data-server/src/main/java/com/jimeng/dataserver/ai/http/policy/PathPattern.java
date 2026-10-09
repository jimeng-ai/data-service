package com.jimeng.dataserver.ai.http.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * key 的 {@code paths} 里的一条（设计文档 §3.3）：{@code **} 可以跨层级，{@code *} 只匹配一层，其余字符按字面。
 * 匹配对象是请求网址<b>编码后的路径</b>（OkHttp 的 {@code HttpUrl.encodedPath()}）；编码过的分隔符由网关先拒掉，见 HttpGateway。
 *
 * <p>★ 不翻成正则来匹配：Java 的正则是回溯引擎，一条写了多个 {@code **} 的 paths 碰上一条差一点就能匹配的长路径，
 * 耗时随路径长度按幂次膨胀（实测 8 个 {@code **} 配 61 个字符要 10 秒）。skill 的作者就能写出这样的 paths，
 * 而网关是所有租户共用的，所以这里按「逐段推进」匹配：耗时与（段数 × 路径长度）成正比，写成什么样都一样。
 * 语义与 {@code ** → .*}、{@code * → [^/]*}、其余按字面的正则写法一致，{@code PathPatternTest} 用随机用例对拍。
 */
public final class PathPattern {

    /** 一段：字面文字（{@code literal != null}），或通配（{@code crossesSlash} 为 true 的是 {@code **}，否则是 {@code *}）。 */
    private record Segment(String literal, boolean crossesSlash) {}

    private final String text;
    private final List<Segment> segments;

    private PathPattern(String text, List<Segment> segments) {
        this.text = text;
        this.segments = segments;
    }

    public static PathPattern parse(String raw) {
        if (raw == null || !raw.startsWith("/")) throw new IllegalArgumentException("路径必须以 / 开头");
        if (raw.contains("?") || raw.contains("#") || raw.contains("\\") || raw.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("路径里不要带查询串、#、空白或反斜杠");
        }
        List<Segment> segments = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            if (raw.charAt(i) == '*') {
                if (literal.length() > 0) {
                    segments.add(new Segment(literal.toString(), false));
                    literal.setLength(0);
                }
                boolean doubleStar = raw.startsWith("**", i);
                segments.add(new Segment(null, doubleStar));
                i += doubleStar ? 2 : 1;
            } else {
                literal.append(raw.charAt(i++));
            }
        }
        if (literal.length() > 0) segments.add(new Segment(literal.toString(), false));
        return new PathPattern(raw, List.copyOf(segments));
    }

    public boolean matches(String encodedPath) {
        if (encodedPath == null) return false;
        int n = encodedPath.length();
        // reached[j]：前面的段恰好能匹配 encodedPath 的前 j 个字符
        boolean[] reached = new boolean[n + 1];
        boolean[] next = new boolean[n + 1];
        reached[0] = true;
        for (Segment s : segments) {
            Arrays.fill(next, false);
            boolean any = false;
            if (s.literal() != null) {
                int len = s.literal().length();
                for (int j = 0; j + len <= n; j++) {
                    if (reached[j] && encodedPath.startsWith(s.literal(), j)) {
                        next[j + len] = true;
                        any = true;
                    }
                }
            } else {
                // 通配吃掉零个或多个字符；* 不能吃 /
                for (int j = 0; j <= n; j++) {
                    next[j] = reached[j]
                            || (j > 0 && next[j - 1] && (s.crossesSlash() || encodedPath.charAt(j - 1) != '/'));
                    any |= next[j];
                }
            }
            if (!any) return false;
            boolean[] swap = reached;
            reached = next;
            next = swap;
        }
        return reached[n];
    }

    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}

package com.jimeng.dataserver.ai.http.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * key 的 {@code paths} 里的一条（设计文档 §3.3）：{@code **} 可以跨层级（零个或多个任意字符），{@code *} 只匹配一层
 * （<b>一个或多个</b>非 {@code /} 的字符，不能是空段），其余字符按字面。
 * 匹配对象是请求网址<b>编码后的路径</b>（OkHttp 的 {@code HttpUrl.encodedPath()}）；编码过的分隔符由网关先拒掉，见 HttpGateway。
 *
 * <p>★ 写了也永远匹配不上的写法，{@link #parse} 直接拒绝（不拦的话作者会以为路径限制生效了，实际每个请求都被拒，还查不出原因）：
 * 非 ASCII / 控制字符 / 空白、{@code %2F %5C %2E}、{@code .} 和 {@code ..} 段，以及 OkHttp 在路径里一定会编码掉的可见字符
 * （{@code " < > ^ ` { | }}，AI 照 OpenAPI 写的 {@code /v1/orders/{id}} 就是最常见的一种）。
 * 孤立的 {@code %}（后面不跟两位十六进制数字）不在其内：OkHttp 按网址解析时原样保留它，匹配得上。
 *
 * <p>{@code *} 不能匹配空：{@code encodedPath()} 保留 {@code //} 和结尾的 {@code /}，而 nginx 一类的上游会把 {@code //}
 * 合并、把 {@code /x/} 当成 {@code /x}，允许空段就能用它绕过路径限制（把 {@code *} 夹在两个斜杠之间的写法会放行
 * {@code /v1/orders//items}，上游看到的是 {@code /v1/orders/items}）。{@code **} 仍可以匹配空尾，
 * 带 {@code //} 的路径要靠网关对「限定了 paths 的 key」整条拒绝。
 *
 * <p>★ 不翻成正则来匹配：Java 的正则是回溯引擎，一条写了多个 {@code **} 的 paths 碰上一条差一点就能匹配的长路径，
 * 耗时随路径长度按幂次膨胀（实测 8 个 {@code **} 配 61 个字符要 10 秒）。skill 的作者就能写出这样的 paths，
 * 而网关是所有租户共用的，所以这里按「逐段推进」匹配：耗时与（段数 × 路径长度）成正比，写成什么样都一样。
 * 语义与 {@code ** → .*}、{@code * → [^/]+}、其余按字面的正则写法一致，{@code PathPatternTest} 用随机用例对拍。
 */
public final class PathPattern {

    /** 一段：字面文字（{@code literal != null}），或通配（{@code crossesSlash} 为 true 的是 {@code **}，否则是 {@code *}）。 */
    private record Segment(String literal, boolean crossesSlash) {}

    /** 路径里不能写、写了也永远匹配不上的编码：网关对带这类编码的请求会先整条拒掉。 */
    private static final Pattern REFUSED_ENCODINGS = Pattern.compile("%(2f|5c|2e)", Pattern.CASE_INSENSITIVE);

    /**
     * 可见 ASCII 里，OkHttp 解析网址时一定会在路径里编码成 {@code %XX} 的字符（不含已经整条拒绝的 {@code ? # \}、通配 {@code *}、
     * 分隔符 {@code /}、转义的起点 {@code %}）。这个集合不是凭记忆写的：{@code PathPatternTest} 对 0x21–0x7E 的每个字符
     * 用真实的 {@code HttpUrl.get(…).encodedPath()} 对拍，OkHttp 升级后集合变了，那条测试先红。
     */
    private static final String ENCODED_AWAY = "\"<>^`{|}";

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
        // 下面这几类写法永远匹配不上：不拦的话作者会以为路径限制生效了，实际每个请求都被拒，还查不出原因。
        if (raw.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw new IllegalArgumentException("路径里有非 ASCII 字符（或控制字符）：请求网址里它们会被编码成 %XX，"
                    + "这样写永远匹配不上；请写成编码之后的样子");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (ENCODED_AWAY.indexOf(c) >= 0) {
                String encoded = String.format("%%%02X", (int) c);
                boolean brace = c == '{' || c == '}';
                throw new IllegalArgumentException("路径里有「" + c + "」：请求网址里它会被编码成 " + encoded + "，这样写永远匹配不上；"
                        + (brace ? "路径参数（比如 {id}）请写成 *（要匹配花括号本身，就写成 " + encoded + "）" : "要匹配它就写成 " + encoded));
            }
        }
        if (REFUSED_ENCODINGS.matcher(raw).find()) {
            throw new IllegalArgumentException("路径里不能写 %2F、%5C、%2E（不分大小写）：带这类编码的请求，网关会先整条拒掉，"
                    + "这样写永远匹配不上");
        }
        for (String segment : raw.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("路径里不能有「.」「..」这样的段：请求网址里它们会被先解析掉，"
                        + "这样写永远匹配不上");
            }
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
                // ** 吃掉零个或多个任意字符；* 吃掉一个或多个非 / 的字符
                for (int j = 0; j <= n; j++) {
                    if (s.crossesSlash()) {
                        next[j] = reached[j] || (j > 0 && next[j - 1]);
                    } else {
                        next[j] = j > 0 && encodedPath.charAt(j - 1) != '/' && (reached[j - 1] || next[j - 1]);
                    }
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

package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PathPatternTest {

    @Test
    @DisplayName("** 跨层级、* 只匹配一层、其余按字面（与设计文档 §3.3 一致）")
    void globs() {
        PathPattern any = PathPattern.parse("/v1/orders/**");
        assertThat(any.matches("/v1/orders/123")).isTrue();
        assertThat(any.matches("/v1/orders/a/b")).isTrue();
        assertThat(any.matches("/v1/orders")).isFalse();
        PathPattern one = PathPattern.parse("/v1/orders/*/items");
        assertThat(one.matches("/v1/orders/9/items")).isTrue();
        assertThat(one.matches("/v1/orders/9/x/items")).isFalse();
        PathPattern exact = PathPattern.parse("/v1/refunds");
        assertThat(exact.matches("/v1/refunds")).isTrue();
        assertThat(exact.matches("/v1/refunds/1")).isFalse();
        assertThat(PathPattern.parse("/a.b+c").matches("/aXb+c")).isFalse();
    }

    @Test
    @DisplayName("★ * 只匹配一个非空的段：OkHttp 的 encodedPath() 保留 // 和结尾的 /，nginx 一类的上游会把它们合并，空段不能拿来绕过路径限制")
    void starNeedsAtLeastOneCharacter() {
        PathPattern middle = PathPattern.parse("/v1/orders/*/items");
        assertThat(middle.matches("/v1/orders/9/items")).isTrue();
        assertThat(middle.matches("/v1/orders//items")).isFalse();
        PathPattern tail = PathPattern.parse("/v1/orders/*");
        assertThat(tail.matches("/v1/orders/123")).isTrue();
        assertThat(tail.matches("/v1/orders/")).isFalse();
        assertThat(tail.matches("/v1/orders/1/2")).isFalse();
        assertThat(PathPattern.parse("/a*b").matches("/ab")).isFalse();
        assertThat(PathPattern.parse("/a*b").matches("/aXb")).isTrue();
    }

    @Test
    @DisplayName("必须以 / 开头，不带查询串、#、空白、反斜杠")
    void rejects() {
        for (String bad : new String[]{"", "v1/x", "/v1?x=1", "/v1#a", "/v 1", "/v1\\x"}) {
            assertThatThrownBy(() -> PathPattern.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("通配在开头 / 结尾 / 相邻 / 夹在字面之间：空段也算一段，/a/**/b 不匹配 /a/b")
    void wildcardEdges() {
        assertThat(PathPattern.parse("/**").matches("/")).isTrue();
        assertThat(PathPattern.parse("/**").matches("/a/b/c")).isTrue();
        assertThat(PathPattern.parse("/*").matches("/")).isFalse();
        assertThat(PathPattern.parse("/*").matches("/a")).isTrue();
        assertThat(PathPattern.parse("/*").matches("/a/b")).isFalse();
        assertThat(PathPattern.parse("/a/**/b").matches("/a/x/y/b")).isTrue();
        assertThat(PathPattern.parse("/a/**/b").matches("/a/b")).isFalse();
        assertThat(PathPattern.parse("/a*b").matches("/axxb")).isTrue();
        assertThat(PathPattern.parse("/a*b").matches("/ax/xb")).isFalse();
        assertThat(PathPattern.parse("/a/***/b").matches("/a/x/y/b")).isTrue();   // ** 后面再跟一个 *，等于 ** 后接 *
        assertThat(PathPattern.parse("/v1/orders").matches(null)).isFalse();
        assertThat(PathPattern.parse("/v1/orders").matches("")).isFalse();
    }

    @Test
    @DisplayName("与「翻成正则」的写法对拍（** → .*，* → [^/]+）：随机的模式和路径，结论逐条一致（只用 / a b * 四个字符，覆盖相邻通配、空段、重叠字面）")
    void agreesWithTheRegexTranslation() {
        Random rnd = new Random(20261009L);
        String patternAlphabet = "/ab**";
        String pathAlphabet = "/ab";
        for (int i = 0; i < 40_000; i++) {
            StringBuilder pat = new StringBuilder("/");
            for (int k = rnd.nextInt(7); k > 0; k--) pat.append(patternAlphabet.charAt(rnd.nextInt(patternAlphabet.length())));
            StringBuilder path = new StringBuilder("/");
            for (int k = rnd.nextInt(9); k > 0; k--) path.append(pathAlphabet.charAt(rnd.nextInt(pathAlphabet.length())));
            assertThat(PathPattern.parse(pat.toString()).matches(path.toString()))
                    .as("模式 %s 匹配 %s", pat, path)
                    .isEqualTo(regexOf(pat.toString()).matcher(path).matches());
        }
    }

    /** 参照写法：** → .*，* → [^/]+（一个或多个非 / 的字符），其余按字面。 */
    private static Pattern regexOf(String raw) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            if (raw.charAt(i) == '*') {
                if (literal.length() > 0) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                boolean doubleStar = raw.startsWith("**", i);
                re.append(doubleStar ? ".*" : "[^/]+");
                i += doubleStar ? 2 : 1;
            } else {
                literal.append(raw.charAt(i++));
            }
        }
        if (literal.length() > 0) re.append(Pattern.quote(literal.toString()));
        return Pattern.compile(re.toString());
    }

    @Test
    @DisplayName("★ 多个 ** 加一条差一点就能匹配的长路径不会把网关拖住（正则回溯：8 个 ** 配 61 个字符要 10 秒；skill 作者能写出这样的 paths）")
    void manyWildcardsDoNotBlowUp() {
        StringBuilder pat = new StringBuilder();
        for (int i = 0; i < 8; i++) pat.append("/**");
        PathPattern p = PathPattern.parse(pat.append("/x").toString());
        String nearMiss = "/".repeat(400) + "y";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThat(p.matches(nearMiss)).isFalse());
        String hit = "/".repeat(400) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThat(p.matches(hit)).isTrue());
    }
    @Test
    @DisplayName("★ 拒绝永远匹配不上的写法并说明原因：%2F %5C %2E（网关先拒）、非 ASCII（OkHttp 会编码）、. 和 .. 段（OkHttp 会解析掉）")
    void rejectsPatternsThatCanNeverMatch() {
        assertThatThrownBy(() -> PathPattern.parse("/files/a%2Fb")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("%2F");
        assertThatThrownBy(() -> PathPattern.parse("/files/a%2fb")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("网关");
        assertThatThrownBy(() -> PathPattern.parse("/files/a%5Cb")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("%5C");
        assertThatThrownBy(() -> PathPattern.parse("/files/%2e%2e/x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("%2E");
        assertThatThrownBy(() -> PathPattern.parse("/订单/列表")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非 ASCII");
        assertThatThrownBy(() -> PathPattern.parse("/a/./b")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("「.」");
        assertThatThrownBy(() -> PathPattern.parse("/a/..")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("「..」");
        assertThatThrownBy(() -> PathPattern.parse("/a\u0001b")).isInstanceOf(IllegalArgumentException.class);
        // 正常写法不受影响：带点的文件名、编码过的空格、通配
        for (String ok : new String[]{"/v1/a.b", "/v1/a..b", "/v1/.well-known/x", "/v1/a%20b", "/v1/*.json", "/v1/**"}) {
            assertThat(PathPattern.parse(ok).text()).isEqualTo(ok);
        }
    }
}

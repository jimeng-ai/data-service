package com.jimeng.dataserver.ai.http.policy;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
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

    // ---------------------------------------------------------------- 第 2 轮 A（R20）：拒绝 OkHttp 在路径里一定会编码掉的字符

    /** 真实的 OkHttp 把这条路径解析成网址之后的 encodedPath()——网关匹配的就是它。 */
    private static String okHttpEncodedPath(String rawPath) {
        return HttpUrl.get("http://h.test" + rawPath).encodedPath();
    }

    @Test
    @DisplayName("★ 对拍真实的 OkHttp（HttpUrl.get(…).encodedPath()）：可见 ASCII 字符里 OkHttp 一定会编码掉的，parse 必须拒绝；不会编码的必须放行（不许多拒）。"
            + "拒绝集合不凭记忆写：字符放在段首、段中、段尾都测")
    void agreesWithOkHttpAboutWhichCharactersAreEncodedAway() {
        Set<Character> encodedAway = new TreeSet<>();
        for (char c = 0x21; c <= 0x7E; c++) {
            if ("/*%?#\\".indexOf(c) >= 0) continue;   // 分隔符、通配、转义的起点，以及已经整条拒绝的 ? # \
            for (String raw : new String[]{"/" + c + "ab", "/a" + c + "b", "/ab" + c}) {
                boolean changed = !okHttpEncodedPath(raw).equals(raw);
                if (changed) {
                    encodedAway.add(c);
                    assertThatThrownBy(() -> PathPattern.parse(raw)).as("OkHttp 会编码 %s（%s），这样写永远匹配不上", c, raw)
                            .isInstanceOf(IllegalArgumentException.class);
                } else {
                    assertThat(PathPattern.parse(raw).text()).as("OkHttp 不会编码 %s（%s），不许多拒", c, raw).isEqualTo(raw);
                }
            }
        }
        // 对拍出来的集合本身也钉住：OkHttp 升级后集合变了，这里先红，提醒来改
        assertThat(encodedAway).containsExactly('"', '<', '>', '^', '`', '{', '|', '}');
    }

    @Test
    @DisplayName("★ 对拍 OkHttp 的 %：合法转义（%7B、%41、%7b……）原样保留，能解析（%2F %5C %2E 按第 1 轮 M9 仍拒）；"
            + "后面不跟两位十六进制数字的 %，OkHttp 按网址解析时同样原样保留（不是 %25），所以也能解析——不许多拒")
    void percentSignsAgreeWithOkHttp() {
        String chars = "0123456789abcdefABCDEFgG-%.";   // 十六进制数字（大小写）、非十六进制字符、%、.
        List<String> raws = new ArrayList<>();
        raws.add("/a%");
        raws.add("/a%/b");
        for (char x : chars.toCharArray()) {
            raws.add("/a%" + x);
            raws.add("/a%" + x + "/b");
            for (char y : chars.toCharArray()) raws.add("/a%" + x + y + "b");
        }
        int accepted = 0;
        for (String raw : raws) {
            boolean refusedByM9 = Pattern.compile("%(2f|5c|2e)", Pattern.CASE_INSENSITIVE).matcher(raw).find();
            boolean changed = !okHttpEncodedPath(raw).equals(raw);
            if (refusedByM9 || changed) {
                assertThatThrownBy(() -> PathPattern.parse(raw)).as(raw).isInstanceOf(IllegalArgumentException.class);
            } else {
                assertThat(PathPattern.parse(raw).text()).as("OkHttp 原样保留 %s，不许多拒", raw).isEqualTo(raw);
                accepted++;
            }
        }
        assertThat(accepted).as("合法转义和孤立的 % 都在放行的里面").isGreaterThan(400);
        for (String ok : new String[]{"/v1/a%7Bb", "/v1/a%7bb", "/v1/a%41", "/v1/%e4%b8%ad"}) {
            assertThat(PathPattern.parse(ok).text()).isEqualTo(ok);
        }
    }

    @Test
    @DisplayName("★ 文案：说明这个字符会被编码掉、这样写永远匹配不上；{ 和 } 额外提示「路径参数（比如 {id}）请写成 *」（AI 照 OpenAPI 写的 /v1/orders/{id}）")
    void theMessageExplainsWhatToWriteInstead() {
        assertThatThrownBy(() -> PathPattern.parse("/v1/orders/{id}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("「{」").hasMessageContaining("%7B").hasMessageContaining("永远匹配不上")
                .hasMessageContaining("路径参数（比如 {id}）请写成 *");
        assertThatThrownBy(() -> PathPattern.parse("/v1/orders/id}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("「}」").hasMessageContaining("%7D").hasMessageContaining("路径参数（比如 {id}）请写成 *");
        assertThatThrownBy(() -> PathPattern.parse("/v1/a\"b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%22").hasMessageContaining("永远匹配不上").hasMessageNotContaining("路径参数");
        assertThat(PathPattern.parse("/v1/orders/*").matches("/v1/orders/42")).isTrue();   // 提示里教的写法是通的
    }
}

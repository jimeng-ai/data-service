package com.jimeng.dataserver.ai.skill.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SecretValuesTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";
    private static final String PREFIX = "sk-live-CRM";   // 另一个 key 的值恰好是它的前缀

    private static SecretValues two() {
        return SecretValues.of(List.of(new SecretValues.Item("CRM_PREFIX", PREFIX), new SecretValues.Item("CRM_TOKEN", TOKEN)));
    }

    @Test
    @DisplayName("所有出现的地方都换成 {{名字}}；值互为前缀时长的先换，短的单独出现时照样换")
    void replacesEveryOccurrenceLongestFirst() {
        String out = two().replace("Bearer " + TOKEN + " / " + PREFIX + " / " + TOKEN);
        assertThat(out).isEqualTo("Bearer {{CRM_TOKEN}} / {{CRM_PREFIX}} / {{CRM_TOKEN}}");
    }

    @Test
    @DisplayName("没有值、没有命中、空文本：原样返回（同一个对象）")
    void untouchedWhenNothingMatches() {
        String text = "没有任何 key 的文字";
        assertThat(two().replace(text)).isSameAs(text);
        assertThat(SecretValues.NONE.replace(text)).isSameAs(text);
        assertThat(two().replace("")).isEmpty();
        assertThat(two().replace(null)).isNull();
    }

    @Test
    @DisplayName("换进去的占位符不会被再扫一遍（按顺序一个值一个值地 replace 会把它切碎）；平常的值再换一次结果不变")
    void placeholdersAreNotRescanned() {
        // 另一个 key 的值恰好等于占位符里的一段文字：换第一个 key 时产生的 {{CRM_TOKEN}} 不能被第二个 key 吃掉
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item("CRM_TOKEN", TOKEN), new SecretValues.Item("OTHER_KEY", "CRM_TOKEN")));
        assertThat(v.replace("a " + TOKEN + " b")).isEqualTo("a {{CRM_TOKEN}} b");
        String once = two().replace("x " + TOKEN);
        assertThat(two().replace(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("★ 随机对拍：值互相重叠、互为前缀、文本只由 a b 两个字母组成——与朴素写法逐条一致，替换之后任何一个值都不再整段出现")
    void noValueSurvivesAndAgreesWithTheNaiveVersion() {
        Random rnd = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = 10 + rnd.nextInt(110); n > 0; n--) text.append(rnd.nextBoolean() ? 'a' : 'b');
            List<SecretValues.Item> items = new ArrayList<>();
            String base = randomAb(rnd, 8 + rnd.nextInt(5));
            items.add(new SecretValues.Item("K_BASE", base));
            if (rnd.nextInt(3) > 0) items.add(new SecretValues.Item("K_LONGER", base + randomAb(rnd, 1 + rnd.nextInt(6))));      // 以 base 为前缀
            if (rnd.nextInt(3) > 0) items.add(new SecretValues.Item("K_OVERLAP", base.substring(3) + randomAb(rnd, 3 + rnd.nextInt(6))));   // 头是 base 的尾巴
            if (rnd.nextInt(3) > 0) items.add(new SecretValues.Item("K_OTHER", randomAb(rnd, 8 + rnd.nextInt(7))));
            if (rnd.nextInt(4) == 0) {   // 取自文本本身，保证有命中
                int from = rnd.nextInt(text.length() - 8);
                items.add(new SecretValues.Item("K_INSIDE", text.substring(from, from + 4 + rnd.nextInt(4)).repeat(2)));
            }
            SecretValues values = SecretValues.of(items);
            String out = values.replace(text.toString());
            assertThat(new String(values.replaceBytes(text.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)).isEqualTo(naive(items, text.toString()));
            assertThat(out).as("值 %s 文本 %s", items, text).isEqualTo(naive(items, text.toString()));
            for (SecretValues.Item it : items) {
                if (it.value().length() >= SecretValues.MIN_VALUE_LENGTH) assertThat(out).as("%s 在 %s 里", it, out).doesNotContain(it.value());
            }
        }
    }

    private static String randomAb(Random rnd, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(rnd.nextBoolean() ? 'a' : 'b');
        return sb.toString();
    }

    /** 参照写法：每一步取最靠左的出现，同一位置取更长的（同样长的取靠前的），整段换掉，从它后面接着找。 */
    private static String naive(List<SecretValues.Item> items, String text) {
        List<SecretValues.Item> sorted = new ArrayList<>();
        for (SecretValues.Item it : items) if (it.value().length() >= SecretValues.MIN_VALUE_LENGTH) sorted.add(it);
        sorted.sort((a, b) -> Integer.compare(b.value().length(), a.value().length()));
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int bestAt = -1;
            SecretValues.Item best = null;
            for (SecretValues.Item it : sorted) {
                int at = text.indexOf(it.value(), pos);
                if (at >= 0 && (bestAt < 0 || at < bestAt)) {
                    bestAt = at;
                    best = it;
                }
            }
            if (best == null) break;
            out.append(text, pos, bestAt).append("{{").append(best.name()).append("}}");
            pos = bestAt + best.value().length();
        }
        return out.append(text.substring(pos)).toString();
    }

    @Test
    @DisplayName("namesIn：文本、字节、不分大小写三种查法，结论与 contains 一致")
    void namesInAgreesWithContains() {
        SecretValues v = two();
        assertThat(v.namesIn("path/" + TOKEN + "/x")).containsExactlyInAnyOrder("CRM_TOKEN", "CRM_PREFIX");
        assertThat(v.namesIn("path/" + PREFIX + "/x")).containsExactly("CRM_PREFIX");
        assertThat(v.namesIn("path/sk-live-crm/x")).isEmpty();
        assertThat(v.namesInIgnoreCase("path/sk-live-crm/x")).containsExactly("CRM_PREFIX");
        assertThat(v.namesIn(("\0\1" + TOKEN).getBytes(StandardCharsets.UTF_8))).containsExactlyInAnyOrder("CRM_TOKEN", "CRM_PREFIX");
        assertThat(v.namesIn(new byte[]{0, 1, 2})).isEmpty();
        assertThat(v.namesIn((String) null)).isEmpty();
        SecretValues cjk = SecretValues.of(List.of(new SecretValues.Item("ZH_KEY", "密钥密钥密钥密钥")));
        assertThat(cjk.namesIn("前缀密钥密钥密钥密钥后缀".getBytes(StandardCharsets.UTF_8))).containsExactly("ZH_KEY");
    }

    @Test
    @DisplayName("★ 查找是线性的：2MB 的「aaa…」配「aaa…ab」不会拖住线程，替换和两种 namesIn 合计 1 秒内（新实现约 0.02 s；朴素写法 String.indexOf 每项约 3 s、逐字节的双重循环约 7 s，合计十几秒）")
    void searchIsLinear() {
        String hay = "a".repeat(2_000_000);
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item("TRAP_KEY", "a".repeat(8191) + "b")));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThat(v.replace(hay)).isSameAs(hay);
            assertThat(v.namesIn(hay)).isEmpty();
            assertThat(v.namesIn(hay.getBytes(StandardCharsets.UTF_8))).isEmpty();
        });
    }

    @Test
    @DisplayName("值相同的两个名字：duplicatePairs 报出来，替换时排在前面的名字先到先得")
    void duplicateValues() {
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item("KEY_A", TOKEN), new SecretValues.Item("KEY_B", TOKEN)));
        assertThat(v.duplicatePairs()).singleElement().satisfies(p -> assertThat(p).containsExactly("KEY_A", "KEY_B"));
        assertThat(v.replace("x " + TOKEN)).isEqualTo("x {{KEY_A}}");
        assertThat(two().duplicatePairs()).isEmpty();
    }

    @Test
    @DisplayName("toString 只列名字，不带值（Item 也一样）")
    void toStringHasNoValues() {
        assertThat(two().toString()).contains("CRM_TOKEN", "CRM_PREFIX").doesNotContain(TOKEN).doesNotContain(PREFIX);
        assertThat(new SecretValues.Item("CRM_TOKEN", TOKEN).toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("plus 合并两批；空值被丢掉")
    void plusAndEmpty() {
        SecretValues merged = SecretValues.of(List.of(new SecretValues.Item("A_KEY", "value-of-a-key")))
                .plus(SecretValues.of(java.util.Map.of("B_KEY", "value-of-b-key", "EMPTY_KEY", "")));
        assertThat(merged.names()).containsExactlyInAnyOrder("A_KEY", "B_KEY");
        assertThat(merged.replace("value-of-a-key value-of-b-key")).isEqualTo("{{A_KEY}} {{B_KEY}}");
        assertThat(SecretValues.NONE.plus(SecretValues.NONE).isEmpty()).isTrue();
    }
    // ---------------------------------------------------------------- 第 2 轮：抹值的成本有上限

    @Test
    @DisplayName("★ 短于 8 个字符的字符串不是值：替换、检查、回显都不拿它抹（否则「待填写」「...」「e」会把预览抹花，输出被放大几十倍）")
    void stringsShorterThanEightCharactersAreNotValues() {
        String longName = "K" + "X".repeat(63);   // 64 个字符的 key 名
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item(longName, "e"), new SecretValues.Item("SHORT_KEY", "short7c"),
                new SecretValues.Item("EMPTY_KEY", "")));
        assertThat(v.isEmpty()).isTrue();
        String text = "frontmatter: e short7c";
        assertThat(v.replace(text)).isSameAs(text);
        assertThat(v.namesIn(text)).isEmpty();
        assertThat(SecretValues.of(List.of(new SecretValues.Item("EDGE_KEY", "exactly8"))).isEmpty()).isFalse();
        // 8 个「字符」按码点数：4 个补充平面的字符只有 4 个码点，不算
        assertThat(SecretValues.of(List.of(new SecretValues.Item("EMOJI_KEY", "\uD83D\uDE00".repeat(4)))).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("★ 输出的膨胀有上限：值至少 8 个字符、占位符最长 68 个字符，输出不超过输入的 8.5 倍；用「e」当值配 64 个字符的 key 名，输出一旦放大几十倍就红")
    void theOutputIsAtMostEightPointFiveTimesTheInput() {
        String longName = "K" + "X".repeat(63);
        String onlyEs = "e".repeat(1_000_000);
        SecretValues shortOne = SecretValues.of(List.of(new SecretValues.Item(longName, "e")));
        assertThat(shortOne.replace(onlyEs).length()).isLessThanOrEqualTo((int) (onlyEs.length() * 8.5));

        String eightChars = "ABCDEFGH";
        String dense = eightChars.repeat(125_000);   // 1 MB，每 8 个字符就有一处要换
        String out = SecretValues.of(List.of(new SecretValues.Item(longName, eightChars))).replace(dense);
        assertThat(out.length()).isEqualTo(125_000 * (longName.length() + 4));
        assertThat(out.length()).isLessThanOrEqualTo((int) (dense.length() * 8.5));
    }

    @Test
    @DisplayName("★ 替换是线性的：一个 8 字符的值和一个 8192 字符的值在 5 MB 文本里反复抢位置（复审的「baba…」写法），1 秒内完成；"
            + "以前短值每抢一次，长值就从头重扫约 8192 个字符，总共要几秒")
    void twoOverlappingValuesOverFiveMegabytesTakeUnderASecond() {
        String shortValue = "ab".repeat(4);                    // 8 个字符，出现在偶数位置
        String longValue = "ba".repeat(4096);                  // 8192 个字符，出现在奇数位置
        String text = "ab".repeat(2_621_440);                  // 5 MB
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item("LONG_KEY", longValue), new SecretValues.Item("SHORT_KEY", shortValue)));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String out = v.replace(text);
            assertThat(out).startsWith("{{SHORT_KEY}}").doesNotContain(shortValue);
        });
    }

    @Test
    @DisplayName("★ 64 个值反复抢位置也是线性的：一个 8 字符的值每隔 8 个字符抢走一次，63 个 3200 字符上下、互相是前缀的周期值每次都被抢——1 MB 文本，"
            + "替换 5 秒内完成（新实现约 0.1~0.2 s，上限的 1/30 以下；旧的重扫写法每抢一次、每个长值就从头扫一遍约 3200 个字符，要三十多秒）；namesIn（文本、字节）同样")
    void sixtyFourValuesStealingFromEachOtherStayLinear() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 63; i++) m.put("LONG_" + i, "ba".repeat(1600 + i));
        m.put("SHORT_KEY", "ab".repeat(4));
        SecretValues v = SecretValues.of(m);
        String text = "ab".repeat(512 * 1024);   // 1 MB
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(v.replace(text)).startsWith("{{SHORT_KEY}}{{SHORT_KEY}}").doesNotContain("abababab"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(v.namesIn(text)).hasSize(64));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(v.namesIn(bytes)).hasSize(64));
    }

    // ---------------------------------------------------------------- 第 3 轮 1：残留检查在原文上做，按「整段落在某一次替换之内」判

    private static final String AWS_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String AWS_COMBINED = AWS_ID + ":wJalrXUtnFEMIK7MDENG";

    private static SecretValues valuesOf(String... nameValue) {
        List<SecretValues.Item> items = new ArrayList<>();
        for (int i = 0; i < nameValue.length; i += 2) items.add(new SecretValues.Item(nameValue[i], nameValue[i + 1]));
        return SecretValues.of(items);
    }

    @Test
    @DisplayName("★ 前缀覆盖：当前值只盖住旧值的前一半，旧值剩下的一半替换之后不再整段出现——按原文判，这一处出现里有字符没被选中的替换盖住")
    void aValueThatIsOnlyPartlyCoveredByAReplacementIsReported() {
        SecretValues replaceSet = valuesOf("AK_ID", AWS_ID);
        SecretValues previous = valuesOf("AWS_CRED", AWS_COMBINED);
        String text = "CRED = '" + AWS_COMBINED + "'\n";

        SecretValues.Replacement r = replaceSet.replacement(text);

        assertThat(r.text()).isEqualTo("CRED = '{{AK_ID}}:wJalrXUtnFEMIK7MDENG'\n");   // 替换之后的文本里旧值已经不是整段了：以前就是在这里放行的
        assertThat(r.notFullyReplaced(previous)).containsExactly("AWS_CRED");
        assertThat(r.notFullyReplaced(replaceSet)).isEmpty();                          // 当前值自己整段被换掉了
    }

    @Test
    @DisplayName("首尾相接：当前值的尾巴就是旧值的头（或反过来）——旧值露出来的那一截也要报")
    void adjacentOverlapsAreReportedToo() {
        SecretValues replaceSet = valuesOf("TENANT_KEY", "tenant-0042-ABCD");
        SecretValues previous = valuesOf("OLD_KEY", "ABCD-1234-wxyz-5678");
        SecretValues.Replacement r = replaceSet.replacement("X = 'tenant-0042-ABCD-1234-wxyz-5678'");
        assertThat(r.text()).isEqualTo("X = '{{TENANT_KEY}}-1234-wxyz-5678'");
        assertThat(r.notFullyReplaced(previous)).containsExactly("OLD_KEY");

        // 旧值在前、当前值在后
        SecretValues replaceSet2 = valuesOf("TAIL_KEY", "wxyz-5678-ZZZZ");
        SecretValues previous2 = valuesOf("OLD_KEY", "ABCD-1234-wxyz-5678");
        assertThat(replaceSet2.replacement("X = 'ABCD-1234-wxyz-5678-ZZZZ'").notFullyReplaced(previous2)).containsExactly("OLD_KEY");
    }

    @Test
    @DisplayName("两个都要替换的值在文本里互相重叠：靠左的先换，被抢掉的那个剩下的部分会原样留下——报被抢掉的那个")
    void twoReplacedValuesThatOverlapInTheTextLeaveTheSecondOneBehind() {
        SecretValues both = valuesOf("KEY_A", "xxxx-AAAA-1111", "KEY_B", "AAAA-1111-yyyy");
        SecretValues.Replacement r = both.replacement("v = 'xxxx-AAAA-1111-yyyy'");
        assertThat(r.text()).isEqualTo("v = '{{KEY_A}}-yyyy'");
        assertThat(r.notFullyReplaced(both)).containsExactly("KEY_B");
        // 各自单独出现时，都整段被换掉
        assertThat(both.replacement("a xxxx-AAAA-1111 b AAAA-1111-yyyy c").notFullyReplaced(both)).isEmpty();
    }

    @Test
    @DisplayName("★ 当前值包含旧值（旧 sk-abc12345，新 Bearer sk-abc12345）：旧值的每个字符都在新值的替换区间里，不算；旧值单独出现在区间之外才算")
    void aPreviousValueInsideACurrentValueIsFine() {
        SecretValues replaceSet = valuesOf("AUTH_HEADER", "Bearer sk-abc12345");
        SecretValues previous = valuesOf("API_KEY", "sk-abc12345");
        assertThat(replaceSet.replacement("h = 'Bearer sk-abc12345'").notFullyReplaced(previous)).isEmpty();
        assertThat(replaceSet.replacement("h = 'Bearer sk-abc12345' and k = 'sk-abc12345'").notFullyReplaced(previous)).containsExactly("API_KEY");
    }

    @Test
    @DisplayName("同一个值有两个名字（key 改了名、值没变）：按当前的名字换，旧名字不算残留")
    void aRenamedKeyIsNotResidue() {
        SecretValues replaceSet = valuesOf("NEW_NAME", AWS_ID);
        SecretValues previous = valuesOf("OLD_NAME", AWS_ID);
        SecretValues.Replacement r = replaceSet.replacement("id = '" + AWS_ID + "'");
        assertThat(r.text()).isEqualTo("id = '{{NEW_NAME}}'");
        assertThat(r.notFullyReplaced(previous)).isEmpty();
    }

    @Test
    @DisplayName("没命中、没有值、空文本：没有残留；text() 是原来的同一个对象")
    void nothingToReport() {
        SecretValues v = valuesOf("AK_ID", AWS_ID);
        String text = "没有任何 key 的文字";
        assertThat(v.replacement(text).text()).isSameAs(text);
        assertThat(v.replacement(text).notFullyReplaced(valuesOf("AWS_CRED", AWS_COMBINED))).isEmpty();
        assertThat(SecretValues.NONE.replacement(text).notFullyReplaced(v)).isEmpty();
        assertThat(SecretValues.NONE.replacement("x " + AWS_ID).notFullyReplaced(v)).containsExactly("AK_ID");   // 没有任何替换：每一处出现都在区间之外
        assertThat(v.replacement("").notFullyReplaced(v)).isEmpty();
        assertThat(v.replacement(text).notFullyReplaced(SecretValues.NONE)).isEmpty();
    }

    @Test
    @DisplayName("★ 随机对拍：替换集合是已知集合的一个子集，值互相重叠、互为前缀、文本只由 a b 组成——与朴素写法（先选区间、再逐处核对）逐条一致；text() 与 replace() 一致")
    void theCoverageCheckAgreesWithTheNaiveVersion() {
        Random rnd = new Random(31);
        int flagged = 0;
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = 10 + rnd.nextInt(110); n > 0; n--) text.append(rnd.nextBoolean() ? 'a' : 'b');
            List<SecretValues.Item> known = new ArrayList<>();
            String base = randomAb(rnd, 8 + rnd.nextInt(5));
            known.add(new SecretValues.Item("K_BASE", base));
            if (rnd.nextInt(3) > 0) known.add(new SecretValues.Item("K_LONGER", base + randomAb(rnd, 1 + rnd.nextInt(6))));
            if (rnd.nextInt(3) > 0) known.add(new SecretValues.Item("K_OVERLAP", base.substring(3) + randomAb(rnd, 3 + rnd.nextInt(6))));
            if (rnd.nextInt(3) > 0) known.add(new SecretValues.Item("K_OTHER", randomAb(rnd, 8 + rnd.nextInt(7))));
            if (rnd.nextInt(4) == 0) {
                int from = rnd.nextInt(text.length() - 8);
                known.add(new SecretValues.Item("K_INSIDE", text.substring(from, from + 4 + rnd.nextInt(4)).repeat(2)));
            }
            List<SecretValues.Item> replace = new ArrayList<>();
            for (SecretValues.Item it : known) if (rnd.nextInt(3) > 0) replace.add(it);   // 其余的是「上一版里删掉了的」
            SecretValues replaceSet = SecretValues.of(replace);
            SecretValues.Replacement r = replaceSet.replacement(text.toString());

            assertThat(r.text()).as("替换 %s 文本 %s", replace, text).isEqualTo(replaceSet.replace(text.toString()));
            List<String> expected = naiveNotFullyReplaced(replace, known, text.toString());
            assertThat(r.notFullyReplaced(SecretValues.of(known))).as("替换 %s 已知 %s 文本 %s", replace, known, text)
                    .containsExactlyInAnyOrderElementsOf(expected);
            if (!expected.isEmpty()) flagged++;
        }
        assertThat(flagged).as("生成器真的产生了不少「剩下一截」的情形（实测一千出头）").isGreaterThan(500);
    }

    /**
     * 参照写法：先按最左、同位置更长的规则选出替换区间，把被盖住的字符标出来；已知集合里每个值的每一处出现（含互相重叠的），
     * 只要有一个字符没被盖住，就算「没有被替换盖满」。
     */
    private static List<String> naiveNotFullyReplaced(List<SecretValues.Item> replaceItems, List<SecretValues.Item> knownItems, String text) {
        List<SecretValues.Item> sorted = new ArrayList<>();
        for (SecretValues.Item it : replaceItems) if (it.value().length() >= SecretValues.MIN_VALUE_LENGTH) sorted.add(it);
        sorted.sort((a, b) -> Integer.compare(b.value().length(), a.value().length()));
        boolean[] covered = new boolean[text.length()];
        int pos = 0;
        while (true) {
            int bestAt = -1;
            SecretValues.Item best = null;
            for (SecretValues.Item it : sorted) {
                int at = text.indexOf(it.value(), pos);
                if (at >= 0 && (bestAt < 0 || at < bestAt)) {
                    bestAt = at;
                    best = it;
                }
            }
            if (best == null) break;
            for (int c = bestAt; c < bestAt + best.value().length(); c++) covered[c] = true;
            pos = bestAt + best.value().length();
        }
        List<String> names = new ArrayList<>();
        for (SecretValues.Item k : knownItems) {
            if (k.value().length() < SecretValues.MIN_VALUE_LENGTH || names.contains(k.name())) continue;
            search:
            for (int at = text.indexOf(k.value()); at >= 0; at = text.indexOf(k.value(), at + 1)) {
                for (int c = at; c < at + k.value().length(); c++) {
                    if (!covered[c]) {
                        names.add(k.name());
                        break search;
                    }
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("★ 核对是线性的：3 MB 的文本里同一个值出现三十五万次、每一次都是独立的一片替换（中间隔着一个字符），逐处核对不能每次都从头数这三十五万片（那是六百多亿次比较）；"
            + "2 秒内完成（新实现约 0.05 s；每次从头数的写法要十几秒）")
    void theCoverageCheckIsLinear() {
        SecretValues replaceSet = valuesOf("ONLY_KEY", "ABCDEFGH");
        String text = "ABCDEFGH-".repeat(350_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            SecretValues.Replacement r = replaceSet.replacement(text);
            assertThat(r.notFullyReplaced(replaceSet)).isEmpty();   // 每一处出现都整段落在自己的那一片里
            assertThat(r.text()).startsWith("{{ONLY_KEY}}-{{ONLY_KEY}}-");
        });
    }

    @Test
    @DisplayName("判的是「这一处出现的每个字符是否都被某次替换盖住」：相接的两个区间算一整片（8 个 0 的值，写了 16 个 0 → 两个整段相接，跨在两段上的出现也整个被盖住，不报）；"
            + "写了 12 个 0 → 剩下 4 个 0 是值的一截，报")
    void adjacentReplacementsCountAsOneCoveredRun() {
        SecretValues v = valuesOf("ZERO_KEY", "00000000");
        assertThat(v.replacement("n = 00000000").notFullyReplaced(v)).isEmpty();
        assertThat(v.replacement("n = 0000000000000000").notFullyReplaced(v)).isEmpty();
        assertThat(v.replacement("n = 000000000000").notFullyReplaced(v)).containsExactly("ZERO_KEY");
        assertThat(v.replacement("n = 000000000").notFullyReplaced(v)).containsExactly("ZERO_KEY");   // 9 个：剩 1 个

        // 一个旧值正好跨在两个相接的当前值上：字符全被盖住，没有剩下的
        SecretValues replaceSet = valuesOf("KEY_A", "AAAAAAAA1111", "KEY_B", "2222BBBBBBBB");
        SecretValues previous = valuesOf("OLD_KEY", "11112222");
        assertThat(replaceSet.replacement("x AAAAAAAA11112222BBBBBBBB y").notFullyReplaced(previous)).isEmpty();
        assertThat(replaceSet.replacement("x AAAAAAAA11112222BBBBBBBB y").text()).isEqualTo("x {{KEY_A}}{{KEY_B}} y");
        // 中间隔了一个字符就不是一片了
        assertThat(replaceSet.replacement("x AAAAAAAA1111-2222BBBBBBBB y").notFullyReplaced(valuesOf("OLD_KEY", "1111-2222"))).containsExactly("OLD_KEY");
    }
    @Test
    void boundedBytesAndUtf8Longest() {
        SecretValues v = SecretValues.of(java.util.Map.of("K" + "X".repeat(63), "exactly8"));
        SecretValues.ByteReplacement out = v.replaceBytes("exactly8".repeat(1000).getBytes(StandardCharsets.UTF_8), 100);
        assertThat(out.bytes().length).isLessThanOrEqualTo(100);
        assertThat(out.stopped()).isTrue();
        byte[] binary = new byte[]{0, (byte)255, 1};
        assertThat(v.replaceBytes(binary)).isSameAs(binary);
        SecretValues utf = SecretValues.of(java.util.Map.of("A", "界".repeat(8), "B", "abcdefghijklmnopqrst"));
        assertThat(new String(utf.replaceBytes(("界".repeat(8) + "abcdefghijklmnopqrst").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)).isEqualTo("{{A}}{{B}}");
    }

    @Test
    void strictBytesAgreeWithNaiveUnionAndRemainBounded() {
        Random random = new Random(19);
        for (int run = 0; run < 3000; run++) {
            String text = randomAb(random, 150);
            List<SecretValues.Item> items = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                int start = random.nextInt(120), length = 8 + random.nextInt(10);
                String value = text.substring(start, start + length);
                items.add(new SecretValues.Item("K_" + k, value));
                for (int pos = text.indexOf(value); pos >= 0; pos = text.indexOf(value, pos + 1)) ranges.add(new int[]{pos, pos + value.length()});
            }
            ranges.sort(java.util.Comparator.comparingInt(r -> r[0]));
            StringBuilder expected = new StringBuilder();
            int copied = 0, left = ranges.get(0)[0], right = ranges.get(0)[1];
            for (int i = 1; i <= ranges.size(); i++) {
                if (i < ranges.size() && ranges.get(i)[0] < right) { right = Math.max(right, ranges.get(i)[1]); continue; }
                expected.append(text, copied, left).append('*'); copied = right;
                if (i < ranges.size()) { left = ranges.get(i)[0]; right = ranges.get(i)[1]; }
            }
            expected.append(text.substring(copied));
            SecretValues v = SecretValues.of(items);
            byte[] input = text.getBytes(StandardCharsets.UTF_8);
            String actual = new String(v.scrubBytes(input, Integer.MAX_VALUE).bytes(), StandardCharsets.UTF_8);
            assertThat(actual.replaceAll("\\{\\{K_[0-9]+}}", "*")).isEqualTo(expected.toString());
            int cap = random.nextInt(140);
            SecretValues.ByteReplacement bounded = v.scrubBytes(input, cap);
            assertThat(bounded.bytes().length).isLessThanOrEqualTo(cap);
            assertThat(v.namesIn(bounded.bytes())).isEmpty();
        }
        SecretValues composed = SecretValues.of(java.util.Map.of("TAIL", "abcdefgh", "COMPOSED", "x{{TAIL}}"));
        assertThat(composed.scrub("xabcdefgh")).isEmpty();
    }
}

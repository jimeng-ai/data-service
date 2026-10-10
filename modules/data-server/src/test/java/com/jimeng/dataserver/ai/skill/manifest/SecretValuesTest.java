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
    @DisplayName("★ 查找是线性的：2MB 的「aaa…」配「aaa…ab」不会拖住线程（朴素的 indexOf 要算上百亿次比较）")
    void searchIsLinear() {
        String hay = "a".repeat(2_000_000);
        SecretValues v = SecretValues.of(List.of(new SecretValues.Item("TRAP_KEY", "a".repeat(8191) + "b")));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
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
    @DisplayName("★ 64 个值配 5 MB 文本：替换、namesIn（文本、字节）各在 1 秒内")
    void sixtyFourValuesOverFiveMegabytesTakeUnderASecond() {
        Random rnd = new Random(11);
        List<SecretValues.Item> items = new ArrayList<>();
        StringBuilder text = new StringBuilder(5_300_000);
        for (int i = 0; i < 64; i++) {
            StringBuilder v = new StringBuilder("value" + i + "-");
            for (int n = 8 + rnd.nextInt(33); n > 0; n--) v.append((char) ('a' + rnd.nextInt(26)));
            items.add(new SecretValues.Item("KEY_" + i, v.toString()));
        }
        while (text.length() < 5_242_880) {
            for (int n = 400 + rnd.nextInt(600); n > 0; n--) text.append((char) ('a' + rnd.nextInt(26)));
            text.append(items.get(rnd.nextInt(64)).value());
        }
        String hay = text.toString();
        byte[] bytes = hay.getBytes(StandardCharsets.UTF_8);
        SecretValues v = SecretValues.of(items);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(v.replace(hay)).doesNotContain("value0-"));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(v.namesIn(hay)).hasSize(64));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(v.namesIn(bytes)).hasSize(64));
    }
}

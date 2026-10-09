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
    @DisplayName("★ 随机文本：替换之后任何一个值都不再整段出现；与朴素写法逐条一致")
    void noValueSurvivesAndAgreesWithTheNaiveVersion() {
        Random rnd = new Random(7);
        String alphabet = "abc ";
        for (int i = 0; i < 20_000; i++) {
            List<SecretValues.Item> items = new ArrayList<>();
            for (int k = 1 + rnd.nextInt(3); k > 0; k--) {
                StringBuilder v = new StringBuilder();
                for (int n = 2 + rnd.nextInt(4); n > 0; n--) v.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                items.add(new SecretValues.Item("K" + k, v.toString()));
            }
            StringBuilder text = new StringBuilder();
            for (int n = rnd.nextInt(30); n > 0; n--) text.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            SecretValues values = SecretValues.of(items);
            String out = values.replace(text.toString());
            assertThat(out).as("值 %s 文本 %s", items, text).isEqualTo(naive(items, text.toString()));
            for (SecretValues.Item it : items) assertThat(out).as("%s 在 %s 里", it, out).doesNotContain(it.value());
        }
    }

    /** 参照写法：每一步取最靠左的出现，同一位置取更长的（同样长的取靠前的），整段换掉，从它后面接着找。 */
    private static String naive(List<SecretValues.Item> items, String text) {
        List<SecretValues.Item> sorted = new ArrayList<>(items);
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
}

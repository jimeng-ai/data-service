package com.jimeng.dataserver.ai.skill.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSecretsFileTest {

    private static SkillSecretsFile.Parsed parse(String json) {
        return SkillSecretsFile.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("合法的一条：hosts 规范化、methods 转大写、paths 原样；toString 不带出值")
    void valid() {
        SkillSecretsFile.Parsed p = parse("{\"CRM_TOKEN\":{\"value\":\"sk-live-0123456789\",\"hosts\":[\"API.customer.com\"],"
                + "\"methods\":[\"get\",\"POST\"],\"paths\":[\"/v1/orders/**\"]}}");
        assertThat(p.errors()).isEmpty();
        SkillSecretsFile.Entry e = p.entries().get(0);
        assertThat(e.name()).isEqualTo("CRM_TOKEN");
        assertThat(e.hosts()).containsExactly("api.customer.com");
        assertThat(e.methods()).containsExactly("GET", "POST");
        assertThat(e.paths()).containsExactly("/v1/orders/**");
        assertThat(e.toString()).doesNotContain("sk-live");
        assertThat(SkillSecretsFile.decls(p.entries()).get(0).toString()).doesNotContain("sk-live");
    }

    @Test
    @DisplayName("没写 value = 沿用上一版的值（value 为 null）；methods / paths 不写 = 不限（null）")
    void valueMayBeOmitted() {
        SkillSecretsFile.Entry e = parse("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}").entries().get(0);
        assertThat(e.value()).isNull();
        assertThat(e.methods()).isNull();
        assertThat(e.paths()).isNull();
    }

    @Test
    @DisplayName("逐条报错：名字、hosts、值太短、值里有换行、不认识的字段、方法、路径；报错里不出现值")
    void errors() {
        SkillSecretsFile.Parsed p = parse("{"
                + "\"crm\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"]},"
                + "\"NO_HOSTS\":{\"value\":\"abcdefgh\"},"
                + "\"SHORT\":{\"value\":\"short7c\",\"hosts\":[\"a.example.com\"]},"
                + "\"NEWLINE\":{\"value\":\"abc\\r\\nX-Evil: 1\",\"hosts\":[\"a.example.com\"]},"
                + "\"EXTRA\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"],\"header\":\"X\"},"
                + "\"BAD_METHOD\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"],\"methods\":[\"FETCH\"]},"
                + "\"BAD_PATH\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"],\"paths\":[\"v1/x\"]},"
                + "\"BAD_HOST\":{\"value\":\"abcdefgh\",\"hosts\":[\"https://a.example.com/x\"]}}");
        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).anyMatch(e -> e.contains("crm"))
                .anyMatch(e -> e.contains("NO_HOSTS") && e.contains("hosts"))
                .anyMatch(e -> e.contains("SHORT") && e.contains("8"))
                .anyMatch(e -> e.contains("NEWLINE") && e.contains("换行"))
                .anyMatch(e -> e.contains("EXTRA") && e.contains("header"))
                .anyMatch(e -> e.contains("BAD_METHOD"))
                .anyMatch(e -> e.contains("BAD_PATH"))
                .anyMatch(e -> e.contains("BAD_HOST"));
        assertThat(String.join("", p.errors())).doesNotContain("abcdefgh").doesNotContain("short7c").doesNotContain("X-Evil");
    }

    @Test
    @DisplayName("不是 JSON 对象 / 超过 32 个 key 都报错")
    void shape() {
        assertThat(parse("[1]").errors()).isNotEmpty();
        assertThat(parse("{not json").errors()).isNotEmpty();
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            many.append(i == 0 ? "" : ",").append("\"K").append(i).append("\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"]}");
        }
        assertThat(parse(many.append("}").toString()).errors()).anyMatch(e -> e.contains("32"));
    }
    private static final String OLD = "sk-live-OLD-0123456789";
    private static final String NEW = "sk-live-NEW-9876543210";

    @Test
    @DisplayName("★ 同名 key（JSON 层面的重复键）一律拒绝：Jackson 默认静默只留最后一个，被吞掉的值既不加密也不替换，原文会原样进 bundle")
    void duplicateKeysAreRejected() {
        SkillSecretsFile.Parsed p = parse("{\"CRM_TOKEN\":{\"value\":\"" + OLD + "\",\"hosts\":[\"a.example.com\"]},"
                + "\"CRM_TOKEN\":{\"value\":\"" + NEW + "\",\"hosts\":[\"a.example.com\"]}}");
        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).anyMatch(e -> e.contains("重复"));
        assertThat(String.join("", p.errors())).doesNotContain(OLD).doesNotContain(NEW);

        SkillSecretsFile.Parsed inner = parse("{\"CRM_TOKEN\":{\"value\":\"" + OLD + "\",\"value\":\"" + NEW + "\",\"hosts\":[\"a.example.com\"]}}");
        assertThat(inner.valid()).isFalse();
        assertThat(String.join("", inner.errors())).doesNotContain(OLD).doesNotContain(NEW);
    }

    @Test
    @DisplayName("★ 第一个 JSON 对象后面还有内容一律拒绝：默认被静默忽略，后面那个对象里的 key 会悄悄丢掉")
    void trailingContentIsRejected() {
        String first = "{\"CRM_TOKEN\":{\"value\":\"" + OLD + "\",\"hosts\":[\"a.example.com\"]}}";
        String second = "{\"OTHER_KEY\":{\"value\":\"" + NEW + "\",\"hosts\":[\"b.example.com\"]}}";
        SkillSecretsFile.Parsed p = parse(first + "\n" + second);
        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).anyMatch(e -> e.contains("后面还有"));
        assertThat(String.join("", p.errors())).doesNotContain(OLD).doesNotContain(NEW);
        assertThat(parse(first + " 多余的文字").valid()).isFalse();
        assertThat(parse(first + "\n\n").valid()).isTrue();   // 结尾的空白不算
    }
    // ---------------------------------------------------------------- 值不能出现在声明里，回显的条目要抹值

    private static final String TOKEN = "sk-live-CRM-0123456789";

    @Test
    @DisplayName("★ 值写进了自己的 paths（webhook 写法）：拒绝，文案点名 key 和位置、教作者怎么写，不带值")
    void aValueInItsOwnPathsIsRejected() {
        SkillSecretsFile.Parsed p = parse("{\"HOOK_KEY\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"hook.example.com\"],"
                + "\"paths\":[\"/open-apis/bot/v2/hook/" + TOKEN + "\"]}}");
        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).singleElement().satisfies(e -> {
            assertThat(e).contains("key HOOK_KEY 的值出现在了它自己的 paths 里").contains("路径里用 * 通配").contains("{{HOOK_KEY}}");
        });
        assertThat(String.join("", p.errors())).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ 值出现在别的 key 的 hosts 里：拒绝；hosts 按小写比较（值里有大写，域名在库里一律是小写）")
    void aValueInAnotherKeysHostsIsRejectedCaseInsensitively() {
        String mixedCase = "AbCdEfGh1234";
        SkillSecretsFile.Parsed p = parse("{\"KEY_A\":{\"value\":\"" + mixedCase + "\",\"hosts\":[\"a.example.com\"]},"
                + "\"KEY_B\":{\"value\":\"another-value-1\",\"hosts\":[\"" + mixedCase.toLowerCase() + ".example.com\"]}}");
        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e).contains("key KEY_A 的值出现在了 key KEY_B 的 hosts 里"));
        assertThat(String.join("", p.errors()).toLowerCase()).doesNotContain(mixedCase.toLowerCase());
    }

    @Test
    @DisplayName("★ 两个 key 的值相同：拒绝，文案是「key A 和 key B 的值相同」，不带值")
    void twoKeysCannotShareAValue() {
        SkillSecretsFile.Parsed p = parse("{\"KEY_A\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"a.example.com\"]},"
                + "\"KEY_B\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"b.example.com\"]}}");
        assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e).contains("key KEY_A 和 key KEY_B 的值相同"));
        assertThat(String.join("", p.errors())).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ 回显作者写的东西（hosts、methods、paths 的条目、key 名、不认识的字段名）的报错，先用同一文件里的值抹一遍")
    void echoedEntriesAreScrubbed() {
        SkillSecretsFile.Parsed p = parse("{"
                + "\"BAD_HOST\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"https://a.example.com/" + TOKEN + "\"]},"
                + "\"BAD_PATH\":{\"value\":\"another-secret-1\",\"hosts\":[\"a.example.com\"],\"paths\":[\"v1/another-secret-1\"]},"
                + "\"BAD_METHOD\":{\"value\":\"third-secret-123\",\"hosts\":[\"a.example.com\"],\"methods\":[\"third-secret-123\"]},"
                + "\"EXTRA\":{\"value\":\"fourth-secret-12\",\"hosts\":[\"a.example.com\"],\"fourth-secret-12\":\"x\"}}");
        String all = String.join("\n", p.errors());
        assertThat(all).contains("https://a.example.com/{{BAD_HOST}}").contains("v1/{{BAD_PATH}}")
                .contains("「{{BAD_METHOD}}」").contains("{{EXTRA}}");
        assertThat(all).doesNotContain(TOKEN).doesNotContain("another-secret-1").doesNotContain("third-secret-123").doesNotContain("fourth-secret-12");
    }

    @Test
    @DisplayName("无效的条目里写的值也算「已知的值」（报错和预览都要抹）；JSON 都解析不了才是「不知道有哪些值」")
    void valuesOfInvalidEntriesAreStillKnown() {
        SkillSecretsFile.Parsed invalidEntry = parse("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\"},\"PLAIN_KEY\":\"plain-string-value\"}");
        assertThat(invalidEntry.valid()).isFalse();
        assertThat(invalidEntry.readable()).isTrue();
        assertThat(invalidEntry.values().names()).containsExactlyInAnyOrder("CRM_TOKEN", "PLAIN_KEY");
        assertThat(invalidEntry.values().replace("a " + TOKEN + " b plain-string-value")).isEqualTo("a {{CRM_TOKEN}} b {{PLAIN_KEY}}");
        assertThat(parse("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",").readable()).isFalse();
        assertThat(parse("[\"" + TOKEN + "\"]").readable()).isFalse();
    }

    @Test
    @DisplayName("★ 上限：每个 key 的 hosts 最多 32 条、paths 最多 64 条、每条 path 最长 512 字符、value 最长 8192 字符；刚好等于上限的放行；文案不带值")
    void limits() {
        String hosts33 = String.join(",", java.util.stream.IntStream.rangeClosed(1, 33).mapToObj(i -> "\"h" + i + ".example.com\"").toList());
        String hosts32 = String.join(",", java.util.stream.IntStream.rangeClosed(1, 32).mapToObj(i -> "\"h" + i + ".example.com\"").toList());
        String paths65 = String.join(",", java.util.stream.IntStream.rangeClosed(1, 65).mapToObj(i -> "\"/p" + i + "\"").toList());
        String paths64 = String.join(",", java.util.stream.IntStream.rangeClosed(1, 64).mapToObj(i -> "\"/p" + i + "\"").toList());
        String value = "v".repeat(8192);

        assertThat(parse("{\"K_1\":{\"value\":\"" + value + "\",\"hosts\":[" + hosts32 + "],\"paths\":[" + paths64 + ",\"/" + "a".repeat(511) + "\"]}}").errors())
                .as("刚好等于上限").anyMatch(e -> e.contains("paths 最多")).hasSize(1);   // 64 + 1 条 = 65 条
        assertThat(parse("{\"K_1\":{\"value\":\"" + value + "\",\"hosts\":[" + hosts32 + "],\"paths\":[" + paths64.substring(0, paths64.lastIndexOf(',')) + ",\"/" + "a".repeat(511) + "\"]}}").valid())
                .as("63 条 + 一条 512 字符的").isTrue();
        assertThat(parse("{\"K_1\":{\"value\":\"" + value + "v\",\"hosts\":[\"a.example.com\"]}}").errors())
                .singleElement().satisfies(e -> assertThat(e).contains("8192").doesNotContain("vvvvvvvv"));
        assertThat(parse("{\"K_1\":{\"value\":\"" + TOKEN + "\",\"hosts\":[" + hosts33 + "]}}").errors())
                .singleElement().satisfies(e -> assertThat(e).contains("hosts 最多 32 条").doesNotContain(TOKEN));
        assertThat(parse("{\"K_1\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"a.example.com\"],\"paths\":[" + paths65 + "]}}").errors())
                .singleElement().satisfies(e -> assertThat(e).contains("paths 最多 64 条"));
        assertThat(parse("{\"K_1\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"a.example.com\"],\"paths\":[\"/" + "a".repeat(512) + "\"]}}").errors())
                .singleElement().satisfies(e -> assertThat(e).contains("超过 512 个字符").doesNotContain("aaaaaaaa"));
    }

    @Test
    @DisplayName("预览用的 secrets.json：每个 value（以及直接写成字符串的值、不认识的字段里的字符串和数字）换成 ******，hosts / methods / paths 原样")
    void maskedPreview() {
        String preview = SkillSecretsFile.maskedPreview(("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"],"
                + "\"methods\":[\"GET\"],\"paths\":[\"/v1/**\"]},"
                + "\"PLAIN_KEY\":\"plain-string-value\","
                + "\"ODD_KEY\":{\"hosts\":[\"a.example.com\"],\"token\":\"odd-secret-value\",\"pin\":12345678}}").getBytes(StandardCharsets.UTF_8));
        assertThat(preview).doesNotContain(TOKEN).doesNotContain("plain-string-value").doesNotContain("odd-secret-value").doesNotContain("12345678");
        assertThat(preview).contains("api.customer.com").contains("GET").contains("/v1/**").contains("CRM_TOKEN").contains("PLAIN_KEY");
        assertThat(preview.split("\\*\\*\\*\\*\\*\\*", -1).length - 1).isEqualTo(4);   // value、字符串写法、token、pin
        // hosts 里万一有值，也先抹掉
        String leaky = SkillSecretsFile.maskedPreview(("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"" + TOKEN + ".example.com\"]}}").getBytes(StandardCharsets.UTF_8));
        assertThat(leaky).doesNotContain(TOKEN).contains("{{CRM_TOKEN}}");
    }

    @Test
    @DisplayName("读不出来的 secrets.json（不是合法 JSON、不是对象）没有预览：不知道哪些是值，一个字都不给")
    void maskedPreviewOfAnUnreadableFileIsNull() {
        assertThat(SkillSecretsFile.maskedPreview(("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",").getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(SkillSecretsFile.maskedPreview(("[\"" + TOKEN + "\"]").getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(SkillSecretsFile.maskedPreview(new byte[0])).isNull();
    }

    @Test
    @DisplayName("无值声明：不带 value；不限的 methods / paths 省略（不是 []）；写出来的东西重新读回来，声明一字不差、value 为 null")
    void valueLessJsonRoundTrips() {
        List<SkillManifest.KeyDecl> decls = List.of(
                new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com", "*.cdn.example.com"), List.of("GET", "POST"), List.of("/v1/orders/**")),
                new SkillManifest.KeyDecl("OPEN_KEY", List.of("x.example.com"), null, null));
        String json = SkillSecretsFile.valueLessJson(decls);

        assertThat(json).doesNotContain("value").doesNotContain("[]");
        SkillSecretsFile.Parsed back = SkillSecretsFile.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(back.valid()).as(back.errors().toString()).isTrue();
        assertThat(SkillSecretsFile.decls(back.entries())).isEqualTo(decls);
        assertThat(back.entries()).allSatisfy(e -> assertThat(e.value()).isNull());
        assertThat(SkillSecretsFile.valueLessJson(List.of())).isEqualTo("{ }\n");
    }
}

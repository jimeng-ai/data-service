package com.jimeng.dataserver.ai.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholdersTest {

    @Test
    @DisplayName("网址、请求头的值、请求体里的占位符都能找到；原样的和 %7B%7B…%7D%7D 都算；请求头名字和小写名字不算")
    void finds() {
        assertThat(Placeholders.find("https://a.example.com/x/%7B%7BPATH_KEY%7D%7D?t={{QUERY_KEY}}",
                List.of(Map.entry("Authorization", "Bearer {{HDR_KEY}}"), Map.entry("{{NOT_A_VALUE}}", "x")),
                "{\"k\":\"{{BODY_KEY}}\",\"f\":\"%7b%7bFORM_KEY%7d%7d\",\"l\":\"{{lower}}\"}".getBytes(StandardCharsets.UTF_8)))
                .containsExactlyInAnyOrder("PATH_KEY", "QUERY_KEY", "HDR_KEY", "BODY_KEY", "FORM_KEY");
    }

    @Test
    @DisplayName("网址里换成严格百分号编码后的值（值里的 / + & = 不会改变网址结构）；请求头换成原值")
    void substitutesUrlAndHeaders() {
        Map<String, String> v = Map.of("K_1", "a/b+c&d=e");
        assertThat(Placeholders.substituteUrl("https://x.example.com/p/%7B%7BK_1%7D%7D?t={{K_1}}", v))
                .isEqualTo("https://x.example.com/p/a%2Fb%2Bc%26d%3De?t=a%2Fb%2Bc%26d%3De");
        assertThat(Placeholders.substituteHeaders(List.of(Map.entry("X-Key", "pre-{{K_1}}")), v))
                .containsExactly(Map.entry("X-Key", "pre-a/b+c&d=e"));
    }

    @Test
    @DisplayName("请求体按字节替换：中文值不乱码，二进制正文的其余字节一个不动")
    void substitutesBodyBytes() {
        byte[] body = new byte[]{0, (byte) 0xff, '{', '{', 'K', '_', '1', '}', '}', 1};
        byte[] out = Placeholders.substituteBody(body, Map.of("K_1", "密钥abc"));
        byte[] value = "密钥abc".getBytes(StandardCharsets.UTF_8);
        assertThat(out).startsWith((byte) 0, (byte) 0xff).endsWith((byte) 1);
        assertThat(new String(out, 2, value.length, StandardCharsets.UTF_8)).isEqualTo("密钥abc");
    }
    @Test
    void insertedCredentialPlaceholdersAreOpaque() {
        Map<String,String> values = new java.util.LinkedHashMap<>();
        values.put("FIRST_KEY", "{{OTHER_KEY}}");
        values.put("OTHER_KEY", "actual-credential");
        String input = "{{FIRST_KEY}}/{{OTHER_KEY}}";
        assertThat(Placeholders.substituteUrl(input, values)).isEqualTo("%7B%7BOTHER_KEY%7D%7D/actual-credential");
        assertThat(Placeholders.substituteHeaders(List.of(Map.entry("X-Key", input)), values))
            .containsExactly(Map.entry("X-Key", "{{OTHER_KEY}}/actual-credential"));
        assertThat(new String(Placeholders.substituteBody(input.getBytes(StandardCharsets.UTF_8), values), StandardCharsets.UTF_8))
            .isEqualTo("{{OTHER_KEY}}/actual-credential");
        assertThat(new String(Placeholders.substituteBody("%7b%7bFIRST_KEY%7d%7d/{{OTHER_KEY}}".getBytes(StandardCharsets.UTF_8), values), StandardCharsets.UTF_8))
            .isEqualTo("%7B%7BOTHER_KEY%7D%7D/actual-credential");
    }

    @Test
    void boundedSubstitutionCountsUtf8AndPreservesBinary() {
        byte[] body = new byte[]{0, (byte)255, '{', '{', 'K', '}', '}', 1};
        Map<String,String> values = Map.of("K", "密钥abc");
        byte[] expected = Placeholders.substituteBody(body, values);
        assertThat(Placeholders.substituteBody(body, values, expected.length)).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Placeholders.substituteBody(body, values, expected.length - 1)).isInstanceOf(Placeholders.BodyTooLarge.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Placeholders.substituteBody("{{K}}".repeat(1024).getBytes(StandardCharsets.UTF_8), Map.of("K", "a".repeat(8192)), 1024 * 1024)).isInstanceOf(Placeholders.BodyTooLarge.class);
    }
}

package com.jimeng.dataserver.ai.skill.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}

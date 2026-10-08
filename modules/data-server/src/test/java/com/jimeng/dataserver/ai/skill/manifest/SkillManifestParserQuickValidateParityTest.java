package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 严格模式与 skill-creator 的 quick_validate.py 对拍（设计文档 §8.1 第 5 条）。
 * 用例里的 quickValidate 由 scripts/check-quick-validate-parity.py 用真的 quick_validate.py 核对过；
 * 这里保证我们的严格模式和那份结论一致，只有标了平台规则的用例允许不同。
 */
class SkillManifestParserQuickValidateParityTest {

    /** 测试工作目录是 modules/data-server。 */
    private static final Path CASES = Path.of("src/test/resources/skill-manifest/quick-validate-cases.json");

    /** 允许与 quick_validate.py 结论不同的只有平台自己的硬约束。新加一类之前，先想清楚它为什么不能照 quick_validate 来。 */
    private static final List<String> PLATFORM_RULE_PREFIXES =
            List.of("name 不能为空", "description 不能为空", "metadata.jm");

    /*
     * data-server 解析到的 jackson-databind 是 2.11.1（父 pom 里先 import 的 redisson 3.13.4 BOM 把 Spring Boot 的 2.14.1 压住了），
     * 2.11 不认 record：不加注解会报 no Creators。所以每个组件都写明 JSON 名，并在紧凑构造器上标 @JsonCreator；
     * Jackson 升到 2.12 以上之后这套写法照样能用。
     */
    record Fill(@JsonProperty("char") String ch, @JsonProperty("count") int count) {
        @JsonCreator
        Fill {}
    }

    record Case(@JsonProperty("id") String id, @JsonProperty("md") String md, @JsonProperty("template") String template,
                @JsonProperty("fill") Fill fill, @JsonProperty("quickValidate") String quickValidate,
                @JsonProperty("jm") String jm, @JsonProperty("platformRule") String platformRule) {
        @JsonCreator
        Case {}

        String content() {
            return md != null ? md : template.replace("@@FILL@@", fill.ch().repeat(fill.count()));
        }
    }

    static List<Case> cases() throws IOException {
        return new ObjectMapper().readValue(CASES.toFile(), new TypeReference<>() {});
    }

    @TestFactory
    @DisplayName("严格模式与 quick_validate.py 逐条一致（不一致的只能是标了平台规则的用例）")
    Stream<DynamicTest> 严格模式与quickValidate一致() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            ParsedSkill p = SkillManifestParser.parse(c.content(), SkillManifestParser.Mode.STRICT);
            assertThat(p.valid()).as(c.id() + " 的错误：" + p.errors()).isEqualTo("valid".equals(c.jm()));
            if (c.jm().equals(c.quickValidate())) {
                assertThat(c.platformRule()).as(c.id() + " 与 quick_validate.py 一致，不该标平台规则").isNull();
                return;
            }
            assertThat(c.platformRule()).as(c.id() + " 和 quick_validate.py 结论不同，必须标出是哪条平台规则").isNotBlank();
            assertThat(PLATFORM_RULE_PREFIXES).as(c.id()).anyMatch(prefix -> c.platformRule().startsWith(prefix));
            assertThat(p.errors()).as(c.id()).anyMatch(e -> e.contains(c.platformRule()));
        }));
    }

    @Test
    @DisplayName("用例文件读得到、覆盖了两种结论和两条平台规则（工作目录不对时读到空集会白白通过）")
    void 用例覆盖面() throws IOException {
        List<Case> cs = cases();
        assertThat(cs).hasSizeGreaterThanOrEqualTo(38);
        assertThat(cs).extracting(Case::platformRule).contains("name 不能为空", "description 不能为空");
        assertThat(cs).anyMatch(c -> "invalid".equals(c.quickValidate()));
        assertThat(cs).anyMatch(c -> "valid".equals(c.quickValidate()));
    }
}

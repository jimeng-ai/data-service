package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.manifest.JmMetadata;
import com.jimeng.dataserver.ai.skill.manifest.ParsedSkill;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库里的平台 skill 就是出厂版本（设计文档 §6.2）。它们在 CI 里先过一遍严格校验：
 * 加载器遇到不合规的出厂版本只会跳过并报错（不再让 data-server 起不来），不能等线上才发现某个 skill 没了。
 */
class PlatformSkillsManifestTest {

    /** 测试工作目录是 modules/data-server。 */
    private static final Path SKILLS_DIR = Path.of("skills");

    static List<Path> skillDirs() throws IOException {
        try (Stream<Path> s = Files.list(SKILLS_DIR)) {
            return s.filter(d -> Files.isRegularFile(d.resolve("SKILL.md"))).sorted().toList();
        }
    }

    static ParsedSkill parse(Path dir) throws IOException {
        return SkillManifestParser.parse(Files.readString(dir.resolve("SKILL.md"), StandardCharsets.UTF_8),
                SkillManifestParser.Mode.STRICT);
    }

    @Test
    @DisplayName("扫描范围非空：工作目录不对时一个都扫不到，下面的检查会白白通过")
    void 扫描范围非空() throws IOException {
        assertThat(skillDirs()).extracting(d -> d.getFileName().toString())
                .contains("connector", "rag-knowledge", "design-system");
    }

    @TestFactory
    @DisplayName("每一份出厂 SKILL.md 都过严格校验，且 name 与目录名一致")
    Stream<DynamicTest> 出厂版本都过严格校验() throws IOException {
        return skillDirs().stream().map(d -> DynamicTest.dynamicTest(d.getFileName().toString(), () -> {
            ParsedSkill p = parse(d);
            assertThat(p.errors()).as(d + "/SKILL.md").isEmpty();
            assertThat(p.name()).isEqualTo(d.getFileName().toString());
        }));
    }

    @TestFactory
    @DisplayName("出厂版本不带 secrets.json：key 不能进代码仓库（设计文档 §3.4 第 5 条）")
    Stream<DynamicTest> 出厂版本不带secrets() throws IOException {
        return skillDirs().stream().map(d -> DynamicTest.dynamicTest(d.getFileName().toString(),
                () -> assertThat(d.resolve("secrets.json")).as(d + "/secrets.json").doesNotExist()));
    }

    @TestFactory
    @DisplayName("S1 的平台 skill 不声明 hosts / web：平台 skill 的 key 要等 S2b，现在写了不会生效")
    Stream<DynamicTest> 平台skill暂不声明HTTP() throws IOException {
        return skillDirs().stream().map(d -> DynamicTest.dynamicTest(d.getFileName().toString(), () -> {
            JmMetadata jm = parse(d).jm();
            assertThat(jm.hosts()).as(d + " 的 metadata.jm.hosts").isEmpty();
            assertThat(jm.web()).as(d + " 的 metadata.jm.web").isFalse();
        }));
    }
}

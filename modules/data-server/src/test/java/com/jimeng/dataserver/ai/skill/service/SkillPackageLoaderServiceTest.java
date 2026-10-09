package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackageLoaderServiceTest {

    @TempDir
    Path root;

    private SkillPackageLoaderService loaderAt(Path dir) {
        SkillPackageLoaderService svc = new SkillPackageLoaderService();
        ReflectionTestUtils.setField(svc, "skillRootDir", dir.toString());
        return svc;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("一份写错的出厂 SKILL.md 只让它自己被跳过，其余照常加载（以前 requires 写错会让 data-server 起不来）")
    void invalidSkillIsSkippedNotFatal() throws IOException {
        write(root.resolve("good/SKILL.md"), "---\nname: good\ndescription: d\nmetadata:\n  jm:\n    requires: connections\n---\n正文\n");
        write(root.resolve("bad/SKILL.md"), "---\nname: bad\ndescription: d\nmetadata:\n  jm:\n    requires: connection\n---\n正文\n");
        Map<String, SkillPackage> loaded = loaderAt(root).loadSkillPackages();
        assertThat(loaded).containsOnlyKeys("good");
        assertThat(loaded.get("good").getRequires()).isEqualTo(SkillRequirement.CONNECTIONS);
    }

    @Test
    @DisplayName("平台上传走严格校验：顶层多一个键就拒绝，目录里什么都不写")
    void uploadIsStrict() {
        MockMultipartFile f = new MockMultipartFile("file", "SKILL.md", "text/markdown",
                "---\nname: x-y\ndescription: d\nrequires: connections\n---\n正文\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> loaderAt(root).uploadSkillMarkdown(f, false))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不允许的键");
        assertThat(root.resolve("x-y")).doesNotExist();
    }

    @Test
    @DisplayName("平台上传：只有 frontmatter、正文为空的 SKILL.md 被拒绝（严格模式本身不查正文，这是唯一的守卫），目录里什么都不写")
    void uploadRejectsEmptyBody() {
        MockMultipartFile f = new MockMultipartFile("file", "SKILL.md", "text/markdown",
                "---\nname: x-y\ndescription: d\n---\n\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> loaderAt(root).uploadSkillMarkdown(f, false))
                .isInstanceOf(ServiceException.class).hasMessageContaining("正文不能为空");
        assertThat(root.resolve("x-y")).doesNotExist();
    }
}

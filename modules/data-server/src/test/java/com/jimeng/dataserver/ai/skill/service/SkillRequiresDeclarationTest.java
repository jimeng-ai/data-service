package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 磁盘上那几份 SKILL.md 的 {@code requires:} 声明。
 *
 * <h3>为什么这件事需要一个测试盯着</h3>
 * 「这个 Skill 什么时候该出现」现在是<b>一行 markdown</b> 说了算。漏写这一行<b>不会报错</b>：
 * skill 照常加载、照常出现在发现列表里、模型照常能靠 {@code activate_skills} 用上它——
 * 只是每轮多两次往返、且每轮重赌一次。这正是本特性要修掉的那个 bug 的形状，
 * 而它<b>会以完全相同的方式悄悄回来</b>：谁把这行删了、或新加一个平台 Skill 时忘了写，
 * 线上没有任何信号。
 *
 * <p>与 {@code JimengTenantLineHandler.TENANT_AWARE_TABLES + 单测断言} 同一个模式：
 * 配置项漏登记不报错，就拿测试当那个报错。
 */
class SkillRequiresDeclarationTest {

    /** skills 目录相对模块根；测试工作目录是 modules/data-server。 */
    private static final Path SKILLS_DIR = Path.of("skills");

    @Test
    @DisplayName("connector 必须声明 requires: connections —— 漏了就退回走发现，且不报错")
    void connectorDeclaresConnections() throws IOException {
        assertEquals(SkillRequirement.CONNECTIONS, requiresOf("connector"),
                "skills/connector/SKILL.md 的 frontmatter 必须是 requires: connections");
    }

    @Test
    @DisplayName("rag-knowledge 必须声明 requires: knowledge_bases —— 它原来靠运行时硬编码，现在靠这一行")
    void ragKnowledgeDeclaresKnowledgeBases() throws IOException {
        assertEquals(SkillRequirement.KNOWLEDGE_BASES, requiresOf("rag-knowledge"),
                "skills/rag-knowledge/SKILL.md 的 frontmatter 必须是 requires: knowledge_bases。"
                        + "这段判断原本硬编码在 SkillRuntimeService 里，删掉硬编码之后它唯一的落点就是这一行");
    }

    @Test
    @DisplayName("没有前置资源的 Skill 不写 requires（gaode-poi 是这一类的样例）")
    void skillsWithoutResourcesDeclareNothing() throws IOException {
        assumeTrue(Files.isDirectory(SKILLS_DIR.resolve("gaode-poi")), "gaode-poi 不在，跳过");
        assertEquals(null, requiresOf("gaode-poi"),
                "gaode-poi 不依赖 Agent 绑定任何资源，不该声明 requires");
    }

    @Test
    @DisplayName("每一份 SKILL.md 的 requires 都必须是认得出来的值（拼错会被 parse 当场抛）")
    void everySkillOnDiskParses() throws IOException {
        assumeTrue(Files.isDirectory(SKILLS_DIR), "skills 目录不在，跳过");
        try (var dirs = Files.list(SKILLS_DIR)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path md = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                // parse 对认不出来的值会抛 ServiceException；这里不 catch，让它把文件名带出来。
                try {
                    requiresOf(dir.getFileName().toString());
                } catch (RuntimeException e) {
                    throw new AssertionError("skills/" + dir.getFileName() + "/SKILL.md 的 requires 非法: "
                            + e.getMessage(), e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 直接读磁盘文件解析 frontmatter，<b>不经过 SkillPackageLoaderService</b>。
     *
     * <p>刻意绕开加载器：本用例要断言的是「磁盘上那一行写没写对」，
     * 走加载器的话，加载器自己的一个 bug（比如把 requires 读丢了）会让这个测试跟着一起绿。
     */
    private static SkillRequirement requiresOf(String skillName) throws IOException {
        Path md = SKILLS_DIR.resolve(skillName).resolve("SKILL.md");
        assertTrue(Files.isRegularFile(md), "找不到 " + md.toAbsolutePath());
        String text = Files.readString(md, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.startsWith("---\n"), skillName + " 的 SKILL.md 缺 frontmatter");
        int end = text.indexOf("\n---\n", 4);
        assertTrue(end > 0, skillName + " 的 frontmatter 没有闭合");

        Map<String, String> fm = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            fm.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
        }
        assertNotNull(fm.get("name"), skillName + " 的 frontmatter 缺 name");
        return SkillRequirement.parse(fm.get("requires"));
    }
}

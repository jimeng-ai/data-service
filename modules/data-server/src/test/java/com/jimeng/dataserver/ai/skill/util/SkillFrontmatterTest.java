package com.jimeng.dataserver.ai.skill.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布关口的 frontmatter 规则 = skill-creator quick_validate.py，逐条对照。 */
class SkillFrontmatterTest {

    private static SkillFrontmatter.Parsed p(String fm, String body) {
        return SkillFrontmatter.parse("---\n" + fm + "\n---\n\n" + body);
    }

    private static boolean hasError(SkillFrontmatter.Parsed r, String fragment) {
        return r.errors().stream().anyMatch(e -> e.contains(fragment));
    }

    @Test
    @DisplayName("合规的 SKILL.md：取出 name / description / 正文")
    void valid() {
        SkillFrontmatter.Parsed r = p("name: csv-to-xlsx\ndescription: 把 csv 转成 Excel。用户提到表格转换时使用。\nlicense: MIT", "# 用法\n步骤一");
        assertTrue(r.valid(), String.valueOf(r.errors()));
        assertEquals("csv-to-xlsx", r.name());
        assertEquals("把 csv 转成 Excel。用户提到表格转换时使用。", r.description());
        assertEquals("# 用法\n步骤一", r.body());
    }

    @Test
    @DisplayName("六个允许的键都放行；多出来的键报错")
    void allowedKeys() {
        assertTrue(p("name: a\ndescription: d\nlicense: x\nallowed-tools: Read\nmetadata:\n  k: v\ncompatibility: py3", "b").valid());
        SkillFrontmatter.Parsed r = p("name: a\ndescription: d\nversion: 2\nauthor: me", "b");
        assertTrue(hasError(r, "author, version"), String.valueOf(r.errors()));
    }

    @Test
    @DisplayName("name：必填、kebab-case、连字符规则、≤64")
    void nameRules() {
        assertTrue(hasError(p("description: d", "b"), "缺少 name"));
        assertTrue(hasError(p("name: CsvTool\ndescription: d", "b"), "kebab-case"));
        assertTrue(hasError(p("name: csv_tool\ndescription: d", "b"), "kebab-case"));
        assertTrue(hasError(p("name: -csv\ndescription: d", "b"), "连字符"));
        assertTrue(hasError(p("name: csv--tool\ndescription: d", "b"), "连字符"));
        assertTrue(hasError(p("name: " + "a".repeat(65) + "\ndescription: d", "b"), "最多 64"));
        assertTrue(p("name: " + "a".repeat(64) + "\ndescription: d", "b").valid());
        assertTrue(hasError(p("name: 123\ndescription: d", "b"), "name 必须是字符串"));
    }

    @Test
    @DisplayName("description：必填、无尖括号、≤1024（按字符计，emoji 不算两个）")
    void descriptionRules() {
        assertTrue(hasError(p("name: a", "b"), "缺少 description"));
        assertTrue(hasError(p("name: a\ndescription: 用 <tag> 标记", "b"), "尖括号"));
        assertTrue(hasError(p("name: a\ndescription: " + "字".repeat(1025), "b"), "最多 1024"));
        assertTrue(p("name: a\ndescription: " + "字".repeat(1024), "b").valid());
        assertTrue(p("name: a\ndescription: " + "😀".repeat(1024), "b").valid(), "1024 个 emoji 是 1024 个字符");
    }

    @Test
    @DisplayName("compatibility：可选、≤500")
    void compatibility() {
        assertTrue(hasError(p("name: a\ndescription: d\ncompatibility: " + "x".repeat(501), "b"), "最多 500"));
        assertTrue(p("name: a\ndescription: d\ncompatibility: " + "x".repeat(500), "b").valid());
    }

    @Test
    @DisplayName("没有 frontmatter / 没闭合 / 不是字典 / YAML 语法错")
    void structure() {
        assertTrue(hasError(SkillFrontmatter.parse("# no frontmatter"), "缺少 YAML frontmatter"));
        assertTrue(hasError(SkillFrontmatter.parse("---\nname: a\n"), "找不到结尾"));
        assertTrue(hasError(SkillFrontmatter.parse("---\n- a\n- b\n---\nx"), "必须是 YAML 字典"));
        assertTrue(hasError(SkillFrontmatter.parse("---\nname: [a\n---\nx"), "不是合法的 YAML"));
    }

    @Test
    @DisplayName("多行 description（YAML 块标量）照常解析；CRLF 换行也认")
    void multilineAndCrlf() {
        SkillFrontmatter.Parsed r = SkillFrontmatter.parse("---\r\nname: a\r\ndescription: >\r\n  第一行\r\n  第二行\r\n---\r\n正文");
        assertTrue(r.valid(), String.valueOf(r.errors()));
        assertEquals("第一行 第二行", r.description());
        assertEquals("正文", r.body());
        assertFalse(r.body().startsWith("\n"));
    }
}

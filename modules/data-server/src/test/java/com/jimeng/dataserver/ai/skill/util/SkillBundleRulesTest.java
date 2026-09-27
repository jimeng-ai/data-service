package com.jimeng.dataserver.ai.skill.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打包 / 下发 / 运行方式三条规则。第三条必须与 jm-agent-sandbox vendor/skill-creator/scripts/run_eval.py 的
 * _has_bundled_files 一致——那边的同名用例在 test/run-eval-adapter-test.mjs 的「运行方式判定」一节。
 */
class SkillBundleRulesTest {

    @Test
    @DisplayName("缓存 / 依赖 / 系统垃圾不是 skill 的一部分（与 package_skill.py 同源）")
    void partOfSkill() {
        assertTrue(SkillBundleRules.isPartOfSkill("SKILL.md"));
        assertTrue(SkillBundleRules.isPartOfSkill("scripts/run.py"));
        assertTrue(SkillBundleRules.isPartOfSkill("evals/evals.json"));
        assertFalse(SkillBundleRules.isPartOfSkill("scripts/__pycache__/run.cpython-311.pyc"));
        assertFalse(SkillBundleRules.isPartOfSkill("node_modules/a/index.js"));
        assertFalse(SkillBundleRules.isPartOfSkill("scripts/run.pyc"));
        assertFalse(SkillBundleRules.isPartOfSkill("assets/.DS_Store"));
        assertFalse(SkillBundleRules.isPartOfSkill("../escape.txt"));
        assertFalse(SkillBundleRules.isPartOfSkill("a//b"));
        assertTrue(SkillBundleRules.isPartOfSkill("references/node_modules.md"), "只有目录名才算，文件名不算");
    }

    @Test
    @DisplayName("根目录 evals/ 存进 bundle，但不下发给任何运行（否则被测模型能读到答案）")
    void runtimeFiles() {
        assertFalse(SkillBundleRules.isRuntimeFile("evals/evals.json"));
        assertFalse(SkillBundleRules.isRuntimeFile("evals/files/sample.csv"));
        assertTrue(SkillBundleRules.isRuntimeFile("references/evals/notes.md"), "只排除根目录的 evals/");
        assertTrue(SkillBundleRules.isRuntimeFile("SKILL.md"));
    }

    @Test
    @DisplayName("运行方式：SKILL.md 之外还有附带文件 → DOER，否则 PROMPT")
    void inferType() {
        assertEquals("PROMPT", SkillBundleRules.inferSkillType(List.of("SKILL.md")));
        assertEquals("PROMPT", SkillBundleRules.inferSkillType(List.of("SKILL.md", "evals/evals.json", "evals/files/a.csv")));
        assertEquals("PROMPT", SkillBundleRules.inferSkillType(List.of("SKILL.md", "scripts/__pycache__/x.pyc")));
        assertEquals("DOER", SkillBundleRules.inferSkillType(List.of("SKILL.md", "scripts/run.py")));
        assertEquals("DOER", SkillBundleRules.inferSkillType(List.of("SKILL.md", "references/api.md")));
        assertEquals("DOER", SkillBundleRules.inferSkillType(List.of("SKILL.md", "assets/template.docx")));
    }
}

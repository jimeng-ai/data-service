package com.jimeng.dataserver.ai.skill.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区目录约定——与边车 skillBuilderContract.ts 写给模型的是同一份契约。 */
class SkillWorkspaceLayoutTest {

    @Test
    @DisplayName("工作区前缀形状满足边车的校验：相对、以 / 结尾、无 . / .. 段")
    void prefix() {
        String p = SkillWorkspaceLayout.workspacePrefix("skill-builder", "t_1", 2099066794181541890L);
        assertEquals("skill-builder/t_1/2099066794181541890/ws/", p);
        assertTrue(p.endsWith("/") && !p.startsWith("/") && !p.contains("/../") && !p.contains("/./"));
    }

    @Test
    @DisplayName("skill 目录只认 skill/<dir>/SKILL.md，工作区目录与更深的 SKILL.md 不算")
    void skillDir() {
        assertEquals("csv-to-xlsx", SkillWorkspaceLayout.skillDirOfSkillMd("skill/csv-to-xlsx/SKILL.md"));
        assertNull(SkillWorkspaceLayout.skillDirOfSkillMd("skill/csv-to-xlsx-workspace/SKILL.md"));
        assertNull(SkillWorkspaceLayout.skillDirOfSkillMd("skill/csv-to-xlsx-workspace/skill-snapshot/SKILL.md"));
        assertNull(SkillWorkspaceLayout.skillDirOfSkillMd("skill/csv-to-xlsx/references/SKILL.md"));
        assertNull(SkillWorkspaceLayout.skillDirOfSkillMd("other/csv/SKILL.md"));
    }

    @Test
    @DisplayName("评审页路径：skill/<dir>-workspace/iteration-N/review.html；反馈写在同目录")
    void review() {
        assertArrayEquals(new Object[]{"csv", 3}, SkillWorkspaceLayout.matchReview("skill/csv-workspace/iteration-3/review.html"));
        assertNull(SkillWorkspaceLayout.matchReview("skill/csv-workspace/iteration-3/eval-a/review.html"));
        assertNull(SkillWorkspaceLayout.matchReview("skill/csv/iteration-3/review.html"));
        assertEquals("skill/csv-workspace/iteration-3/feedback.json", SkillWorkspaceLayout.feedbackPath("csv", 3));
        assertEquals("skill/csv-workspace/description-optimization/report.html", SkillWorkspaceLayout.optimizationReportPath("csv"));
    }
}

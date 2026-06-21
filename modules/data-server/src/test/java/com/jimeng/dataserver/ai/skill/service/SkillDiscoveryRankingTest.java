package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.AiSkillToolPackage;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发现阶段 Skill 挑选：相关性排序 + 租户优先 + 上限截断。
 * 复现并守护「租户自有 Skill 被平台 Skill 挤出 max-selected、一直不被发现」的回归。
 */
class SkillDiscoveryRankingTest {

    private static ToolPackage skill(String name, String desc, String tenantId) {
        AiSkill s = new AiSkill();
        s.setName(name);
        s.setDescription(desc);
        s.setTenantId(tenantId);
        return new AiSkillToolPackage(s);
    }

    private static List<String> names(List<ToolPackage> pkgs) {
        return pkgs.stream().map(ToolPackage::getName).toList();
    }

    @Test
    void tokenizeMixesEnglishWordsAndCjkBigrams() {
        Set<String> t = SkillRuntimeService.tokenize("画图 image 提示词");
        assertTrue(t.contains("image"));
        assertTrue(t.contains("提示")); // bigram
        assertTrue(t.contains("示词")); // bigram
    }

    @Test
    void imageQuerySurfacesTenantImageSkillWithinLimit() {
        List<ToolPackage> all = List.of(
                skill("design-system", "Audit your design system components", null),
                skill("gaode-poi", "高德POI查询与商圈聚类分析，地点检索周边分析", null),
                skill("brand-guidelines", "Anthropic brand colors and typography", "test"),
                skill("ai-image-prompts-lite", "AI 图像提示词推荐助手，绘图提示词润色配图", "test"));
        Set<String> q = SkillRuntimeService.tokenize("用skill帮我润色绘图提示词再画一只猫");
        List<ToolPackage> top = SkillRuntimeService.rankForDiscovery(all, q, 3);
        assertEquals(3, top.size());
        assertTrue(names(top).contains("ai-image-prompts-lite"),
                "图像相关问题应让租户生图提示词 Skill 进入发现列表: " + names(top));
    }

    @Test
    void unrelatedQueryStillKeepsTenantSkillsOverPlatform() {
        List<ToolPackage> all = List.of(
                skill("design-system", "design system", null),
                skill("gaode-poi", "poi", null),
                skill("brand-guidelines", "brand", "test"),
                skill("ai-image-prompts-lite", "image prompts", "test"));
        // 查询与任何 Skill 都不相关 → 全 0 分，同分租户优先，租户 Skill 不被平台挤掉。
        List<ToolPackage> top = SkillRuntimeService.rankForDiscovery(all, SkillRuntimeService.tokenize("zzzz"), 3);
        assertTrue(names(top).contains("brand-guidelines"), names(top).toString());
        assertTrue(names(top).contains("ai-image-prompts-lite"), names(top).toString());
    }
}

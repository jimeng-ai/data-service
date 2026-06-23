package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.AiSkillToolPackage;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发现阶段 Skill 挑选：常规数量下「展示全部、模型自行判断」；仅超出安全上限才退化到相关性排序 + 租户优先 + 截断。
 * 守护两条回归：①知识库等无关请求不再因关键词命中被强行激活图像技能(改为模型按描述判断)；
 * ②租户自有 Skill 不被平台 Skill 挤出而漏发现。
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

    private static Map<String, ToolPackage> asMap(List<ToolPackage> pkgs) {
        Map<String, ToolPackage> m = new LinkedHashMap<>();
        for (ToolPackage p : pkgs) m.put(p.getName(), p);
        return m;
    }

    @Test
    void tokenizeMixesEnglishWordsAndCjkBigrams() {
        Set<String> t = SkillRuntimeService.tokenize("画图 image 提示词");
        assertTrue(t.contains("image"));
        assertTrue(t.contains("提示")); // bigram
        assertTrue(t.contains("示词")); // bigram
    }

    // ---- 发现阶段「展示全部、模型自行判断」：selectForDiscovery ----

    @Test
    void discoveryShowsEverySkillWhenUnderCap() {
        // 常规数量下，发现阶段把每一个 Skill 都展示给模型(让模型读到全部描述、自行判断是否激活)，不做关键词预筛。
        List<ToolPackage> all = List.of(
                skill("design-system", "Audit your design system", null),
                skill("gaode-poi", "高德POI查询与商圈聚类分析", null),
                skill("brand-guidelines", "Anthropic brand colors and typography", "test"),
                skill("ai-image-prompts-lite", "AI 图像提示词推荐助手，绘图提示词润色配图", "test"));
        List<ToolPackage> shown = SkillRuntimeService.selectForDiscovery(asMap(all), "帮我把这条内容上传到知识库");
        assertEquals(4, shown.size(), "候选数未超上限时应原样全部展示");
        assertTrue(names(shown).containsAll(
                List.of("design-system", "gaode-poi", "brand-guidelines", "ai-image-prompts-lite")));
    }

    @Test
    void discoveryFallsBackToRankingWhenOverCap() {
        // 仅当 Skill 数量超过安全上限(30)时，才退化为相关性排序 + 截断，防止上下文膨胀。
        java.util.List<ToolPackage> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(skill("filler-" + i, "无关填充技能", null));
        many.add(skill("ai-image-prompts-lite", "AI 图像 绘图 提示词 配图", "test"));
        List<ToolPackage> shown = SkillRuntimeService.selectForDiscovery(asMap(many), "帮我绘图配图生成图像提示词");
        assertEquals(30, shown.size(), "超上限应截断到 DISCOVERY_MAX");
        assertTrue(names(shown).contains("ai-image-prompts-lite"),
                "超量兜底排序应让相关的租户图像技能进入发现列表: " + names(shown));
    }

    // ---- 超量兜底时仍保留的相关性排序：rankForDiscovery ----

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

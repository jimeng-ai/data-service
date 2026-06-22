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

    private static ToolPackage skillWithBody(String name, String desc, String tenantId, String body) {
        AiSkill s = new AiSkill();
        s.setName(name);
        s.setDescription(desc);
        s.setTenantId(tenantId);
        s.setBody(body);
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

    // ---- 确定性自动激活：pickAutoActivateTenantSkill ----

    @Test
    void autoActivatePicksRelevantTenantPromptSkill() {
        List<ToolPackage> discovered = List.of(
                skillWithBody("design-system", "Audit your design system", null, "platform body"),
                // 与线上 ai-image-prompts-lite 一致：描述含「生成」，故「生成…图片」请求词面相关。
                skillWithBody("ai-image-prompts-lite",
                        "AI 图像提示词推荐助手，按风格场景生成绘图提示词、配图润色", "test", "提示词优化指引正文"));
        ToolPackage auto = SkillRuntimeService.pickAutoActivateTenantSkill(
                discovered, "帮我生成一张小猫在奔跑的图片");
        assertNotNull(auto, "生图请求应确定性自动激活租户图像提示词技能");
        assertEquals("ai-image-prompts-lite", auto.getName());
    }

    @Test
    void autoActivateNeverPicksPlatformSkill() {
        // 平台技能(tenantId==null)即便相关也不自动激活，避免对所有对话强行注入平台技能正文。
        List<ToolPackage> discovered = List.of(
                skillWithBody("design-system", "设计系统 组件 审计 提示词", null, "platform body"));
        assertNull(SkillRuntimeService.pickAutoActivateTenantSkill(discovered, "设计系统组件审计提示词"));
    }

    @Test
    void autoActivateNullWhenNoLexicalOverlap() {
        List<ToolPackage> discovered = List.of(
                skillWithBody("ai-image-prompts-lite", "AI 图像提示词 绘图", "test", "正文"));
        assertNull(SkillRuntimeService.pickAutoActivateTenantSkill(discovered, "今天天气怎么样"));
    }

    @Test
    void autoActivateSkipsTenantSkillWithoutBody() {
        // 无正文的技能没有可注入的指引，不自动激活。
        List<ToolPackage> discovered = List.of(
                skill("ai-image-prompts-lite", "AI 图像提示词 绘图 配图", "test"));
        assertNull(SkillRuntimeService.pickAutoActivateTenantSkill(discovered, "帮我绘图配图"));
    }

    @Test
    void autoActivatePicksHighestScoringTenantSkill() {
        List<ToolPackage> discovered = List.of(
                skillWithBody("weak-skill", "无关 描述", "test", "正文1"),
                skillWithBody("image-skill", "图像 绘图 提示词 配图 生成", "test", "正文2"));
        ToolPackage auto = SkillRuntimeService.pickAutoActivateTenantSkill(
                discovered, "绘图 提示词 配图 生成图像");
        assertNotNull(auto);
        assertEquals("image-skill", auto.getName());
    }
}

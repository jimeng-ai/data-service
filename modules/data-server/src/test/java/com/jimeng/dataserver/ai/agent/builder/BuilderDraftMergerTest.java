package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuilderDraftMergerTest {

    private final BuilderDraftMerger merger = new BuilderDraftMerger();

    @Test
    void merge_appliesOnlyPresentFields_andReturnsUpdatedKeys() {
        BuilderDraft base = new BuilderDraft();
        base.setName("旧名");
        base.setSystemPrompt("旧人设");

        Map<String, Object> patch = Map.of(
                "name", "客服助手",
                "description", "处理售后问题");

        List<String> updated = merger.apply(base, patch);

        assertEquals("客服助手", base.getName());
        assertEquals("处理售后问题", base.getDescription());
        assertEquals("旧人设", base.getSystemPrompt());          // 未给的字段不动
        assertTrue(updated.containsAll(List.of("name", "description")));
        assertEquals(2, updated.size());
    }

    @Test
    void merge_listAndMapFields() {
        BuilderDraft base = new BuilderDraft();
        Map<String, Object> patch = Map.of(
                "presetQuestions", List.of("你能做什么", "怎么退货"),
                "modelParams", Map.of("temperature", 0.7, "maxTokens", 2048),
                "recommendedPluginIds", List.of(11, 12),
                "recommendedKbIds", List.of(5));

        merger.apply(base, patch);

        assertEquals(List.of("你能做什么", "怎么退货"), base.getPresetQuestions());
        assertEquals(0.7, base.getModelParams().get("temperature"));
        assertEquals(List.of(11L, 12L), base.getRecommendedPluginIds());   // 数字归一为 Long
        assertEquals(List.of(5L), base.getRecommendedKbIds());
    }

    @Test
    void merge_ignoresUnknownKeys_andNulls() {
        BuilderDraft base = new BuilderDraft();
        base.setName("保留");
        Map<String, Object> patch = new java.util.HashMap<>();
        patch.put("name", null);          // 显式 null 不覆盖
        patch.put("unknownField", "x");   // 未知字段忽略

        List<String> updated = merger.apply(base, patch);

        assertEquals("保留", base.getName());
        assertTrue(updated.isEmpty());
    }
}

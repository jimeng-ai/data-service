package com.jimeng.dataserver.ai.skill.builder;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每会话内存态 {@link SkillDraft} 的共享持有者（含 files/脚本——DRAFT ai_skill 行不存这些）。
 *
 * <p>无任何依赖：刻意把内存草稿从 {@link SkillBuilderRunService} 抽出来，避免
 * {@link DraftSkillToolExecutor}（被工具执行注册中心收集）→ SkillBuilderRunService →
 * ClaudeService → … → SkillToolExecutorRegistryService 的构造期循环依赖。
 * 执行器与 run-service 都只依赖本叶子组件。
 */
@Component
public class SkillDraftStore {

    private final ConcurrentHashMap<Long, SkillDraft> drafts = new ConcurrentHashMap<>();

    /** 合并一次 draft_skill 增量并返回合并后的快照。 */
    public SkillDraft merge(Long conversationId, Map<String, Object> patch) {
        SkillDraft draft = drafts.computeIfAbsent(conversationId, k -> new SkillDraft());
        SkillDraftMerger.merge(draft, patch);
        return draft;
    }

    /** 取该会话当前内存草稿；无则 null。 */
    public SkillDraft current(Long conversationId) {
        return drafts.get(conversationId);
    }

    /** finalize/清理后移除。 */
    public void clear(Long conversationId) {
        drafts.remove(conversationId);
    }
}

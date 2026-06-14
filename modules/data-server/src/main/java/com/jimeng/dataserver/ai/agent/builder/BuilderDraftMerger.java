package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 draft_agent 工具的增量 patch 合并进当前草稿。纯函数（无 IO），便于单测。
 * 规则：只应用"出现且非 null"的字段；未知 key 忽略；返回本次真正改动的字段名。
 */
@Component
public class BuilderDraftMerger {

    /** @return 本次实际更新的字段名列表（给模型 ack / 日志用） */
    @SuppressWarnings("unchecked")
    public List<String> apply(BuilderDraft draft, Map<String, Object> patch) {
        List<String> updated = new ArrayList<>();
        if (patch == null) return updated;

        applyStr(patch, "name", draft::setName, updated);
        applyStr(patch, "description", draft::setDescription, updated);
        applyStr(patch, "avatarHint", draft::setAvatarHint, updated);
        applyStr(patch, "systemPrompt", draft::setSystemPrompt, updated);
        applyStr(patch, "model", draft::setModel, updated);

        Object preset = patch.get("presetQuestions");
        if (preset instanceof List<?> l) {
            draft.setPresetQuestions(l.stream().map(String::valueOf).toList());
            updated.add("presetQuestions");
        }
        Object params = patch.get("modelParams");
        if (params instanceof Map<?, ?> m) {
            draft.getModelParams().putAll((Map<String, Object>) m);
            updated.add("modelParams");
        }
        applyLongList(patch, "recommendedPluginIds", draft::setRecommendedPluginIds, updated);
        applyLongList(patch, "recommendedKbIds", draft::setRecommendedKbIds, updated);
        return updated;
    }

    private void applyStr(Map<String, Object> patch, String key,
                          java.util.function.Consumer<String> setter, List<String> updated) {
        Object v = patch.get(key);
        if (v != null && !String.valueOf(v).isEmpty()) {
            setter.accept(String.valueOf(v));
            updated.add(key);
        }
    }

    private void applyLongList(Map<String, Object> patch, String key,
                               java.util.function.Consumer<List<Long>> setter, List<String> updated) {
        Object v = patch.get(key);
        if (v instanceof List<?> l) {
            setter.accept(l.stream().map(x -> Long.valueOf(String.valueOf(x))).toList());
            updated.add(key);
        }
    }
}

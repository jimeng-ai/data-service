package com.jimeng.dataserver.ai.skill.builder;

import java.util.Map;

public final class SkillDraftMerger {
    private SkillDraftMerger() {}
    @SuppressWarnings("unchecked")
    public static void merge(SkillDraft draft, Map<String, Object> patch) {
        if (patch == null) return;
        if (patch.get("name") != null) draft.setName(String.valueOf(patch.get("name")));
        if (patch.get("description") != null) draft.setDescription(String.valueOf(patch.get("description")));
        if (patch.get("body") != null) draft.setBody(String.valueOf(patch.get("body")));
        if (patch.get("skillType") != null) draft.setSkillType(String.valueOf(patch.get("skillType")));
        Object files = patch.get("files");
        if (files instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    draft.getFiles().put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
    }
}

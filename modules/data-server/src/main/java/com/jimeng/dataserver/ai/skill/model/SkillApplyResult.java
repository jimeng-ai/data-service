package com.jimeng.dataserver.ai.skill.model;

import java.util.Collections;
import java.util.List;

public class SkillApplyResult {

    private final boolean enabled;
    private final List<String> selectedSkillNames;

    public SkillApplyResult(boolean enabled, List<String> selectedSkillNames) {
        this.enabled = enabled;
        this.selectedSkillNames = selectedSkillNames == null ? Collections.emptyList() : List.copyOf(selectedSkillNames);
    }

    public static SkillApplyResult disabled() {
        return new SkillApplyResult(false, Collections.emptyList());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getSelectedSkillNames() {
        return selectedSkillNames;
    }
}

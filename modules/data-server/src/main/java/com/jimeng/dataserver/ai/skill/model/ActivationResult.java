package com.jimeng.dataserver.ai.skill.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ActivationResult {

    private final boolean success;
    private final List<String> activatedSkillNames;
    private final Map<String, Object> toolResultBlock;

    public ActivationResult(boolean success, List<String> activatedSkillNames, Map<String, Object> toolResultBlock) {
        this.success = success;
        this.activatedSkillNames = activatedSkillNames == null ? Collections.emptyList() : List.copyOf(activatedSkillNames);
        this.toolResultBlock = toolResultBlock == null ? Collections.emptyMap() : Map.copyOf(toolResultBlock);
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getActivatedSkillNames() {
        return activatedSkillNames;
    }

    public Map<String, Object> getToolResultBlock() {
        return toolResultBlock;
    }
}

package com.jimeng.dataserver.ai.skill.model;

import java.util.Collections;
import java.util.List;

public class SkillApplyResult {

    public enum Phase {
        DISABLED,    // skill 未启用或无可用 skill
        DISCOVERY,   // 发现阶段：仅注入摘要 + activate_skills
        ACTIVATED    // 激活阶段：已注入完整 skill 内容（含显式指定）
    }

    private final Phase phase;
    private final List<String> selectedSkillNames;

    private SkillApplyResult(Phase phase, List<String> selectedSkillNames) {
        this.phase = phase;
        this.selectedSkillNames = selectedSkillNames == null ? Collections.emptyList() : List.copyOf(selectedSkillNames);
    }

    /**
     * @deprecated Use {@link #activated(List)} instead. Will be removed after all call sites are migrated.
     */
    @Deprecated
    public SkillApplyResult(boolean enabled, List<String> selectedSkillNames) {
        this(enabled ? Phase.ACTIVATED : Phase.DISABLED, selectedSkillNames);
    }

    /** DISABLED 工厂方法 */
    public static SkillApplyResult disabled() {
        return new SkillApplyResult(Phase.DISABLED, Collections.emptyList());
    }

    /** DISCOVERY 工厂方法 */
    public static SkillApplyResult discovery(List<String> allSkillNames) {
        return new SkillApplyResult(Phase.DISCOVERY, allSkillNames);
    }

    /** ACTIVATED 工厂方法 */
    public static SkillApplyResult activated(List<String> selectedSkillNames) {
        return new SkillApplyResult(Phase.ACTIVATED, selectedSkillNames);
    }

    /**
     * 向后兼容：DISCOVERY 和 ACTIVATED 阶段均返回 true
     */
    public boolean isEnabled() {
        return phase != Phase.DISABLED;
    }

    public boolean isDiscoveryPhase() {
        return phase == Phase.DISCOVERY;
    }

    public boolean isActivatedPhase() {
        return phase == Phase.ACTIVATED;
    }

    public Phase getPhase() {
        return phase;
    }

    public List<String> getSelectedSkillNames() {
        return selectedSkillNames;
    }
}

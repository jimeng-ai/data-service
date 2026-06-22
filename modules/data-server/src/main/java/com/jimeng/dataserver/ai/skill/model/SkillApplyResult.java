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
    /**
     * 由服务端「确定性自动激活」（直接注入正文，不依赖模型调 activate_skills）的技能名。
     * 用于让对话循环补发一条合成「激活技能」步骤，使前端仍能看到技能被使用。
     */
    private final List<String> autoActivatedSkillNames;

    private SkillApplyResult(Phase phase, List<String> selectedSkillNames, List<String> autoActivatedSkillNames) {
        this.phase = phase;
        this.selectedSkillNames = selectedSkillNames == null ? Collections.emptyList() : List.copyOf(selectedSkillNames);
        this.autoActivatedSkillNames =
                autoActivatedSkillNames == null ? Collections.emptyList() : List.copyOf(autoActivatedSkillNames);
    }

    /**
     * @deprecated Use {@link #activated(List)} instead. Will be removed after all call sites are migrated.
     */
    @Deprecated
    public SkillApplyResult(boolean enabled, List<String> selectedSkillNames) {
        this(enabled ? Phase.ACTIVATED : Phase.DISABLED, selectedSkillNames, Collections.emptyList());
    }

    /** DISABLED 工厂方法 */
    public static SkillApplyResult disabled() {
        return new SkillApplyResult(Phase.DISABLED, Collections.emptyList(), Collections.emptyList());
    }

    /** DISCOVERY 工厂方法 */
    public static SkillApplyResult discovery(List<String> allSkillNames) {
        return new SkillApplyResult(Phase.DISCOVERY, allSkillNames, Collections.emptyList());
    }

    /** DISCOVERY 阶段，但其中部分技能已由服务端确定性自动激活（已注入正文）；其余仍待模型按需发现/激活。 */
    public static SkillApplyResult discoveryWithAutoActivated(List<String> allSkillNames, List<String> autoActivated) {
        return new SkillApplyResult(Phase.DISCOVERY, allSkillNames, autoActivated);
    }

    /** 全部由服务端确定性自动激活，无剩余待发现技能。 */
    public static SkillApplyResult autoActivated(List<String> autoActivated) {
        return new SkillApplyResult(Phase.ACTIVATED, autoActivated, autoActivated);
    }

    /** ACTIVATED 工厂方法 */
    public static SkillApplyResult activated(List<String> selectedSkillNames) {
        return new SkillApplyResult(Phase.ACTIVATED, selectedSkillNames, Collections.emptyList());
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

    public List<String> getAutoActivatedSkillNames() {
        return autoActivatedSkillNames;
    }
}

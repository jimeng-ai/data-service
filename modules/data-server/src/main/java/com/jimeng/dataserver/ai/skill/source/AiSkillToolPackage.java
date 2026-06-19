package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolPackageKind;
import com.jimeng.persistence.entity.AiSkill;
import java.util.List;

public class AiSkillToolPackage implements ToolPackage {
    private final AiSkill skill;
    public AiSkillToolPackage(AiSkill skill) { this.skill = skill; }
    @Override public String getName() { return skill.getName(); }
    @Override public String getDescription() { return skill.getDescription(); }
    @Override public String getBody() { return skill.getBody(); }
    @Override public List<SkillToolDefinition> getTools() { return List.of(); }
    @Override public String getTenantId() { return skill.getTenantId(); }
    @Override public ToolPackageKind getKind() { return ToolPackageKind.SKILL; }
}

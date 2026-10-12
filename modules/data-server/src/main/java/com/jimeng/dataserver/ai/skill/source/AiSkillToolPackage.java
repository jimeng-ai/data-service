package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.http.tool.HttpRequestToolDefinition;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolPackageKind;
import com.jimeng.persistence.entity.AiSkill;

import java.util.List;

public class AiSkillToolPackage implements ToolPackage {
    private final AiSkill skill;
    /** 解析结果读库里存好的那份（设计文档 §3.6：运行时不再重复解析 SKILL.md）。 */
    private final SkillManifest manifest;

    public AiSkillToolPackage(AiSkill skill) {
        this.skill = skill;
        this.manifest = SkillManifest.fromJson(skill.getManifest());
    }

    @Override public String getName() { return skill.getName(); }
    @Override public String getDescription() { return skill.getDescription(); }
    @Override public String getBody() { return skill.getBody(); }
    /** 有 HTTP 声明（hosts / key / web）才带 http_request：激活后并入本轮工具列表（§4.1）。 */
    @Override public List<SkillToolDefinition> getTools() {
        return manifest.hasHttp() ? List.of(HttpRequestToolDefinition.DEF) : List.of();
    }
    @Override public String getRuntimeNotes() { return HttpRequestToolDefinition.notes(manifest); }
    @Override public String getTenantId() { return skill.getTenantId(); }
    @Override public Long getSourceId() { return skill.getId(); }
    @Override public ToolPackageKind getKind() { return ToolPackageKind.SKILL; }

    public SkillManifest getManifest() {
        return manifest;
    }

    public SkillGrant toGrant() {
        return new SkillGrant(skill.getId(), skill.getVersion() == null ? 1 : skill.getVersion(), skill.getName(), manifest);
    }
}

package com.jimeng.dataserver.ai.skill.controller.dto;

import com.jimeng.persistence.entity.AiSkill;
import lombok.Data;

@Data
public class SkillView {
    private String id;
    private String name;
    private String description;
    private String scope;
    private String skillType;
    private String source;
    private String status;
    private String ownerUserId;
    private Integer version;

    public static SkillView of(AiSkill s) {
        SkillView v = new SkillView();
        v.setId(s.getId() == null ? null : String.valueOf(s.getId()));
        v.setName(s.getName());
        v.setDescription(s.getDescription());
        v.setScope(s.getScope());
        v.setSkillType(s.getSkillType());
        v.setSource(s.getSource());
        v.setStatus(s.getStatus());
        v.setOwnerUserId(s.getOwnerUserId() == null ? null : String.valueOf(s.getOwnerUserId()));
        v.setVersion(s.getVersion());
        return v;
    }
}

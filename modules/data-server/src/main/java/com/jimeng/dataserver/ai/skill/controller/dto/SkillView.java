package com.jimeng.dataserver.ai.skill.controller.dto;

import com.jimeng.persistence.entity.AiSkill;
import lombok.Data;

@Data
public class SkillView {
    /** 与 SkillBuilderSessionService.ORIGIN_REF_PREFIX 一致（dto 包不反向依赖构建器包）。 */
    private static final String BUILDER_SESSION_REF = "builder-session:";
    private String id;
    private String name;
    private String description;
    private String scope;
    private String skillType;
    private String source;
    private String status;
    private String ownerUserId;
    private Integer version;
    /** 构建器草稿所属的会话 id（列表页「继续编辑」用）；不是构建器草稿则为 null。 */
    private String builderSessionId;

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
        String ref = s.getOriginRef();
        if ("DRAFT".equals(s.getStatus()) && ref != null && ref.startsWith(BUILDER_SESSION_REF)) {
            v.setBuilderSessionId(ref.substring(BUILDER_SESSION_REF.length()));
        }
        return v;
    }
}

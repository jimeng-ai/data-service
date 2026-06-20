package com.jimeng.dataserver.ai.skill.controller.dto;

import com.jimeng.persistence.entity.AiSkill;
import lombok.Data;

import java.util.List;

/** skill 详情：在 {@link SkillView} 元数据基础上带 SKILL.md 正文 + DOER 的 bundle 文件（脚本）。 */
@Data
public class SkillDetailView {
    private String id;
    private String name;
    private String description;
    private String scope;
    private String skillType;
    private String source;
    private String status;
    private String ownerUserId;
    /** 创建者显示名（displayName，缺省回退 username）；由控制器解析后填入，前端展示用 */
    private String ownerName;
    private Integer version;
    /** SKILL.md 正文 */
    private String body;
    /** DOER bundle 文件（脚本/依赖/README 等，不含已由 body 呈现的根 SKILL.md）；PROMPT 为空 */
    private List<SkillFileView> files;

    public static SkillDetailView of(AiSkill s, List<SkillFileView> files, String ownerName) {
        SkillDetailView v = new SkillDetailView();
        v.setId(s.getId() == null ? null : String.valueOf(s.getId()));
        v.setName(s.getName());
        v.setDescription(s.getDescription());
        v.setScope(s.getScope());
        v.setSkillType(s.getSkillType());
        v.setSource(s.getSource());
        v.setStatus(s.getStatus());
        v.setOwnerUserId(s.getOwnerUserId() == null ? null : String.valueOf(s.getOwnerUserId()));
        v.setOwnerName(ownerName);
        v.setVersion(s.getVersion());
        v.setBody(s.getBody());
        v.setFiles(files);
        return v;
    }
}

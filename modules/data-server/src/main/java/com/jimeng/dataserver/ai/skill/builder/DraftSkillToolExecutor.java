package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

/** draft_skill 执行器：把模型增量草稿合并进该会话对应的 DRAFT ai_skill 行（元数据+body；files 由 run-service/finalize 处理）。 */
@Component
@RequiredArgsConstructor
public class DraftSkillToolExecutor implements SkillToolExecutor {
    private final AiSkillMapper aiSkillMapper;

    @Override public boolean supports(String toolName) {
        return DraftSkillToolPackage.TOOL_NAME.equals(toolName);
    }
    @Override public Object execute(String toolName, Map<String, Object> input) {
        throw new UnsupportedOperationException("通过 applyDraft 调用，由对话循环注入会话上下文");
    }

    public AiSkill applyDraft(Long conversationId, String tenantId, Long ownerUserId, Map<String, Object> patch) {
        String ref = "builder:" + conversationId;
        AiSkill row = aiSkillMapper.selectOne(new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getTenantId, tenantId)
                .eq(AiSkill::getOriginRef, ref)
                .eq(AiSkill::getStatus, SkillConst.STATUS_DRAFT)
                .last("limit 1"));
        boolean creating = row == null;
        if (creating) {
            row = new AiSkill();
            row.setTenantId(tenantId);
            row.setOwnerUserId(ownerUserId);
            row.setScope(SkillConst.SCOPE_PRIVATE);
            row.setStatus(SkillConst.STATUS_DRAFT);
            row.setSource(SkillConst.SOURCE_AI_GEN);
            row.setOriginRef(ref);
            row.setSkillType(SkillConst.TYPE_PROMPT);
            row.setVersion(1);
        }
        if (patch.get("name") != null) row.setName(String.valueOf(patch.get("name")));
        if (patch.get("description") != null) row.setDescription(String.valueOf(patch.get("description")));
        if (patch.get("body") != null) row.setBody(String.valueOf(patch.get("body")));
        if (patch.get("skillType") != null) row.setSkillType(String.valueOf(patch.get("skillType")));
        if (creating) aiSkillMapper.insert(row); else aiSkillMapper.updateById(row);
        return row;
    }

    @Override public String traceStepType() { return "TOOL_CALL"; }
}

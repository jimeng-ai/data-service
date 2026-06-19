package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行 draft_skill：合并增量草稿。镜像 {@link com.jimeng.dataserver.ai.agent.builder.DraftAgentToolExecutor}
 * 的路由——本类是 {@link SkillToolExecutor} bean，对话循环的工具执行注册中心据 {@link #supports} 命中后调
 * {@link #execute}；runId 从 MDC（{@link MdcAsyncSupport#MDC_CONNECTION_ID}）读取（执行器跑在流式 executor
 * 线程，runId 已被捎带），据此解析所属会话。
 *
 * <p>一次执行做三件事：
 * <ol>
 *   <li>{@link #applyDraft} 把元数据 + body 合并进该会话的 DRAFT {@code ai_skill} 行（落库）；</li>
 *   <li>把同一 patch 合并进 {@link SkillBuilderRunService} 的内存 {@link SkillDraft}（保留 files/脚本——
 *       DRAFT 行不存这些，供试跑/finalize 用）；</li>
 *   <li>经 SSE {@code draft-update} 推内存草稿全量给前端预览。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class DraftSkillToolExecutor implements SkillToolExecutor {

    private final AiSkillMapper aiSkillMapper;
    private final ChatConversationService conversationService;
    private final RunEventTee tee;
    // run-service 持每会话内存草稿 map；本执行器据 runId→conversationId 回调合并增量（单向依赖，无环）。
    private final SkillBuilderRunService runService;

    @Override
    public boolean supports(String toolName) {
        return DraftSkillToolPackage.TOOL_NAME.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        String runId = MDC.get(MdcAsyncSupport.MDC_CONNECTION_ID);
        Long conversationId = conversationService.conversationIdOfRun(runId);
        if (conversationId == null) {
            throw new IllegalStateException("找不到 draft_skill 所属会话, runId=" + runId);
        }

        String tenantId = TenantContext.get();
        Long ownerUserId = AdminRequestContext.findUserIdOrNull();

        // 1. 落库 DRAFT ai_skill 行（元数据 + body）。
        applyDraft(conversationId, tenantId, ownerUserId, input);
        // 2. 合并进内存草稿（含 files/脚本），并推 SSE 全量给前端预览。
        SkillDraft draft = runService.mergeDraft(conversationId, input);
        if (runId != null) {
            tee.teeJson(runId, "draft-update", draft);
        }

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("ok", true);
        ack.put("name", draft.getName());
        ack.put("skillType", draft.getSkillType());
        return ack;
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

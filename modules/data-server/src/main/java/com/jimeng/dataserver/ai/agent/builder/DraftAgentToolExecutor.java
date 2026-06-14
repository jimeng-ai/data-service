package com.jimeng.dataserver.ai.agent.builder;

import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.model.ModelCatalogService;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行 draft_agent：校验 patch → 合并进会话当前草稿 → 落库快照 → 经 SSE draft-update 推完整草稿 → 回 ack。
 * runId 从 MDC（MdcAsyncSupport.MDC_CONNECTION_ID）读取——执行器跑在流式 executor 线程上，runId 已被捎带。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftAgentToolExecutor implements SkillToolExecutor {

    private final BuilderDraftMerger merger;
    private final ChatConversationService conversationService;
    private final ModelCatalogService modelCatalogService;
    private final RunEventTee tee;

    @Override
    public boolean supports(String toolName) {
        return DraftAgentToolPackage.TOOL_NAME.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        String runId = MDC.get(MdcAsyncSupport.MDC_CONNECTION_ID);
        Long conversationId = conversationService.conversationIdOfRun(runId);
        if (conversationId == null) {
            throw new IllegalStateException("找不到 draft_agent 所属会话, runId=" + runId);
        }

        // 校验 model（必须取自启用中的目录）。
        Object model = input.get("model");
        if (model != null && !modelCatalogService.isValidModel(String.valueOf(model))) {
            List<String> valid = modelCatalogService.listEnabled().stream()
                    .map(m -> m.getValue()).toList();
            throw new IllegalArgumentException("model 不在可选模型目录: " + model + "；可选: " + valid);
        }

        // 载入现有草稿并合并 patch。
        String json = conversationService.getBuilderDraft(conversationId);
        BuilderDraft draft = json == null ? new BuilderDraft() : JSONUtil.toBean(json, BuilderDraft.class);
        List<String> updated = merger.apply(draft, input);

        // 校验 temperature 不超模型上限（model 取草稿合并后的值）。
        clampTemperatureOrThrow(draft);

        // 落库 + 推 SSE 完整草稿。
        conversationService.saveBuilderDraft(conversationId, JSONUtil.toJsonStr(draft));
        if (runId != null) {
            tee.teeJson(runId, "draft-update", draft);
        }

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("ok", true);
        ack.put("updatedFields", updated);
        return ack;
    }

    private void clampTemperatureOrThrow(BuilderDraft draft) {
        Object temp = draft.getModelParams() == null ? null : draft.getModelParams().get("temperature");
        if (temp instanceof Number n && draft.getModel() != null) {
            double max = modelCatalogService.maxTempOf(draft.getModel());
            if (n.doubleValue() > max) {
                throw new IllegalArgumentException(
                        "temperature " + n + " 超过模型 " + draft.getModel() + " 上限 " + max);
            }
        }
    }
}

package com.jimeng.dataserver.ai.agent.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderSessionDtos.FinalizeRequest;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.persistence.entity.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** 草稿 → DRAFT Agent + 绑插件 + 写 kb_config。一把事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBuilderFinalizeService {

    private final AgentService agentService;
    private final ChatConversationService conversationService;
    private final PermissionResolver permissionResolver;

    @Transactional
    public Long finalize(Long conversationId, FinalizeRequest req) {
        BuilderDraft draft = req.getDraft() != null ? req.getDraft() : loadSnapshot(conversationId);
        if (draft == null || StrUtil.isBlank(draft.getName()) || StrUtil.isBlank(draft.getSystemPrompt())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "name 和 systemPrompt 不能为空");
        }

        Agent agent = new Agent();
        agent.setCode(generateCode());
        agent.setName(draft.getName());
        agent.setDescription(draft.getDescription());
        agent.setSystemPrompt(draft.getSystemPrompt());
        agent.setModel(draft.getModel());
        agent.setStatus("DRAFT");
        if (draft.getPresetQuestions() != null && !draft.getPresetQuestions().isEmpty()) {
            agent.setPresetQuestions(JSONUtil.toJsonStr(draft.getPresetQuestions()));
        }
        if (draft.getModelParams() != null && !draft.getModelParams().isEmpty()) {
            agent.setModelParams(JSONUtil.toJsonStr(draft.getModelParams()));
        }
        agent.setKbConfig(buildKbConfig(req));

        Agent created = agentService.create(agent);   // 含 code 唯一键兜底 + creator 授权

        // 绑定用户确认的插件（带权限校验，防越权间接调用）。
        if (req.getPluginIds() != null) {
            for (Long pluginId : req.getPluginIds()) {
                permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
                agentService.bindPlugin(created.getId(), pluginId);
            }
        }
        log.info("构建器 finalize: 会话 {} → DRAFT Agent {}（{}）", conversationId, created.getId(), created.getName());
        return created.getId();
    }

    private BuilderDraft loadSnapshot(Long conversationId) {
        String json = conversationService.getBuilderDraft(conversationId);
        return StrUtil.isBlank(json) ? null : JSONUtil.toBean(json, BuilderDraft.class);
    }

    /** kb_config = {kbIds, topK, scoreThreshold, rerank}；无 kbIds 时返回 null（不绑库）。 */
    private String buildKbConfig(FinalizeRequest req) {
        if (req.getKbIds() == null || req.getKbIds().isEmpty()) return null;
        Map<String, Object> kb = new LinkedHashMap<>();
        kb.put("kbIds", req.getKbIds());
        kb.put("topK", req.getTopK() != null ? req.getTopK() : 5);
        kb.put("scoreThreshold", req.getScoreThreshold() != null ? req.getScoreThreshold() : 0.5);
        kb.put("rerank", req.getRerank() != null ? req.getRerank() : Boolean.TRUE);
        return JSONUtil.toJsonStr(kb);
    }

    /** 唯一 code（agent 表有 uk_agent_tenant_code）。用 base36 时间戳，租户内不冲突。 */
    private String generateCode() {
        return "gen-" + Long.toString(System.currentTimeMillis(), 36);
    }
}

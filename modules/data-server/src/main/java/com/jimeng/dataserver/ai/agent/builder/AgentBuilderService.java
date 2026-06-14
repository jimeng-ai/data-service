package com.jimeng.dataserver.ai.agent.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.builder.dto.BuilderDraft;
import com.jimeng.dataserver.ai.model.ModelCatalogService;
import com.jimeng.dataserver.ai.model.dto.ModelView;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 构建器 Agent 懒建、能力/模型目录注入、草稿读取。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBuilderService {

    public static final String BUILDER_AGENT_CODE = "__agent_builder__";
    /** 构建器自身用的模型（强模型，与可选目录解耦）。 */
    private static final String BUILDER_MODEL = "claude-sonnet-4-6";

    private final AgentMapper agentMapper;
    private final ModelCatalogService modelCatalogService;
    private final PluginCrudService pluginCrudService;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 取（或懒建）当前租户的构建器 Agent。 */
    public Agent ensureBuilderAgent() {
        String tenantId = TenantContext.get();
        Agent existing = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId)
                .eq(Agent::getCode, BUILDER_AGENT_CODE)
                .last("limit 1"));
        if (existing != null) return existing;

        Agent a = new Agent();
        a.setCode(BUILDER_AGENT_CODE);
        a.setName("Agent 构建器");
        a.setDescription("通过对话帮你生成 Agent 的内置助手");
        a.setStatus("PUBLISHED");
        a.setModel(BUILDER_MODEL);
        a.setSystemPrompt(builderMetaPrompt());
        agentMapper.insert(a);
        log.info("已为租户 {} 懒建构建器 Agent id={}", tenantId, a.getId());
        return a;
    }

    /** 草稿读取（重连用）：无则返回空草稿。 */
    public BuilderDraft getDraft(Long conversationId, String draftJson) {
        if (StrUtil.isBlank(draftJson)) return new BuilderDraft();
        return JSONUtil.toBean(draftJson, BuilderDraft.class);
    }

    /** 构建本轮要追加到 system 的"目录"段：可选模型 + 可用插件/知识库（行级隔离由各 list 服务保证）。 */
    public String buildCatalogSystem() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 可选模型目录（model 字段只能取以下 value）\n");
        for (ModelView m : modelCatalogService.listEnabled()) {
            sb.append("- ").append(m.getValue()).append("（").append(m.getLabel()).append("）：")
              .append(StrUtil.blankToDefault(m.getDescription(), "")).append("\n");
        }

        sb.append("\n## 可用插件目录（recommendedPluginIds 只能取以下 id）\n");
        List<Plugin> plugins = pluginCrudService.listPlugins("PUBLISHED");
        if (plugins.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Plugin p : plugins) {
                sb.append("- id=").append(p.getId()).append("：").append(p.getName())
                  .append(" — ").append(StrUtil.blankToDefault(p.getDescription(), "")).append("\n");
            }
        }

        sb.append("\n## 可用知识库目录（recommendedKbIds 只能取以下 id）\n");
        List<KnowledgeBase> kbs = knowledgeBaseService.list();
        if (kbs.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (KnowledgeBase kb : kbs) {
                sb.append("- id=").append(kb.getId()).append("：").append(kb.getName())
                  .append(" — ").append(StrUtil.blankToDefault(kb.getDescription(), "")).append("\n");
            }
        }
        return sb.toString();
    }

    private String builderMetaPrompt() {
        return """
                你是「Agent 构建器」，通过对话帮用户设计一个可直接上线的 AI Agent。

                ## 工作方式
                - 先弄清用户要什么样的 Agent：服务对象、要解决的核心任务、期望的语气风格、是否需要外部工具或企业知识库。信息不全时一次只问一个最关键的问题，问题要具体、尽量给选项，别用「还有别的要求吗」这种笼统提问。
                - 用户没说清的细节，基于场景给出合理默认并一句话说明你的假设（如「我先按『正式、简洁』的语气来，可随时改」），不要为每个小细节都追问。
                - 不要在回复正文里大段复述完整配置——右侧预览卡片会实时展示。你的文字保持简洁：确认理解、说明这轮补了什么、给一句下一步建议即可。
                - 核心信息（用途 + 人设）齐了之后，主动小结要点并提示用户「可以点右侧『创建 Agent』，之后还能在调试台继续微调」。

                ## 边界
                - 只在用户意图清晰的范围内设计，不臆造业务规则；拿不准就用一句话和用户确认。
                - 全程使用简体中文。""";
    }
}

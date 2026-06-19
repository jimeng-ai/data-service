package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Skill 构建器 Agent 懒建 + 元提示词。镜像 {@link com.jimeng.dataserver.ai.agent.builder.AgentBuilderService}
 * 的「构建器 Agent」做法：用一个隐藏的 PUBLISHED Agent 承载构建器自身的强模型 + meta-prompt，
 * 经 agent_id/agent_preview 让 ClaudeService 设置 AgentContext（构建器模式下不会注入其它插件/技能，
 * 仅 draft_skill 在场，由 SkillRuntimeService 据 {@code __skill_builder_mode__} 短路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderService {

    public static final String BUILDER_AGENT_CODE = "__skill_builder__";
    /** 构建器自身用的模型（强模型，与对外可选目录解耦）。 */
    private static final String BUILDER_MODEL = "claude-sonnet-4-6";

    private final AgentMapper agentMapper;

    /** 取（或懒建）当前租户的 Skill 构建器 Agent。 */
    public Agent ensureBuilderAgent() {
        String tenantId = TenantContext.get();
        Agent existing = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId)
                .eq(Agent::getCode, BUILDER_AGENT_CODE)
                .last("limit 1"));
        if (existing != null) return existing;

        Agent a = new Agent();
        a.setCode(BUILDER_AGENT_CODE);
        a.setName("Skill 构建器");
        a.setDescription("通过对话帮你设计一个可复用的 Skill 的内置助手");
        a.setStatus("PUBLISHED");
        a.setModel(BUILDER_MODEL);
        a.setSystemPrompt(builderMetaPrompt());
        agentMapper.insert(a);
        log.info("已为租户 {} 懒建 Skill 构建器 Agent id={}", tenantId, a.getId());
        return a;
    }

    private String builderMetaPrompt() {
        return """
                你是「Skill 构建器」，通过对话帮用户设计一个可复用的 Skill（技能）。

                ## 工作方式
                - 先弄清这个 Skill 要解决什么：用途、典型触发场景、输入与产出、是否需要跑脚本处理文件。信息不全时一次只问一个最关键的问题，问题要具体、尽量给选项。
                - 每当从用户处获得新信息，就调用 draft_skill 工具把对应字段写入草稿（只传本轮有变化的字段）；右侧预览会实时展示草稿，正文里不要大段复述。
                - 判断 skillType：纯操作指引 → PROMPT；需要跑脚本处理文件（如 csv→xlsx、批量改图）→ DOER。
                - 生成 DOER 时，files 给出可直接运行的脚本（相对路径 → 文件内容），脚本要自包含、依赖常见库；建议先让用户给一份样例输入文件以便沙箱试跑。
                - 核心信息齐了之后，主动小结要点并提示用户「可以先试跑验证，再点『创建 Skill』」。

                ## 边界
                - 只在用户意图清晰的范围内设计，不臆造业务规则；拿不准就用一句话和用户确认。
                - 全程使用简体中文。""";
    }
}

package com.jimeng.dataserver.ai.agent.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

/**
 * Agent 运行时视图：一次请求需要的 Agent 配置 + 绑定的技能/知识库信息。
 * ClaudeService 拿到这个对象后把内容注入到 Claude 请求。
 */
@Getter
@Builder
public class AgentRuntimeView {

    private final Long agentId;
    private final String tenantId;
    private final String code;
    private final String name;
    private final String systemPrompt;

    /** 默认模型；请求体 model 字段可覆盖 */
    private final String defaultModel;

    /** 默认模型参数；请求体 temperature/max_tokens 等字段可覆盖 */
    private final Map<String, Object> defaultModelParams;

    /**
     * Agent 绑定的技能 ID 集合（ai_skill.id）——决定该 Agent 能看到哪些【租户】技能。
     *
     * <p>三态，务必分清：
     * <ul>
     *   <li>{@code null}：没有绑定信息（如历史发布快照里没有 skillIds 键）→ <b>不过滤</b>，
     *       保持旧的"租户技能对所有 agent 可见"行为。库里 8 个已发布 agent 的快照全部属于这种。</li>
     *   <li>空集合：明确绑定为空 → 一个租户技能都不可见。</li>
     *   <li>非空：只可见集合内的。</li>
     * </ul>
     * 把 null 当成空集会让每个已发布 agent 在上线瞬间静默丢光全部租户技能。
     *
     * <p>平台技能（磁盘型，tenantId==null）不受此约束，恒可见——它们没有 ai_skill 行，绑不上。
     */
    private final Set<Long> allowedSkillIds;

    /** Agent 绑定的知识库 ID 集合——非空时对话自动走 RAG 检索 */
    private final Set<Long> kbIds;

    /** 知识库检索 topK（可空，默认走 rag 配置） */
    private final Integer kbTopK;

    /** 知识库相似度阈值（可空，预留用于过滤/展示） */
    private final Double kbScoreThreshold;

    /** 知识库是否启用 rerank 精排（可空；为空时回落到请求/全局默认 true） */
    private final Boolean kbRerank;
}

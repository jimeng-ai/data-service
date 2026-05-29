package com.jimeng.dataserver.ai.agent.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

/**
 * Agent 运行时视图：一次请求需要的 Agent 配置 + 绑定的插件信息。
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

    /** Agent 绑定的插件 code 集合——决定该 Agent 能看到哪些 ToolPackage */
    private final Set<String> allowedPluginCodes;

    /** Agent 绑定的知识库 ID 集合——非空时对话自动走 RAG 检索 */
    private final Set<Long> kbIds;

    /** 知识库检索 topK（可空，默认走 rag 配置） */
    private final Integer kbTopK;

    /** 知识库相似度阈值（可空，预留用于过滤/展示） */
    private final Double kbScoreThreshold;
}

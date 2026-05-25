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

    /** Map<pluginCode, credentialAlias>——Agent 使用某插件时用哪份凭证 */
    private final Map<String, String> pluginCredentialAliases;
}

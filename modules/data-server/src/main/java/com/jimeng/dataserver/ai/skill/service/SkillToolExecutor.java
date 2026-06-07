package com.jimeng.dataserver.ai.skill.service;

import java.util.Map;

public interface SkillToolExecutor {

    boolean supports(String toolName);

    Object execute(String toolName, Map<String, Object> input);

    /**
     * 该执行器在调用链路 Trace 中对应的步骤类型：
     * {@code "TOOL_CALL"}（默认）/ {@code "PLUGIN_TRIGGER"}（插件）/ {@code null}（内部自行埋点，
     * 注册中心跳过，避免与 service 内埋点重复，如 RAG 检索已在 HybridSearchService/RerankService 记录）。
     */
    default String traceStepType() {
        return "TOOL_CALL";
    }
}

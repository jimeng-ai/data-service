package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

import java.util.Map;

/** 语义层 agent 的单表完整提交。资源身份全部来自 {@link SemanticAgentPrincipal}。 */
@Data
public class SubmitRequest {
    private String structureStamp;
    private Map<String, Object> submission;
}

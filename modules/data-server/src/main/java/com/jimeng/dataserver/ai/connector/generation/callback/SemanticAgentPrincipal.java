package com.jimeng.dataserver.ai.connector.generation.callback;

/**
 * 已通过回调过滤器校验的语义层 agent 身份。
 *
 * <p>控制器不得从请求体或客户端头重新读取这些资源 ID；只使用这个主体，避免 token 已绑定的批次、连接和运行
 * 被请求体里的同名字段替换。
 */
public record SemanticAgentPrincipal(
        String tenantId,
        String userId,
        long generationId,
        long connectorId,
        int sliceNo,
        String runId,
        String mode) {
}

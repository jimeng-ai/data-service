package com.jimeng.dataserver.ai.agent.runtime;

/**
 * 当前请求/流式会话的「Agent ID」上下文（ThreadLocal）。
 *
 * <p>背景：用户对话的 agent_id 会在转发给模型前被 ClaudeService 从请求体里移除
 * （移除后用于设置 AgentContext），导致调用日志落库时请求体里已经读不到 agent_id。
 * 这里镜像 TenantContext 的做法：请求/流式入口处 set，MdcAsyncSupport 透传到 executor 线程，
 * AiModelCallRecordService 落库时兜底读取，任务结束 clear。
 *
 * <p>只存原始字符串 ID，避免依赖 AgentRuntimeView 的具体结构。
 */
public final class AgentIdContext {

    private static final ThreadLocal<String> CURRENT_AGENT_ID = new ThreadLocal<>();

    private AgentIdContext() {
    }

    public static void set(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            CURRENT_AGENT_ID.set(agentId);
        }
    }

    public static String get() {
        return CURRENT_AGENT_ID.get();
    }

    public static void clear() {
        CURRENT_AGENT_ID.remove();
    }
}

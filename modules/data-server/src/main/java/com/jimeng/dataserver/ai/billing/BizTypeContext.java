package com.jimeng.dataserver.ai.billing;

/**
 * 当前请求/流式会话的「业务类型（功能）」上下文（ThreadLocal）。
 *
 * <p>背景：模型调用记账要按「功能」维度统计（运营平台展示）。RAG/rerank/embedding/上下文化/
 * Agent 沙箱等链路在落库时已显式带上 biz_type；但走 {@code AiConversationLoop} 的对话类调用
 * （普通对话 / RAG 知识库问答）请求体里没有 biz_type，且 biz_type 绝不能塞进转发给上游模型的
 * body（Anthropic 对未知字段会 400）。
 *
 * <p>因此镜像 {@link com.jimeng.dataserver.ai.agent.runtime.AgentIdContext} 的做法：调用方在入口
 * set，{@code MdcAsyncSupport} 透传到流式 executor 线程，{@link AiModelCallRecordService#recordRequest}
 * 落库时兜底读取（未设置时回退为 "chat"），任务结束 clear。
 *
 * <p>已知取值（biz_type 列，供运营平台「功能」维度统计）：
 * {@code chat}（普通/Agent 对话）、{@code rag_answer}（知识库问答）、
 * {@code rag_embedding} / {@code rag_rerank} / {@code rag_contextualization} / {@code rag_image_desc}、
 * {@code agent_exec}、{@code plugin_gen} / {@code plugin_refine}。
 */
public final class BizTypeContext {

    /** 对话类调用未显式标注时的默认功能。 */
    public static final String DEFAULT_CHAT = "chat";

    /** RAG 知识库问答（{@code /rag/answer}，最终复用 ClaudeService 生成答案）。 */
    public static final String RAG_ANSWER = "rag_answer";

    /** 对话式生成 Agent（构建器向导）。 */
    public static final String AGENT_GEN = "agent_gen";

    private static final ThreadLocal<String> CURRENT_BIZ_TYPE = new ThreadLocal<>();

    private BizTypeContext() {
    }

    public static void set(String bizType) {
        if (bizType != null && !bizType.isBlank()) {
            CURRENT_BIZ_TYPE.set(bizType);
        }
    }

    public static String get() {
        return CURRENT_BIZ_TYPE.get();
    }

    public static void clear() {
        CURRENT_BIZ_TYPE.remove();
    }
}

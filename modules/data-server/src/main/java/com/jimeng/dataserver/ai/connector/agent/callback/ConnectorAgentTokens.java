package com.jimeng.dataserver.ai.connector.agent.callback;

import com.jimeng.dataserver.ai.connector.generation.callback.SemanticAgentTokens;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 对话 agent 连接器回调协议中需要跨过滤器、控制器稳定共享的名字。
 *
 * <p>与 {@link SemanticAgentTokens} 是两套<b>互不通用</b>的凭据：语义层那套绑定「批次 + 连接 + 片号」，
 * 这套绑定「agent + 本次对话运行」。两者的 purpose 必须是不同的值，否则一枚语义层 token
 * 就能进连接器回调、反之亦然，而两边的资源绑定检查都会因为「不是我的 purpose」而被跳过。
 */
public final class ConnectorAgentTokens {

    /**
     * 回调前缀。
     *
     * <p>★ 刻意写成 {@code connector-agent} 而不是 {@code semantic-agent} 的某种后缀扩展：
     * 两个前缀<b>谁都不是谁的字符串前缀</b>。若取名成 {@code /data/internal/semantic-agent-conn/}，
     * 语义层过滤器的 {@code startsWith} 会把它当成自己的回调路径，于是这条路由的资源绑定
     * 由语义层那份逻辑来判——必然判不过，而且报错信息指向的是一个跟本功能无关的批次。
     */
    public static final String CALLBACK_PREFIX = "/data/internal/connector-agent/";

    /**
     * {@code purpose} claim 的值。<b>必须是新值，不可复用 semantic-agent</b>：
     * 复用等于把「一片语义层生成」的 token 变成「该 Agent 全部连接器的读写凭据」。
     */
    public static final String PURPOSE = "connector-agent";

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "user-id";

    public static final String CLAIM_PURPOSE = "purpose";
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_USER_ID = "id";
    public static final String CLAIM_REALM = "realm";
    public static final String CLAIM_AGENT_ID = "aid";
    public static final String CLAIM_RUN_ID = "rid";

    public static final String PRINCIPAL_ATTRIBUTE = ConnectorAgentTokens.class.getName() + ".principal";
    public static final String MDC_CONNECTOR_RUN = "connRun";

    /**
     * 请求体上限，直接引用语义层那一份（256KB）：两条回调走的是同一个 Servlet 容器、同一类
     * 「模型写的 JSON 参数」，两处各写一个数字迟早会分叉，而分叉的表现是「某一条回调莫名其妙被拒」。
     */
    public static final int MAX_BODY_BYTES = SemanticAgentTokens.MAX_BODY_BYTES;

    private ConnectorAgentTokens() {
    }

    /**
     * 读取过滤器写入的可信主体。
     *
     * <p>★ 拿不到就直接抛，<b>刻意不回退到请求参数</b>：回退就等于允许请求体自称身份——
     * 沙箱里跑的是模型自己写的代码，它完全可以在 body 里塞一个别的 agentId / tenantId。
     * 身份只有一个来源：过滤器验签后写进 request attribute 的这份。
     */
    public static ConnectorAgentPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof ConnectorAgentPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("缺少连接器 agent 回调身份");
    }
}

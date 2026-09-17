package com.jimeng.dataserver.ai.connector.generation.callback;

import jakarta.servlet.http.HttpServletRequest;

/** 语义层 agent 回调协议中需要跨过滤器、控制器稳定共享的名字。 */
public final class SemanticAgentTokens {

    public static final String CALLBACK_PREFIX = "/data/internal/semantic-agent/";
    public static final String PURPOSE = "semantic-agent";

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "user-id";

    public static final String CLAIM_PURPOSE = "purpose";
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_USER_ID = "id";
    public static final String CLAIM_REALM = "realm";
    public static final String CLAIM_GENERATION_ID = "gen";
    public static final String CLAIM_CONNECTOR_ID = "cid";
    public static final String CLAIM_SLICE_NO = "slice";
    public static final String CLAIM_RUN_ID = "rid";

    public static final String PRINCIPAL_ATTRIBUTE = SemanticAgentTokens.class.getName() + ".principal";
    public static final String MDC_SEMANTIC_GENERATION = "semanticGen";
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private SemanticAgentTokens() {
    }

    /**
     * 读取过滤器写入的可信主体。回调控制器若未经过过滤器，应直接失败，而不是回退到不可信的请求参数。
     */
    public static SemanticAgentPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof SemanticAgentPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("缺少语义层 agent 身份");
    }
}

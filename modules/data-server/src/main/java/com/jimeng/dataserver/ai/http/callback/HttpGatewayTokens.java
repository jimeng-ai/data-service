package com.jimeng.dataserver.ai.http.callback;

import com.jimeng.dataserver.ai.http.SkillGrant;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 网关回调协议里跨过滤器、控制器共享的名字。与连接器回调（connector-agent）、语义层回调（semantic-agent）是三套互不通用的凭据：
 * purpose 各不相同，前缀谁都不是谁的字符串前缀。
 */
public final class HttpGatewayTokens {

    public static final String CALLBACK_PREFIX = "/data/internal/http-gateway/";
    public static final String PURPOSE = "http-gateway";

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "user-id";

    public static final String CLAIM_PURPOSE = "purpose";
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_USER_ID = "id";
    public static final String CLAIM_REALM = "realm";
    public static final String CLAIM_AGENT_ID = "aid";
    public static final String CLAIM_RUN_ID = "rid";

    public static final String PRINCIPAL_ATTRIBUTE = HttpGatewayTokens.class.getName() + ".principal";
    public static final String GRANTS_ATTRIBUTE = HttpGatewayTokens.class.getName() + ".grants";
    public static final String MDC_HTTP_RUN = "httpRun";

    private HttpGatewayTokens() {}

    /** 身份只有一个来源：过滤器验签后写进请求属性的这份。拿不到就抛，绝不回退到请求体（沙箱里跑的是模型写的代码）。 */
    public static HttpGatewayPrincipal requirePrincipal(HttpServletRequest request) {
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof HttpGatewayPrincipal p) return p;
        throw new IllegalStateException("缺少回调身份");
    }

    @SuppressWarnings("unchecked")
    public static List<SkillGrant> requireGrants(HttpServletRequest request) {
        if (request.getAttribute(GRANTS_ATTRIBUTE) instanceof List<?> l) return (List<SkillGrant>) l;
        throw new IllegalStateException("缺少本次运行可用的 skill");
    }
}

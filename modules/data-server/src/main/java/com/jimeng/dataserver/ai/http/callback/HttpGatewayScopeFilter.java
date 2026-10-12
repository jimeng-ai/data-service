package com.jimeng.dataserver.ai.http.callback;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.ai.http.SkillGrant;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通用 HTTP 网关回调的资源范围过滤器。逐条照抄 {@code ConnectorAgentScopeFilter}，只换三处：前缀与 purpose
 * （{@link HttpGatewayTokens}）、资源绑定（{@link HttpGatewayRunRegistry#activeGrants}，同时取回本次可用的 skill）。
 * 请求体不在这里缓存：/script 的请求体可达 20 MB，由控制器有界地读。
 *
 * <p>网关的验签不能替代这里：data-server 的端口能被本机直连、绕过网关；而这枚凭证必须被限制在「本次还在跑的那一次运行」上，
 * 也绝不能拿去访问普通接口。
 *
 * <p>顺序 +57：在 TenantContextFilter(+50) 之后（要读租户），在连接器(+56) / 语义层(+55) 两个过滤器之后——
 * 它们只拦自己的 purpose，网关凭据会原样放过来。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 57)
public class HttpGatewayScopeFilter implements Filter {

    private static final Pattern DANGEROUS_ENCODED_PATH = Pattern.compile("%(?:25)*(?:2e|2f|5c)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_CALLBACK_BOUNDARY = Pattern.compile("^%(?:25)*(?:2f|5c)", Pattern.CASE_INSENSITIVE);

    private record Claims(String tenantId, Long userId, Long agentId, String runId) {}

    private final HttpGatewayRunRegistry runRegistry;
    private final JwtSecretProvider jwtSecretProvider;
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    public HttpGatewayScopeFilter(HttpGatewayRunRegistry runRegistry, JwtSecretProvider jwtSecretProvider) {
        this.runRegistry = runRegistry;
        this.jwtSecretProvider = jwtSecretProvider;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        String token = req.getHeader(HttpGatewayTokens.HEADER_AUTHORIZATION);
        JWT jwt = parse(token);
        boolean ours = jwt != null && HttpGatewayTokens.PURPOSE.equals(stringClaim(jwt.getPayload().getClaim(HttpGatewayTokens.CLAIM_PURPOSE)));

        String path;
        try {
            path = StringUtils.cleanPath(urlPathHelper.getPathWithinApplication(req));
        } catch (RuntimeException invalid) {
            if (ours || rawLooksLikeCallback(req.getRequestURI())) {
                forbid(resp, "回调路径非法");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        boolean callbackPath = path.startsWith(HttpGatewayTokens.CALLBACK_PREFIX);
        if (isSuspicious(req.getRequestURI()) && (callbackPath || ours || rawLooksLikeCallback(req.getRequestURI()))) {
            forbid(resp, "回调路径非法");
            return;
        }
        if (!callbackPath) {
            // ★ 反向限制：窄凭据不得变成全权凭据，泄漏出去也只能打网关回调。
            if (ours) {
                forbid(resp, "运行凭据只能访问回调接口");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!ours || !validSignatureAndExpiration(token, jwt)) {
            forbid(resp, "回调凭据无效");
            return;
        }
        Claims c = crossCheck(jwt.getPayload(), req);
        if (c == null) {
            forbid(resp, "回调凭据范围不匹配");
            return;
        }
        List<SkillGrant> grants = runRegistry.activeGrants(c.tenantId(), c.runId(), c.agentId());
        if (grants == null) {
            forbid(resp, "本次运行已结束或凭据已失效");
            return;
        }
        req.setAttribute(HttpGatewayTokens.PRINCIPAL_ATTRIBUTE, new HttpGatewayPrincipal(c.tenantId(), c.userId(), c.agentId(), c.runId()));
        req.setAttribute(HttpGatewayTokens.GRANTS_ATTRIBUTE, grants);
        String previous = MDC.get(HttpGatewayTokens.MDC_HTTP_RUN);
        MDC.put(HttpGatewayTokens.MDC_HTTP_RUN, c.runId());
        try {
            chain.doFilter(req, response);
        } finally {
            if (previous == null) MDC.remove(HttpGatewayTokens.MDC_HTTP_RUN);
            else MDC.put(HttpGatewayTokens.MDC_HTTP_RUN, previous);
        }
    }

    /** 只解析不验签，用来分流。取 Authorization 原值、不剥 Bearer：本栈的契约就是裸 JWT。 */
    private static JWT parse(String token) {
        if (!StringUtils.hasText(token)) return null;
        try {
            return JWTUtil.parseToken(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 强制三段、第三段非空、alg=HS256（防 alg=none 伪造），并且 exp 必须存在且未过期。 */
    private boolean validSignatureAndExpiration(String token, JWT jwt) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3 || !StringUtils.hasText(segments[2]) || !"HS256".equals(jwt.getAlgorithm())) return false;
            if (!JWTUtil.verify(token, JWTSignerUtil.hs256(jwtSecretProvider.key()))) return false;
            Long exp = longClaim(jwt.getPayload().getClaim(JWTPayload.EXPIRES_AT));
            return exp != null && Instant.now().getEpochSecond() < exp;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** token 自称的租户 / 用户必须与网关据同一枚 token 注入的头一致。 */
    private static Claims crossCheck(JWTPayload payload, HttpServletRequest req) {
        String tenantId = stringClaim(payload.getClaim(HttpGatewayTokens.CLAIM_TENANT_ID));
        String userId = stringClaim(payload.getClaim(HttpGatewayTokens.CLAIM_USER_ID));
        String realm = stringClaim(payload.getClaim(HttpGatewayTokens.CLAIM_REALM));
        String runId = stringClaim(payload.getClaim(HttpGatewayTokens.CLAIM_RUN_ID));
        if (!StringUtils.hasText(tenantId) || !Objects.equals(tenantId, req.getHeader(HttpGatewayTokens.HEADER_TENANT_ID))
                || !StringUtils.hasText(userId) || !Objects.equals(userId, req.getHeader(HttpGatewayTokens.HEADER_USER_ID))
                || !PlatformConstant.REALM_ENTERPRISE.equals(realm) || !StringUtils.hasText(runId)) {
            return null;
        }
        Long uid = longClaim(userId);
        Long agentId = longClaim(payload.getClaim(HttpGatewayTokens.CLAIM_AGENT_ID));
        return uid == null || agentId == null ? null : new Claims(tenantId, uid, agentId, runId);
    }

    private static String stringClaim(Object claim) {
        if (claim == null) return null;
        String v = String.valueOf(claim);
        return "null".equals(v) ? null : v;
    }

    private static Long longClaim(Object claim) {
        String v = stringClaim(claim);
        if (!StringUtils.hasText(v)) return null;
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSuspicious(String rawUri) {
        return rawUri != null && (rawUri.contains("..") || rawUri.contains(";") || DANGEROUS_ENCODED_PATH.matcher(rawUri).find());
    }

    private static boolean rawLooksLikeCallback(String rawUri) {
        if (rawUri == null) return false;
        String base = HttpGatewayTokens.CALLBACK_PREFIX.substring(0, HttpGatewayTokens.CALLBACK_PREFIX.length() - 1);
        if (!rawUri.startsWith(base)) return false;
        String suffix = rawUri.substring(base.length());
        return suffix.isEmpty() || suffix.startsWith("/") || ENCODED_CALLBACK_BOUNDARY.matcher(suffix).find();
    }

    private static void forbid(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"success\":false,\"respCode\":\"4030\",\"respMsg\":\"" + message + "\",\"data\":null}");
    }
}

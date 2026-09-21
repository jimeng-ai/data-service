package com.jimeng.dataserver.ai.connector.agent.callback;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 对话 agent 连接器回调的资源范围过滤器。逐条照抄
 * {@code SemanticAgentScopeFilter}，只换掉「资源绑定」那一步。
 *
 * <p>网关的验签不能替代这里的资源授权：data-server 的 :8020 可以被本机直连、完全绕过网关，
 * 而 connector-agent token 必须被限制在「本次还在跑的那一次运行」上，也绝不能拿去访问普通管理接口。
 *
 * <h3>与语义层那份的三处差异</h3>
 * <ol>
 *   <li>前缀常量：{@link ConnectorAgentTokens#CALLBACK_PREFIX}；</li>
 *   <li>purpose 常量：{@link ConnectorAgentTokens#PURPOSE}；</li>
 *   <li>资源绑定：语义层查 {@code connector_semantic_generation} 的
 *       status / connector_id / current_slice_no / current_run_id；这里查
 *       {@link ConnectorAgentRunRegistry#isActive}（对话 run 没有那样一张表，见该类注释）。</li>
 * </ol>
 * 其余每一条都原样保留——它们防的每一件事在这条回调上同样成立。
 *
 * <h3>★ 顺序：TenantContextFilter(+50) 之后、AccountStatusFilter(+60) 之前</h3>
 * 之后：本过滤器与控制器都要读 {@code TenantContext}；之前：回调不是人在点界面，
 * 不该被账号状态那套（停用、改密后强制重登）拦下。与 {@code SemanticAgentScopeFilter}(+55) 错开一位，
 * 两份过滤器各判各的 purpose，互不吞对方的请求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 56)
public class ConnectorAgentScopeFilter implements Filter {

    private static final String BODY_TOO_LARGE_MESSAGE = "请求体超过 256KB";
    private static final Pattern DANGEROUS_ENCODED_PATH = Pattern.compile(
            "%(?:25)*(?:2e|2f|5c)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_CALLBACK_BOUNDARY = Pattern.compile(
            "^%(?:25)*(?:2f|5c)", Pattern.CASE_INSENSITIVE);

    private final ConnectorAgentRunRegistry runRegistry;
    private final JwtSecretProvider jwtSecretProvider;
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    public ConnectorAgentScopeFilter(ConnectorAgentRunRegistry runRegistry,
                                     JwtSecretProvider jwtSecretProvider) {
        this.runRegistry = runRegistry;
        this.jwtSecretProvider = jwtSecretProvider;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        ParsedCredential credential = parseCredential(httpRequest.getHeader(ConnectorAgentTokens.HEADER_AUTHORIZATION));
        String applicationPath;
        try {
            applicationPath = StringUtils.cleanPath(urlPathHelper.getPathWithinApplication(httpRequest));
        } catch (RuntimeException invalidPath) {
            if (credential.connectorAgent() || rawLooksLikeCallback(httpRequest.getRequestURI())) {
                writeForbidden(httpResponse, "回调路径非法");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        boolean callbackPath = applicationPath.startsWith(ConnectorAgentTokens.CALLBACK_PREFIX);
        boolean rawCallbackIntent = rawLooksLikeCallback(httpRequest.getRequestURI());
        if (isSuspiciousRawUri(httpRequest.getRequestURI())
                && (callbackPath || rawCallbackIntent || credential.connectorAgent())) {
            writeForbidden(httpResponse, "回调路径非法");
            return;
        }

        if (!callbackPath) {
            // ★ 反向限制：一枚回调 token 不得变成全权 token。少了这一条，泄漏出去的窄 token
            // 就能去调 /data/admin/**，而那些接口本身只看「验签通过 + realm」。
            if (credential.connectorAgent()) {
                writeForbidden(httpResponse, "connector-agent 凭据只能访问回调接口");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (!credential.connectorAgent() || credential.jwt() == null) {
            // 回调路径 + 无 purpose：普通登录 token（12 小时、全权）绝不能从这条路进来。
            writeForbidden(httpResponse, "回调凭据无效");
            return;
        }

        String token = httpRequest.getHeader(ConnectorAgentTokens.HEADER_AUTHORIZATION);
        if (!validSignatureAndExpiration(token, credential.jwt())) {
            writeForbidden(httpResponse, "回调凭据无效");
            return;
        }

        Claims claims = parseAndCrossCheckClaims(credential.jwt().getPayload(), httpRequest);
        if (claims == null) {
            writeForbidden(httpResponse, "回调凭据范围不匹配");
            return;
        }

        // ★ 资源绑定：语义层在这里查批次行，对话 run 查在跑登记。fail-closed 在 registry 一侧。
        if (!runRegistry.isActive(claims.tenantId(), claims.runId(), claims.agentId())) {
            writeForbidden(httpResponse, "本次运行已结束或凭据已失效");
            return;
        }

        ConnectorAgentPrincipal principal = new ConnectorAgentPrincipal(
                claims.tenantId(), claims.userId(), claims.agentId(), claims.runId());
        httpRequest.setAttribute(ConnectorAgentTokens.PRINCIPAL_ATTRIBUTE, principal);

        String previousConnectorRun = MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN);
        MDC.put(ConnectorAgentTokens.MDC_CONNECTOR_RUN, claims.runId());
        try {
            long contentLength = httpRequest.getContentLengthLong();
            if (contentLength > ConnectorAgentTokens.MAX_BODY_BYTES) {
                writeBodyTooLarge(httpResponse);
                return;
            }

            HttpServletRequest requestForChain = httpRequest;
            if (contentLength < 0) {
                try {
                    requestForChain = new CachedBodyRequestWrapper(httpRequest);
                } catch (BodyTooLargeException tooLarge) {
                    writeChunkedBodyTooLarge(httpResponse);
                    return;
                }
            }
            chain.doFilter(requestForChain, response);
        } finally {
            // 恢复旧值而不是无脑 remove：这是请求线程池的线程，remove 会把外层放进去的值一起抹掉。
            if (previousConnectorRun == null) {
                MDC.remove(ConnectorAgentTokens.MDC_CONNECTOR_RUN);
            } else {
                MDC.put(ConnectorAgentTokens.MDC_CONNECTOR_RUN, previousConnectorRun);
            }
        }
    }

    /**
     * 只解析、不验签，用来给请求分流（是不是一枚回调凭据）。
     *
     * <p>取 Authorization 的<b>原值、不剥 Bearer</b>：本栈的契约就是裸 JWT，
     * 剥前缀等于悄悄接受一种我们并不签发的形态。
     */
    private ParsedCredential parseCredential(String token) {
        if (!StringUtils.hasText(token)) {
            return ParsedCredential.ordinary();
        }
        try {
            JWT jwt = JWTUtil.parseToken(token);
            Object purpose = jwt.getPayload().getClaim(ConnectorAgentTokens.CLAIM_PURPOSE);
            return new ParsedCredential(jwt, ConnectorAgentTokens.PURPOSE.equals(stringClaim(purpose)));
        } catch (RuntimeException ignored) {
            return ParsedCredential.ordinary();
        }
    }

    /**
     * ★ 强制三段 + 第三段非空 + {@code alg == HS256}。
     *
     * <p>:8020 能被本机直连绕过网关，而 {@code alg=none} 的伪造 token 在 claims 全匹配时
     * 会被某些实现当成"无需验签"放过——只校验 claims 是不够的。
     */
    private boolean validSignatureAndExpiration(String token, JWT jwt) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3 || !StringUtils.hasText(segments[2])
                    || !"HS256".equals(jwt.getAlgorithm())) {
                return false;
            }
            if (!JWTUtil.verify(token, JWTSignerUtil.hs256(jwtSecretProvider.key()))) {
                return false;
            }
            Long expiresAt = longClaim(jwt.getPayload().getClaim(JWTPayload.EXPIRES_AT));
            return expiresAt != null && Instant.now().getEpochSecond() < expiresAt;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * claims ↔ 网关注入的头交叉核对。token 自称的租户 / 用户必须与网关据同一枚 token 注入的头一致：
     * 对不上说明这枚 token 和这条请求不是一回事（头被改了，或 token 被搬到了别的请求上）。
     */
    private Claims parseAndCrossCheckClaims(JWTPayload payload, HttpServletRequest request) {
        String tenantId = stringClaim(payload.getClaim(ConnectorAgentTokens.CLAIM_TENANT_ID));
        String userId = stringClaim(payload.getClaim(ConnectorAgentTokens.CLAIM_USER_ID));
        String realm = stringClaim(payload.getClaim(ConnectorAgentTokens.CLAIM_REALM));
        String runId = stringClaim(payload.getClaim(ConnectorAgentTokens.CLAIM_RUN_ID));

        if (!StringUtils.hasText(tenantId)
                || !Objects.equals(tenantId, request.getHeader(ConnectorAgentTokens.HEADER_TENANT_ID))
                || !StringUtils.hasText(userId)
                || !Objects.equals(userId, request.getHeader(ConnectorAgentTokens.HEADER_USER_ID))
                || !PlatformConstant.REALM_ENTERPRISE.equals(realm)
                || !StringUtils.hasText(runId)) {
            return null;
        }

        Long numericUserId = longClaim(userId);
        Long agentId = longClaim(payload.getClaim(ConnectorAgentTokens.CLAIM_AGENT_ID));
        if (numericUserId == null || agentId == null) {
            return null;
        }
        return new Claims(tenantId, numericUserId, agentId, runId);
    }

    private static String stringClaim(Object claim) {
        if (claim == null) {
            return null;
        }
        String value = String.valueOf(claim);
        return "null".equals(value) ? null : value;
    }

    private static Long longClaim(Object claim) {
        String value = stringClaim(claim);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isSuspiciousRawUri(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        return containsDangerousPathSequence(rawUri);
    }

    private static boolean containsDangerousPathSequence(String uri) {
        return uri.contains("..")
                || uri.contains(";")
                || DANGEROUS_ENCODED_PATH.matcher(uri).find();
    }

    private static boolean rawLooksLikeCallback(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        String callbackBase = ConnectorAgentTokens.CALLBACK_PREFIX.substring(
                0, ConnectorAgentTokens.CALLBACK_PREFIX.length() - 1);
        if (!rawUri.startsWith(callbackBase)) {
            return false;
        }
        String suffix = rawUri.substring(callbackBase.length());
        if (suffix.isEmpty() || suffix.startsWith("/")) {
            return true;
        }

        // 一次线性匹配任意层 %25 包装；相邻路由名如 connector-agent-report 不是回调意图。
        return ENCODED_CALLBACK_BOUNDARY.matcher(suffix).find();
    }

    private static void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        writeEnvelope(response, "4030", message);
    }

    private static void writeBodyTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        writeEnvelope(response, "4000", BODY_TOO_LARGE_MESSAGE);
    }

    private static void writeChunkedBodyTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        writeEnvelope(response, "5005", BODY_TOO_LARGE_MESSAGE);
    }

    private static void writeEnvelope(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"success\":false,\"respCode\":\"" + code
                + "\",\"respMsg\":\"" + message + "\",\"data\":null}");
    }

    private record ParsedCredential(JWT jwt, boolean connectorAgent) {
        private static ParsedCredential ordinary() {
            return new ParsedCredential(null, false);
        }
    }

    private record Claims(String tenantId, Long userId, Long agentId, String runId) {
    }

    /**
     * Wrapper 只用于没有 Content-Length 的请求。进控制器前<b>有界预读并全缓存</b>：
     * Jackson 可能在完整首个 JSON 值后停读，懒限流 wrapper 会因此放过后缀的数百 KB 空白。
     */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;
        private ServletInputStream replayInputStream;
        private BufferedReader replayReader;

        private CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.body = readBounded(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            if (replayReader != null) {
                throw new IllegalStateException("getReader() 已被调用");
            }
            if (replayInputStream == null) {
                replayInputStream = new CachedServletInputStream(body);
            }
            return replayInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (replayReader != null) {
                return replayReader;
            }
            if (replayInputStream != null) {
                throw new IllegalStateException("getInputStream() 已被调用");
            }
            replayInputStream = new CachedServletInputStream(body);
            String encoding = getCharacterEncoding();
            replayReader = new BufferedReader(new InputStreamReader(replayInputStream,
                    StringUtils.hasText(encoding) ? encoding : StandardCharsets.UTF_8.name()));
            return replayReader;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private static byte[] readBounded(ServletInputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(ConnectorAgentTokens.MAX_BODY_BYTES, 8192));
            byte[] buffer = new byte[8192];
            while (output.size() <= ConnectorAgentTokens.MAX_BODY_BYTES) {
                int remainingThroughOverflow = ConnectorAgentTokens.MAX_BODY_BYTES + 1 - output.size();
                int read = input.read(buffer, 0, Math.min(buffer.length, remainingThroughOverflow));
                if (read < 0) {
                    return output.toByteArray();
                }
                output.write(buffer, 0, read);
                if (output.size() > ConnectorAgentTokens.MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
            }
            throw new BodyTooLargeException();
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                throw new IllegalArgumentException("readListener 不能为 null");
            }
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException e) {
                readListener.onError(e);
            }
        }
    }

    private static final class BodyTooLargeException extends IOException {
    }
}

package com.jimeng.dataserver.ai.connector.generation.callback;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 语义层 agent 回调的资源范围过滤器。
 *
 * <p>网关的验签不能替代这里的资源授权：data-server 的本地端口可以被直连，而且 semantic-agent token
 * 必须被限制在单个批次、连接、片号和本次 runId 上，也绝不能拿去访问普通管理接口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 55)
public class SemanticAgentScopeFilter implements Filter {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String BODY_TOO_LARGE_MESSAGE = "请求体超过 256KB";

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final JwtSecretProvider jwtSecretProvider;
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    public SemanticAgentScopeFilter(ConnectorSemanticGenerationMapper generationMapper,
                                    JwtSecretProvider jwtSecretProvider) {
        this.generationMapper = generationMapper;
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

        ParsedCredential credential = parseCredential(httpRequest.getHeader(SemanticAgentTokens.HEADER_AUTHORIZATION));
        String applicationPath;
        try {
            applicationPath = StringUtils.cleanPath(urlPathHelper.getPathWithinApplication(httpRequest));
        } catch (RuntimeException invalidPath) {
            if (credential.semanticAgent() || rawLooksLikeCallback(httpRequest.getRequestURI())) {
                writeForbidden(httpResponse, "回调路径非法");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        boolean callbackPath = applicationPath.startsWith(SemanticAgentTokens.CALLBACK_PREFIX);
        boolean rawCallbackIntent = rawLooksLikeCallback(httpRequest.getRequestURI());
        if (isSuspiciousRawUri(httpRequest.getRequestURI())
                && (callbackPath || rawCallbackIntent || credential.semanticAgent())) {
            writeForbidden(httpResponse, "回调路径非法");
            return;
        }

        if (!callbackPath) {
            if (credential.semanticAgent()) {
                writeForbidden(httpResponse, "semantic-agent 凭据只能访问回调接口");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (!credential.semanticAgent() || credential.jwt() == null) {
            writeForbidden(httpResponse, "回调凭据无效");
            return;
        }

        String token = httpRequest.getHeader(SemanticAgentTokens.HEADER_AUTHORIZATION);
        if (!validSignatureAndExpiration(token, credential.jwt())) {
            writeForbidden(httpResponse, "回调凭据无效");
            return;
        }

        Claims claims = parseAndCrossCheckClaims(credential.jwt().getPayload(), httpRequest);
        if (claims == null) {
            writeForbidden(httpResponse, "回调凭据范围不匹配");
            return;
        }

        ConnectorSemanticGeneration generation = generationMapper.selectById(claims.generationId());
        if (!matchesRunningGeneration(generation, claims)) {
            writeForbidden(httpResponse, "语义层生成批次已结束或已换片");
            return;
        }

        SemanticAgentPrincipal principal = new SemanticAgentPrincipal(
                claims.tenantId(), claims.userId(), claims.generationId(), claims.connectorId(),
                claims.sliceNo(), claims.runId(), generation.getMode());
        httpRequest.setAttribute(SemanticAgentTokens.PRINCIPAL_ATTRIBUTE, principal);

        String previousSemanticGeneration = MDC.get(SemanticAgentTokens.MDC_SEMANTIC_GENERATION);
        MDC.put(SemanticAgentTokens.MDC_SEMANTIC_GENERATION, String.valueOf(claims.generationId()));
        try {
            long contentLength = httpRequest.getContentLengthLong();
            if (contentLength > SemanticAgentTokens.MAX_BODY_BYTES) {
                writeBodyTooLarge(httpResponse);
                return;
            }

            HttpServletRequest requestForChain = contentLength < 0
                    ? new SizeLimitedRequestWrapper(httpRequest)
                    : httpRequest;
            chain.doFilter(requestForChain, response);
        } finally {
            if (previousSemanticGeneration == null) {
                MDC.remove(SemanticAgentTokens.MDC_SEMANTIC_GENERATION);
            } else {
                MDC.put(SemanticAgentTokens.MDC_SEMANTIC_GENERATION, previousSemanticGeneration);
            }
        }
    }

    private ParsedCredential parseCredential(String token) {
        if (!StringUtils.hasText(token)) {
            return ParsedCredential.ordinary();
        }
        try {
            JWT jwt = JWTUtil.parseToken(token);
            Object purpose = jwt.getPayload().getClaim(SemanticAgentTokens.CLAIM_PURPOSE);
            return new ParsedCredential(jwt, SemanticAgentTokens.PURPOSE.equals(stringClaim(purpose)));
        } catch (RuntimeException ignored) {
            return ParsedCredential.ordinary();
        }
    }

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

    private Claims parseAndCrossCheckClaims(JWTPayload payload, HttpServletRequest request) {
        String tenantId = stringClaim(payload.getClaim(SemanticAgentTokens.CLAIM_TENANT_ID));
        String userId = stringClaim(payload.getClaim(SemanticAgentTokens.CLAIM_USER_ID));
        String realm = stringClaim(payload.getClaim(SemanticAgentTokens.CLAIM_REALM));
        String runId = stringClaim(payload.getClaim(SemanticAgentTokens.CLAIM_RUN_ID));

        if (!StringUtils.hasText(tenantId)
                || !Objects.equals(tenantId, request.getHeader(SemanticAgentTokens.HEADER_TENANT_ID))
                || !StringUtils.hasText(userId)
                || !Objects.equals(userId, request.getHeader(SemanticAgentTokens.HEADER_USER_ID))
                || !PlatformConstant.REALM_ENTERPRISE.equals(realm)
                || !StringUtils.hasText(runId)) {
            return null;
        }

        Long generationId = longClaim(payload.getClaim(SemanticAgentTokens.CLAIM_GENERATION_ID));
        Long connectorId = longClaim(payload.getClaim(SemanticAgentTokens.CLAIM_CONNECTOR_ID));
        Integer sliceNo = intClaim(payload.getClaim(SemanticAgentTokens.CLAIM_SLICE_NO));
        if (generationId == null || connectorId == null || sliceNo == null) {
            return null;
        }
        return new Claims(tenantId, userId, generationId, connectorId, sliceNo, runId);
    }

    private boolean matchesRunningGeneration(ConnectorSemanticGeneration generation, Claims claims) {
        return generation != null
                && STATUS_RUNNING.equals(generation.getStatus())
                && Objects.equals(generation.getConnectorId(), claims.connectorId())
                && Objects.equals(generation.getCurrentSliceNo(), claims.sliceNo())
                && Objects.equals(generation.getCurrentRunId(), claims.runId());
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

    private static Integer intClaim(Object claim) {
        String value = stringClaim(claim);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isSuspiciousRawUri(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        String candidate = rawUri.toLowerCase(Locale.ROOT);
        while (true) {
            if (containsDangerousPathSequence(candidate)) {
                return true;
            }
            String folded = candidate.replace("%25", "%");
            if (folded.equals(candidate)) {
                return false;
            }
            // 每个被折叠的 %25 都缩短两个字符；因此即使编码层数来自输入，本循环也必然终止。
            candidate = folded;
        }
    }

    private static boolean containsDangerousPathSequence(String uri) {
        return uri.contains("..")
                || uri.contains(";")
                || uri.contains("%2e")
                || uri.contains("%2f")
                || uri.contains("%5c");
    }

    private static boolean rawLooksLikeCallback(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        String callbackBase = SemanticAgentTokens.CALLBACK_PREFIX.substring(
                0, SemanticAgentTokens.CALLBACK_PREFIX.length() - 1);
        if (!rawUri.startsWith(callbackBase)) {
            return false;
        }
        String suffix = rawUri.substring(callbackBase.length());
        if (suffix.isEmpty() || suffix.startsWith("/")) {
            return true;
        }

        // 只折叠 base 后的 percent 包装；相邻路由名如 semantic-agent-report 不是回调意图。
        String folded = suffix.toLowerCase(Locale.ROOT);
        while (folded.startsWith("%25")) {
            folded = "%" + folded.substring(3);
        }
        return folded.startsWith("%2f") || folded.startsWith("%5c");
    }

    private static void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        writeEnvelope(response, "4030", message);
    }

    private static void writeBodyTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        writeEnvelope(response, "4000", BODY_TOO_LARGE_MESSAGE);
    }

    private static void writeEnvelope(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"success\":false,\"respCode\":\"" + code
                + "\",\"respMsg\":\"" + message + "\",\"data\":null}");
    }

    private record ParsedCredential(JWT jwt, boolean semanticAgent) {
        private static ParsedCredential ordinary() {
            return new ParsedCredential(null, false);
        }
    }

    private record Claims(String tenantId, String userId, long generationId, long connectorId,
                          int sliceNo, String runId) {
    }

    /** Wrapper 只用于没有 Content-Length 的请求；累计读到第 262145 字节时立即失败。 */
    private static final class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {

        private ServletInputStream limitedInputStream;
        private BufferedReader limitedReader;

        private SizeLimitedRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedReader != null) {
                throw new IllegalStateException("getReader() 已被调用");
            }
            if (limitedInputStream == null) {
                limitedInputStream = new LimitedServletInputStream(super.getInputStream());
            }
            return limitedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (limitedReader != null) {
                return limitedReader;
            }
            if (limitedInputStream != null) {
                throw new IllegalStateException("getInputStream() 已被调用");
            }
            limitedInputStream = new LimitedServletInputStream(super.getInputStream());
            String encoding = getCharacterEncoding();
            limitedReader = new BufferedReader(new InputStreamReader(limitedInputStream,
                    StringUtils.hasText(encoding) ? encoding : StandardCharsets.UTF_8.name()));
            return limitedReader;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long delivered;

        private LimitedServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            if (delivered < SemanticAgentTokens.MAX_BODY_BYTES) {
                int value = delegate.read();
                if (value != -1) {
                    delivered++;
                }
                return value;
            }
            int overflow = delegate.read();
            if (overflow == -1) {
                return -1;
            }
            throw new IOException(BODY_TOO_LARGE_MESSAGE);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remaining = SemanticAgentTokens.MAX_BODY_BYTES - delivered;
            if (remaining > 0) {
                int allowed = (int) Math.min(length, remaining);
                int read = delegate.read(bytes, offset, allowed);
                if (read > 0) {
                    delivered += read;
                }
                return read;
            }
            int overflow = delegate.read();
            if (overflow == -1) {
                return -1;
            }
            throw new IOException(BODY_TOO_LARGE_MESSAGE);
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}

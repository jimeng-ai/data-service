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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

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
    private static final Pattern DANGEROUS_ENCODED_PATH = Pattern.compile(
            "%(?:25)*(?:2e|2f|5c)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_CALLBACK_BOUNDARY = Pattern.compile(
            "^%(?:25)*(?:2f|5c)", Pattern.CASE_INSENSITIVE);

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
        String callbackBase = SemanticAgentTokens.CALLBACK_PREFIX.substring(
                0, SemanticAgentTokens.CALLBACK_PREFIX.length() - 1);
        if (!rawUri.startsWith(callbackBase)) {
            return false;
        }
        String suffix = rawUri.substring(callbackBase.length());
        if (suffix.isEmpty() || suffix.startsWith("/")) {
            return true;
        }

        // 一次线性匹配任意层 %25 包装；相邻路由名如 semantic-agent-report 不是回调意图。
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

    private record ParsedCredential(JWT jwt, boolean semanticAgent) {
        private static ParsedCredential ordinary() {
            return new ParsedCredential(null, false);
        }
    }

    private record Claims(String tenantId, String userId, long generationId, long connectorId,
                          int sliceNo, String runId) {
    }

    /**
     * Wrapper 只用于没有 Content-Length 的请求。进控制器前有界预读并缓存：
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
                    Math.min(SemanticAgentTokens.MAX_BODY_BYTES, 8192));
            byte[] buffer = new byte[8192];
            while (output.size() <= SemanticAgentTokens.MAX_BODY_BYTES) {
                int remainingThroughOverflow = SemanticAgentTokens.MAX_BODY_BYTES + 1 - output.size();
                int read = input.read(buffer, 0, Math.min(buffer.length, remainingThroughOverflow));
                if (read < 0) {
                    return output.toByteArray();
                }
                output.write(buffer, 0, read);
                if (output.size() > SemanticAgentTokens.MAX_BODY_BYTES) {
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

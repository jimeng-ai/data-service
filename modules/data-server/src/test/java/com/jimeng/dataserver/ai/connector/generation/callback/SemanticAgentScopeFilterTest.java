package com.jimeng.dataserver.ai.connector.generation.callback;

import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.common.core.tenant.TenantContextFilter;
import com.jimeng.dataserver.admin.common.AccountStatusFilter;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义层 agent 回调的最外层资源授权：既钉住「窄 token 只能进回调前缀」，也钉住
 * 「普通登录 token 绝不能进回调」和正在运行批次的逐项绑定。
 */
class SemanticAgentScopeFilterTest {

    private static final String SECRET = "semantic-scope-filter-secret-0123456789abcdef";
    private static final byte[] OTHER_SECRET =
            "semantic-scope-other-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "1900000000000000001";
    private static final long GENERATION_ID = 2099066794181541890L;
    private static final long CONNECTOR_ID = 2099066794181541891L;
    private static final int SLICE_NO = 3;
    private static final String RUN_ID = "semgen-2099066794181541890-s3-a2";
    private static final String CALLBACK_PATH = "/data/internal/semantic-agent/run-scope";

    private ConnectorSemanticGenerationMapper generationMapper;
    private SemanticAgentScopeFilter filter;

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        JwtSecretProvider secretProvider = new JwtSecretProvider();
        ReflectionTestUtils.setField(secretProvider, "secret", SECRET);
        filter = new SemanticAgentScopeFilter(generationMapper, secretProvider);
        when(generationMapper.selectById(GENERATION_ID)).thenReturn(generation("RUNNING"));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("普通 token 访问回调前缀返回 403")
    void 普通token访问回调前缀403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(false, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("purpose token 访问回调前缀之外返回 403")
    void purpose_token访问回调前缀之外403() throws Exception {
        MockHttpServletRequest request = request("/data/admin/connectors/1", semanticToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @ParameterizedTest(name = "危险原始路径 {0}")
    @ValueSource(strings = {
            "/data/internal/semantic-agent/../admin/connectors/1",
            "/data/internal/semantic-agent/%2e%2e/admin/connectors/1",
            "/data/internal/semantic-agent%2Fsubmit",
            "/data/internal/semantic-agent%5csubmit",
            "/data/internal/semantic-agent/submit;jsessionid=x"
    })
    @DisplayName("点点、编码点/斜杠/反斜杠与分号绕过返回 403")
    void 点点与编码斜杠绕过403(String rawPath) throws Exception {
        // 点点路径规范化后会落到回调前缀之外，靠 semantic purpose 拦；其余路径解码后仍是回调，
        // 故刻意使用普通 token，证明不是单靠 purpose 碰巧拦住。
        String token = rawPath.contains("..") || rawPath.toLowerCase().contains("%2e")
                ? semanticToken()
                : ordinaryToken();
        MockHttpServletRequest request = request(rawPath, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("普通 token 的原始回调路径即使规范化后离开回调前缀也返回 403")
    void 普通token的raw回调遍历403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/semantic-agent/%2e%2e/admin/connectors/1", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("普通 token 不能用双层编码斜杠隐藏原始回调意图")
    void 普通token双层编码斜杠403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/semantic-agent%252fsubmit", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("semantic token 不能用双层编码点隐藏回调路径遍历")
    void semantic_token双层编码点403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/semantic-agent/%252e%252e/admin/connectors/1", semanticToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("普通 token 的正常非回调 URL 不因双层编码被误伤")
    void 正常非回调双层编码放行() throws Exception {
        MockHttpServletRequest request = request("/data/public/%252fasset", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertTrue(reached.get());
        assertEquals(200, response.getStatus());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("semantic token 签名错误返回 403")
    void 签名错误403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(true, OTHER_SECRET, 60_000, TENANT_ID, USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("签名错误仍进入控制器");
        });

        assertForbidden(request, response);
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("alg=none 的无签名 semantic token 即使 claims 与批次全匹配也返回 403")
    void 无签名_none算法403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, unsignedNoneToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("过期 semantic token 返回 403")
    void 过期403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(true, SECRET.getBytes(StandardCharsets.UTF_8), -60_000,
                        TENANT_ID, USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("过期 token 仍进入控制器");
        });

        assertForbidden(request, response);
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("tenant_id claim 与 X-Tenant-Id 头不符返回 403")
    void tenant_id与头不符403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        "tenant-b", USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("跨租户 token 仍进入控制器");
        });

        assertForbidden(request, response);
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("id claim 与 user-id 头不符返回 403")
    void id与user_id头不符403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        TENANT_ID, "1900000000000000099",
                        CONNECTOR_ID, SLICE_NO, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("错用户 token 仍进入控制器");
        });

        assertForbidden(request, response);
        verify(generationMapper, never()).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("批次不是 RUNNING 返回 403")
    void 批次非RUNNING403() throws Exception {
        when(generationMapper.selectById(GENERATION_ID)).thenReturn(generation("READY"));
        assertBatchRejected(semanticToken());
    }

    @Test
    @DisplayName("cid 与批次 connectorId 不符返回 403")
    void cid不符403() throws Exception {
        assertBatchRejected(token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000, TENANT_ID, USER_ID,
                CONNECTOR_ID + 1, SLICE_NO, RUN_ID));
    }

    @Test
    @DisplayName("slice 与批次 currentSliceNo 不符返回 403")
    void slice不符403() throws Exception {
        assertBatchRejected(token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000, TENANT_ID, USER_ID,
                CONNECTOR_ID, SLICE_NO + 1, RUN_ID));
    }

    @Test
    @DisplayName("rid 与批次 currentRunId 不符返回 403")
    void rid不符403() throws Exception {
        assertBatchRejected(token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000, TENANT_ID, USER_ID,
                CONNECTOR_ID, SLICE_NO, RUN_ID + "-old"));
    }

    @Test
    @DisplayName("合法 token 放行，写入 principal 与 semanticGen MDC")
    void 合法token放行并写入principal() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, semanticToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<SemanticAgentPrincipal> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) -> {
            seen.set(SemanticAgentTokens.requirePrincipal((HttpServletRequest) req));
            assertEquals(String.valueOf(GENERATION_ID), MDC.get(SemanticAgentTokens.MDC_SEMANTIC_GENERATION));
        });

        SemanticAgentPrincipal principal = seen.get();
        assertEquals(new SemanticAgentPrincipal(TENANT_ID, USER_ID, GENERATION_ID, CONNECTOR_ID,
                SLICE_NO, RUN_ID, "DIRECT"), principal);
        assertEquals(principal, request.getAttribute(SemanticAgentTokens.PRINCIPAL_ATTRIBUTE));
        assertNull(MDC.get(SemanticAgentTokens.MDC_SEMANTIC_GENERATION), "过滤器返回后不能把批次号串到下一请求");
        assertEquals(200, response.getStatus());
        verify(generationMapper).selectById(GENERATION_ID);
    }

    @Test
    @DisplayName("Order 位于 TenantContextFilter 与 AccountStatusFilter 之间")
    void Order位于TenantContextFilter与AccountStatusFilter之间() {
        int tenantOrder = OrderUtils.getOrder(TenantContextFilter.class, 0);
        int semanticOrder = OrderUtils.getOrder(SemanticAgentScopeFilter.class, 0);
        int accountOrder = OrderUtils.getOrder(AccountStatusFilter.class, 0);

        assertTrue(tenantOrder < semanticOrder, "必须在 TenantContextFilter 已设置租户上下文之后查批次");
        assertTrue(semanticOrder < accountOrder, "路径越权必须在账号状态查库之前被挡住");
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 55, semanticOrder);
        assertInstanceOf(Order.class, SemanticAgentScopeFilter.class.getAnnotation(Order.class));
    }

    @Test
    @DisplayName("Content-Length 超过 256KB 返回 HTTP 200 / 4000")
    void Content_Length超过256KB返回4000() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, semanticToken());
        request.setContent(new byte[SemanticAgentTokens.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertEquals(200, response.getStatus());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"respCode\":\"4000\""));
        assertTrue(response.getContentAsString().contains("请求体超过 256KB"));
        assertFalse(reached.get());
    }

    @Test
    @DisplayName("未知长度的分块请求读到超过 256KB 时抛 IOException，且最多向下游交付 256KB")
    void 分块请求体读超256KB被截断() {
        ChunkedMockHttpServletRequest request = new ChunkedMockHttpServletRequest("POST", CALLBACK_PATH);
        addHeaders(request, semanticToken());
        request.setContent(new byte[SemanticAgentTokens.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicLong delivered = new AtomicLong();
        AtomicReference<ServletRequest> seenRequest = new AtomicReference<>();

        IOException thrown = assertThrows(IOException.class, () -> filter.doFilter(request, response, (req, resp) -> {
            seenRequest.set(req);
            ServletInputStream in = req.getInputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                delivered.addAndGet(read);
            }
        }));

        assertEquals("请求体超过 256KB", thrown.getMessage());
        assertNotSame(request, seenRequest.get(), "未知长度请求必须套限流 wrapper");
        assertEquals(SemanticAgentTokens.MAX_BODY_BYTES, delivered.get());
        assertNull(MDC.get(SemanticAgentTokens.MDC_SEMANTIC_GENERATION));
    }

    private void assertBatchRejected(String token) throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("与批次不匹配仍进入控制器");
        });
        assertForbidden(request, response);
        verify(generationMapper).selectById(GENERATION_ID);
    }

    private static FilterChain markingChain(AtomicBoolean reached) {
        return (request, response) -> reached.set(true);
    }

    private static void assertForbidden(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        assertEquals(403, response.getStatus());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"success\":false"), body);
        assertTrue(body.contains("\"respCode\":\"4030\""), body);
        assertTrue(body.contains("\"data\":null"), body);
        assertFalse(body.contains(request.getHeader("Authorization")), "拒绝文案不得泄漏 token");
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        addHeaders(request, token);
        return request;
    }

    private static void addHeaders(MockHttpServletRequest request, String token) {
        request.addHeader("Authorization", token);
        request.addHeader("X-Tenant-Id", TENANT_ID);
        request.addHeader("user-id", USER_ID);
        request.setContentType("application/json");
    }

    private static String semanticToken() {
        return token(true, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID);
    }

    private static String ordinaryToken() {
        return token(false, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, CONNECTOR_ID, SLICE_NO, RUN_ID);
    }

    private static String token(boolean semanticPurpose, byte[] key, long ttlMs, String tenantId, String userId,
                                long connectorId, int sliceNo, String runId) {
        Date now = new Date();
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put(JWTPayload.EXPIRES_AT, new Date(now.getTime() + ttlMs));
        payload.put("id", userId);
        payload.put("tenant_id", tenantId);
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        if (semanticPurpose) {
            payload.put("purpose", "semantic-agent");
            payload.put("gen", String.valueOf(GENERATION_ID));
            payload.put("cid", String.valueOf(connectorId));
            payload.put("slice", sliceNo);
            payload.put("rid", runId);
        }
        return JWTUtil.createToken(payload, key);
    }

    private static String unsignedNoneToken() {
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        long exp = System.currentTimeMillis() / 1000L + 60;
        String payload = "{\"exp\":" + exp
                + ",\"id\":\"" + USER_ID + "\""
                + ",\"tenant_id\":\"" + TENANT_ID + "\""
                + ",\"realm\":\"" + PlatformConstant.REALM_ENTERPRISE + "\""
                + ",\"purpose\":\"semantic-agent\""
                + ",\"gen\":\"" + GENERATION_ID + "\""
                + ",\"cid\":\"" + CONNECTOR_ID + "\""
                + ",\"slice\":" + SLICE_NO
                + ",\"rid\":\"" + RUN_ID + "\"}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    private static ConnectorSemanticGeneration generation(String status) {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(GENERATION_ID);
        generation.setTenantId(TENANT_ID);
        generation.setConnectorId(CONNECTOR_ID);
        generation.setStatus(status);
        generation.setCurrentSliceNo(SLICE_NO);
        generation.setCurrentRunId(RUN_ID);
        generation.setMode("DIRECT");
        return generation;
    }

    private static final class ChunkedMockHttpServletRequest extends MockHttpServletRequest {
        private ChunkedMockHttpServletRequest(String method, String requestUri) {
            super(method, requestUri);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }
}

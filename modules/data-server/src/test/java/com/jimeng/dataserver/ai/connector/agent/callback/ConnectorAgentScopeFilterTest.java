package com.jimeng.dataserver.ai.connector.agent.callback;

import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.common.core.tenant.TenantContextFilter;
import com.jimeng.dataserver.admin.common.AccountStatusFilter;
import com.jimeng.dataserver.ai.connector.generation.callback.SemanticAgentScopeFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对话 agent 连接器回调的最外层资源授权。
 *
 * <p>与 {@code SemanticAgentScopeFilterTest} 同构：钉住「窄 token 只能进回调前缀」、
 * 「普通登录 token 绝不能进回调」、「回调 token 不得变成全权 token」，以及运行级的逐次绑定。
 * 这些不是功能点，是安全边界——:8020 能被本机直连、完全绕过网关，网关的验签在这里不作数。
 */
class ConnectorAgentScopeFilterTest {

    private static final String SECRET = "connector-scope-filter-secret-0123456789abcdef";
    private static final byte[] OTHER_SECRET =
            "connector-scope-other-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "1900000000000000001";
    private static final long AGENT_ID = 2099066794181541890L;
    private static final String RUN_ID = "run-2099066794181541890-a2";
    private static final String CALLBACK_PATH = "/data/internal/connector-agent/conn_list";

    private ConnectorAgentRunRegistry runRegistry;
    private ConnectorAgentScopeFilter filter;

    @BeforeEach
    void setUp() {
        runRegistry = mock(ConnectorAgentRunRegistry.class);
        JwtSecretProvider secretProvider = new JwtSecretProvider();
        ReflectionTestUtils.setField(secretProvider, "secret", SECRET);
        filter = new ConnectorAgentScopeFilter(runRegistry, secretProvider);
        when(runRegistry.isActive(TENANT_ID, RUN_ID, AGENT_ID)).thenReturn(true);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---------------------------------------------------------------- 前缀与 purpose 的双向限制

    @Test
    @DisplayName("普通登录 token（无 purpose）访问回调前缀返回 403")
    void 普通token访问回调前缀403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("★ 反向限制：connector-agent token 访问回调前缀之外返回 403")
    void connector_token访问回调前缀之外403() throws Exception {
        // 少了这一条，一枚泄漏的窄回调 token 就等于一枚全权 token：
        // /data/admin/** 本身只看「验签通过 + realm」，而这枚 token 两样都满足。
        MockHttpServletRequest request = request("/data/admin/connectors/1", connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @ParameterizedTest(name = "回调前缀之外的路径 {0}")
    @ValueSource(strings = {
            "/data/admin/connectors/1",
            "/data/admin/agents/1/connections",
            "/data/internal/semantic-agent/run-scope",
            "/data/claude/messages",
            "/"
    })
    @DisplayName("connector-agent token 打任何非回调路径都返回 403")
    void connector_token打任何非回调路径403(String path) throws Exception {
        MockHttpServletRequest request = request(path, connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("★ semantic-agent purpose 的 token 打 connector 回调返回 403（两套 purpose 不得互串）")
    void semantic_token访问connector回调403() throws Exception {
        // 复用语义层 token 等于把「一片语义层生成」的凭据变成该 Agent 全部连接器的读写凭据。
        MockHttpServletRequest request = request(CALLBACK_PATH, semanticPurposeToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("两套 purpose 的常量值必须不同，且两个回调前缀互不为字符串前缀")
    void 两套凭据的前缀与purpose互不包含() {
        assertNotEquals(ConnectorAgentTokens.PURPOSE,
                com.jimeng.dataserver.ai.connector.generation.callback.SemanticAgentTokens.PURPOSE);
        String connectorPrefix = ConnectorAgentTokens.CALLBACK_PREFIX;
        String semanticPrefix =
                com.jimeng.dataserver.ai.connector.generation.callback.SemanticAgentTokens.CALLBACK_PREFIX;
        assertFalse(connectorPrefix.startsWith(semanticPrefix),
                "语义层过滤器的 startsWith 会把 connector 回调当成自己的，资源绑定必然判错");
        assertFalse(semanticPrefix.startsWith(connectorPrefix));
    }

    // ---------------------------------------------------------------- 路径遍历

    @ParameterizedTest(name = "危险原始路径 {0}")
    @ValueSource(strings = {
            "/data/internal/connector-agent/../admin/connectors/1",
            "/data/internal/connector-agent/%2e%2e/admin/connectors/1",
            "/data/internal/connector-agent%2Fconn_query",
            "/data/internal/connector-agent%5cconn_query",
            "/data/internal/connector-agent/conn_query;jsessionid=x"
    })
    @DisplayName("点点、编码点/斜杠/反斜杠与分号绕过返回 403")
    void 点点与编码斜杠绕过403(String rawPath) throws Exception {
        // 点点路径规范化后会落到回调前缀之外，靠 connector purpose 拦；其余路径解码后仍是回调，
        // 故刻意使用普通 token，证明不是单靠 purpose 碰巧拦住。
        String token = rawPath.contains("..") || rawPath.toLowerCase().contains("%2e")
                ? connectorToken()
                : ordinaryToken();
        MockHttpServletRequest request = request(rawPath, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("普通 token 的原始回调路径即使规范化后离开回调前缀也返回 403")
    void 普通token的raw回调遍历403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent/%2e%2e/admin/connectors/1", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("普通 token 不能用双层编码斜杠隐藏原始回调意图")
    void 普通token双层编码斜杠403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent%252fconn_query", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("connector token 不能用双层编码点隐藏回调路径遍历")
    void connector_token双层编码点403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent/%252e%252e/admin/connectors/1", connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("普通 token 不能用第 9 层 percent 编码斜杠隐藏原始回调意图")
    void 普通token九层percent编码斜杠403() throws Exception {
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent" + wrapPercentEncoding("%2f", 9) + "conn_query",
                ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("connector token 不能用 64 层 percent 编码点隐藏回调路径遍历")
    void connector_token六十四层percent编码点403() throws Exception {
        String encodedDot = wrapPercentEncoding("%2e", 64);
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent/" + encodedDot + encodedDot + "/admin/connectors/1",
                connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("★ 四千层 percent 包装的回调斜杠仍线性识别并拒绝（不得指数回溯）")
    void 四千层percent编码斜杠线性拒绝() throws Exception {
        String encodedSlash = "%" + "25".repeat(4_000) + "2f";
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent" + encodedSlash + "conn_query", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        // 正则一旦写成会回溯的形状，这里不是"慢一点"，是挂死；用抢占式超时把它钉成一条会红的断言。
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> filter.doFilter(request, response, markingChain(reached)));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("层数翻倍，耗时不得爆炸——线性而非指数")
    void percent包装层数翻倍耗时仍线性() throws Exception {
        long small = elapsedNanosForWrappedSlash(2_000);
        long large = elapsedNanosForWrappedSlash(8_000);
        // 4 倍输入长度，指数实现早就跑不完了；这里只要求不出现数量级的爆炸。
        assertTrue(large < Math.max(small, 1_000_000L) * 200,
                "4 倍长度耗时放大超过 200 倍，疑似回溯：small=" + small + "ns large=" + large + "ns");
    }

    @Test
    @DisplayName("普通 token 的正常非回调 URL 不因 64 层编码被误伤")
    void 正常非回调六十四层编码放行() throws Exception {
        MockHttpServletRequest request = request(
                "/data/public/" + wrapPercentEncoding("%2f", 64) + "asset", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertTrue(reached.get());
        assertEquals(200, response.getStatus());
        verifyNoInteractions(runRegistry);
    }

    @ParameterizedTest(name = "相邻路由名 {0}")
    @ValueSource(strings = {
            "/data/internal/connector-agent-report/%2fstatus",
            "/data/internal/connector-agent-report/status",
            "/data/internal/connector-agentx/status"
    })
    @DisplayName("★ 相邻路由名不得被误判成回调前缀")
    void 相邻路由名不被误判为回调前缀(String path) throws Exception {
        MockHttpServletRequest request = request(path, ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertTrue(reached.get(), path + " 被误判成回调前缀");
        assertEquals(200, response.getStatus());
        verifyNoInteractions(runRegistry);
    }

    // ---------------------------------------------------------------- 签名 / 有效期 / claims

    @Test
    @DisplayName("connector token 签名错误返回 403")
    void 签名错误403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, OTHER_SECRET, 60_000, TENANT_ID, USER_ID, AGENT_ID, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("签名错误仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("★ alg=none 的无签名 token 即使 claims 全匹配也返回 403")
    void 无签名_none算法403() throws Exception {
        // :8020 可被本机直连绕过网关：只校验 claims 是不够的，alg 必须钉死成 HS256。
        MockHttpServletRequest request = request(CALLBACK_PATH, unsignedNoneToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("★ alg=none 但第三段填了垃圾（绕开「第三段非空」那一关）仍返回 403")
    void 无签名_none算法带垃圾签名段403() throws Exception {
        // 上一条是被「三段 + 第三段非空」拦住的；这一条补上真正的 alg 混淆形状：
        // 三段齐全、claims 全匹配，只有 alg 是 none——必须由「alg 必须是 HS256 + 实际验签」拦。
        MockHttpServletRequest request = request(CALLBACK_PATH, unsignedNoneToken() + "ZmFrZS1zaWc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("签名段被截掉（两段式 token）返回 403")
    void 缺少签名段403() throws Exception {
        String signed = connectorToken();
        String stripped = signed.substring(0, signed.lastIndexOf('.') + 1);
        MockHttpServletRequest request = request(CALLBACK_PATH, stripped);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertForbidden(request, response);
        assertFalse(reached.get());
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("过期 token 返回 403")
    void 过期403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), -60_000,
                        TENANT_ID, USER_ID, AGENT_ID, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("过期 token 仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("tenant_id claim 与 X-Tenant-Id 头不符返回 403")
    void tenant_id与头不符403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        "tenant-b", USER_ID, AGENT_ID, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("跨租户 token 仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("id claim 与 user-id 头不符返回 403")
    void id与user_id头不符403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        TENANT_ID, "1900000000000000099", AGENT_ID, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("错用户 token 仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("realm 不是 ENTERPRISE 返回 403")
    void realm不符403() throws Exception {
        Map<String, Object> payload = basePayload(TENANT_ID, USER_ID, 60_000);
        payload.put("realm", "OPERATOR");
        payload.put(ConnectorAgentTokens.CLAIM_PURPOSE, ConnectorAgentTokens.PURPOSE);
        payload.put(ConnectorAgentTokens.CLAIM_AGENT_ID, String.valueOf(AGENT_ID));
        payload.put(ConnectorAgentTokens.CLAIM_RUN_ID, RUN_ID);
        MockHttpServletRequest request = request(CALLBACK_PATH,
                JWTUtil.createToken(payload, SECRET.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("跨 realm token 仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    @Test
    @DisplayName("缺 rid / aid 的 connector token 返回 403")
    void 缺少运行claims403() throws Exception {
        Map<String, Object> payload = basePayload(TENANT_ID, USER_ID, 60_000);
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        payload.put(ConnectorAgentTokens.CLAIM_PURPOSE, ConnectorAgentTokens.PURPOSE);
        MockHttpServletRequest request = request(CALLBACK_PATH,
                JWTUtil.createToken(payload, SECRET.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("缺少运行绑定仍进入控制器"));

        assertForbidden(request, response);
        verifyNoInteractions(runRegistry);
    }

    // ---------------------------------------------------------------- 运行登记（吊销开关）

    @Test
    @DisplayName("★ runRegistry 未登记该运行返回 403")
    void 运行未登记403() throws Exception {
        when(runRegistry.isActive(anyString(), anyString(), anyLong())).thenReturn(false);
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("运行已结束仍进入控制器"));

        assertForbidden(request, response);
        verify(runRegistry).isActive(TENANT_ID, RUN_ID, AGENT_ID);
    }

    @Test
    @DisplayName("★ 已登记但 aid 与登记的 agent 不符返回 403")
    void 登记的agent不符403() throws Exception {
        long otherAgentId = AGENT_ID + 1;
        // registry 按三元组判定：只有 (租户, runId, agentId) 全对才算在跑。
        when(runRegistry.isActive(TENANT_ID, RUN_ID, otherAgentId)).thenReturn(false);
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        TENANT_ID, USER_ID, otherAgentId, RUN_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("aid 与登记不符仍进入控制器"));

        assertForbidden(request, response);
        verify(runRegistry).isActive(TENANT_ID, RUN_ID, otherAgentId);
        verify(runRegistry, never()).isActive(eq(TENANT_ID), eq(RUN_ID), eq(AGENT_ID));
    }

    @Test
    @DisplayName("rid 与登记的运行不符返回 403")
    void 运行id不符403() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH,
                token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                        TENANT_ID, USER_ID, AGENT_ID, RUN_ID + "-old"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failingChain("旧运行 token 仍进入控制器"));

        assertForbidden(request, response);
        verify(runRegistry).isActive(TENANT_ID, RUN_ID + "-old", AGENT_ID);
    }

    // ---------------------------------------------------------------- 合法放行

    @Test
    @DisplayName("合法 token 放行，写入 principal 与 connRun MDC")
    void 合法token放行并写入principal() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ConnectorAgentPrincipal> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) -> {
            seen.set(ConnectorAgentTokens.requirePrincipal((HttpServletRequest) req));
            assertEquals(RUN_ID, MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN));
        });

        ConnectorAgentPrincipal principal = seen.get();
        assertEquals(new ConnectorAgentPrincipal(TENANT_ID, Long.valueOf(USER_ID), AGENT_ID, RUN_ID), principal);
        assertEquals(principal, request.getAttribute(ConnectorAgentTokens.PRINCIPAL_ATTRIBUTE));
        assertEquals(200, response.getStatus());
        assertNull(MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN), "过滤器返回后不能把运行号串到下一请求");
        verify(runRegistry).isActive(TENANT_ID, RUN_ID, AGENT_ID);
    }

    @Test
    @DisplayName("★ 链走完后 connRun MDC 恢复成原来的旧值，而不是被 remove")
    void MDC恢复旧值而非remove() throws Exception {
        // 这是请求线程池的线程：无脑 remove 会把外层（异步任务 / 嵌套调用）放进去的值一起抹掉。
        MDC.put(ConnectorAgentTokens.MDC_CONNECTOR_RUN, "outer-run");
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) ->
                inside.set(MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN)));

        assertEquals(RUN_ID, inside.get(), "链内必须是本次运行号");
        assertEquals("outer-run", MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN),
                "链走完必须恢复旧值，不能 remove");
    }

    @Test
    @DisplayName("下游抛异常时 connRun MDC 同样恢复旧值")
    void 下游异常时MDC也恢复旧值() {
        MDC.put(ConnectorAgentTokens.MDC_CONNECTOR_RUN, "outer-run");
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, resp) -> {
                throw new IllegalStateException("下游炸了");
            });
        } catch (Exception expected) {
            // 异常本身不是本条断言关心的。
        }

        assertEquals("outer-run", MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN));
    }

    // ---------------------------------------------------------------- 过滤器顺序

    @Test
    @DisplayName("★ Order 落在 TenantContextFilter(+50) 与 AccountStatusFilter(+60) 之间，且不与语义层(+55) 撞位")
    void Order位于租户与账号状态之间且不撞语义层() {
        int tenantOrder = OrderUtils.getOrder(TenantContextFilter.class, 0);
        int connectorOrder = OrderUtils.getOrder(ConnectorAgentScopeFilter.class, 0);
        int accountOrder = OrderUtils.getOrder(AccountStatusFilter.class, 0);
        int semanticOrder = OrderUtils.getOrder(SemanticAgentScopeFilter.class, 0);

        assertEquals(Ordered.HIGHEST_PRECEDENCE + 50, tenantOrder);
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 60, accountOrder);
        assertTrue(tenantOrder < connectorOrder, "必须在 TenantContext 建好之后才判");
        assertTrue(connectorOrder < accountOrder, "路径越权必须在账号状态查库之前被挡住");
        assertNotEquals(Ordered.HIGHEST_PRECEDENCE + 55, connectorOrder,
                "不得与 SemanticAgentScopeFilter 同位");
        assertNotEquals(semanticOrder, connectorOrder);
        assertInstanceOf(Order.class, ConnectorAgentScopeFilter.class.getAnnotation(Order.class));
    }

    // ---------------------------------------------------------------- 请求体上限

    @Test
    @DisplayName("Content-Length 超过 256KB 返回 HTTP 200 / 4000")
    void Content_Length超过256KB返回4000() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        request.setContent(new byte[ConnectorAgentTokens.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertEquals(200, response.getStatus());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"respCode\":\"4000\""),
                response.getContentAsString());
        assertTrue(response.getContentAsString().contains("请求体超过 256KB"));
        assertFalse(reached.get());
        assertNull(MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN));
    }

    @Test
    @DisplayName("恰好 256KB 的请求体放行")
    void 恰好256KB放行() throws Exception {
        MockHttpServletRequest request = request(CALLBACK_PATH, connectorToken());
        request.setContent(new byte[ConnectorAgentTokens.MAX_BODY_BYTES]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, markingChain(reached));

        assertTrue(reached.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("未知长度的请求体即使下游只读完首个 JSON 值，超过 256KB 也返回 5005")
    void 分块请求体下游提前停读仍能拦截() throws Exception {
        ChunkedMockHttpServletRequest request = new ChunkedMockHttpServletRequest("POST", CALLBACK_PATH);
        addHeaders(request, connectorToken());
        byte[] content = new byte[ConnectorAgentTokens.MAX_BODY_BYTES + 1];
        content[0] = '{';
        content[1] = '}';
        Arrays.fill(content, 2, content.length, (byte) ' ');
        request.setContent(content);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, resp) -> {
            reached.set(true);
            assertEquals('{', req.getInputStream().read());
            assertEquals('}', req.getInputStream().read());
            // 模拟 Jackson 读完 {} 就停，不 drain 后续空白。
        });

        assertFalse(reached.get(), "大小校验必须在 Jackson 提前停读之前完成");
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"respCode\":\"5005\""),
                response.getContentAsString());
        assertNull(MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN));
    }

    @Test
    @DisplayName("★ 未超限的分块请求体被完整缓存并重放给下游（不能只读一半）")
    void 分块请求体未超限可完整重放() throws Exception {
        ChunkedMockHttpServletRequest request = new ChunkedMockHttpServletRequest("POST", CALLBACK_PATH);
        addHeaders(request, connectorToken());
        // 刻意跨多个 8KB 读缓冲：只读一半的实现会在这里露馅。
        String payload = "{\"connector\":\"mysql-a\",\"statement\":\"" + "x".repeat(100_000) + "\"}";
        request.setContent(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenBody = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) -> seenBody.set(
                new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertEquals(payload, seenBody.get());
        assertEquals(payload.getBytes(StandardCharsets.UTF_8).length, seenBody.get().length());
        assertEquals(200, response.getStatus());
        assertNull(MDC.get(ConnectorAgentTokens.MDC_CONNECTOR_RUN));
    }

    @Test
    @DisplayName("分块请求体的 getReader() 也能读到完整内容")
    void 分块请求体可经Reader重放() throws Exception {
        ChunkedMockHttpServletRequest request = new ChunkedMockHttpServletRequest("POST", CALLBACK_PATH);
        addHeaders(request, connectorToken());
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String payload = "{\"connector\":\"中文连接器\"}";
        request.setContent(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenBody = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) ->
                seenBody.set(((HttpServletRequest) req).getReader().readLine()));

        assertEquals(payload, seenBody.get());
    }

    // ---------------------------------------------------------------- 辅助

    private long elapsedNanosForWrappedSlash(int layers) throws Exception {
        String encodedSlash = "%" + "25".repeat(layers) + "2f";
        MockHttpServletRequest request = request(
                "/data/internal/connector-agent" + encodedSlash + "conn_query", ordinaryToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        long start = System.nanoTime();
        filter.doFilter(request, response, markingChain(new AtomicBoolean()));
        long elapsed = System.nanoTime() - start;
        assertEquals(403, response.getStatus());
        return elapsed;
    }

    private static FilterChain markingChain(AtomicBoolean reached) {
        return (request, response) -> reached.set(true);
    }

    private static FilterChain failingChain(String message) {
        return (request, response) -> {
            throw new AssertionError(message);
        };
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
        request.addHeader(ConnectorAgentTokens.HEADER_AUTHORIZATION, token);
        request.addHeader(ConnectorAgentTokens.HEADER_TENANT_ID, TENANT_ID);
        request.addHeader(ConnectorAgentTokens.HEADER_USER_ID, USER_ID);
        request.setContentType("application/json");
    }

    private static String connectorToken() {
        return token(ConnectorAgentTokens.PURPOSE, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, AGENT_ID, RUN_ID);
    }

    private static String ordinaryToken() {
        return token(null, SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, AGENT_ID, RUN_ID);
    }

    private static String semanticPurposeToken() {
        return token("semantic-agent", SECRET.getBytes(StandardCharsets.UTF_8), 60_000,
                TENANT_ID, USER_ID, AGENT_ID, RUN_ID);
    }

    private static Map<String, Object> basePayload(String tenantId, String userId, long ttlMs) {
        Date now = new Date();
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put(JWTPayload.EXPIRES_AT, new Date(now.getTime() + ttlMs));
        payload.put(ConnectorAgentTokens.CLAIM_USER_ID, userId);
        payload.put(ConnectorAgentTokens.CLAIM_TENANT_ID, tenantId);
        return payload;
    }

    /** purpose 传 null 即普通登录 token（12 小时、全权，不带任何运行绑定 claim）。 */
    private static String token(String purpose, byte[] key, long ttlMs, String tenantId, String userId,
                                long agentId, String runId) {
        Map<String, Object> payload = basePayload(tenantId, userId, ttlMs);
        payload.put(ConnectorAgentTokens.CLAIM_REALM, PlatformConstant.REALM_ENTERPRISE);
        if (purpose != null) {
            payload.put(ConnectorAgentTokens.CLAIM_PURPOSE, purpose);
            payload.put(ConnectorAgentTokens.CLAIM_AGENT_ID, String.valueOf(agentId));
            payload.put(ConnectorAgentTokens.CLAIM_RUN_ID, runId);
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
                + ",\"purpose\":\"" + ConnectorAgentTokens.PURPOSE + "\""
                + ",\"aid\":\"" + AGENT_ID + "\""
                + ",\"rid\":\"" + RUN_ID + "\"}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    /** 在已有的 {@code %2f}/{@code %2e} 外再包指定层数的 {@code %25}。 */
    private static String wrapPercentEncoding(String encodedSequence, int layers) {
        String value = encodedSequence;
        for (int i = 0; i < layers; i++) {
            value = value.replace("%", "%25");
        }
        return value;
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

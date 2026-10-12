package com.jimeng.dataserver.ai.http.callback;

import cn.hutool.jwt.JWTUtil;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpGatewayScopeFilterTest {

    private static final String SECRET = "http-gateway-filter-secret-0123456789abcdef";
    private static final String TENANT = "t1";
    private static final String USER = "1900000000000000001";
    private static final long AGENT = 2099066794181541890L;
    private static final String RUN = "2099066794181541899";
    private static final String TOOL = "/data/internal/http-gateway/tool";

    private final List<SkillGrant> grants = List.of(new SkillGrant(7L, 1, "crm", SkillManifest.EMPTY));
    private HttpGatewayRunRegistry registry;
    private HttpGatewayScopeFilter filter;
    private AdminAuthService auth;

    @BeforeEach
    void setUp() {
        JwtSecretProvider secretProvider = new JwtSecretProvider();
        ReflectionTestUtils.setField(secretProvider, "secret", SECRET);
        registry = mock(HttpGatewayRunRegistry.class);
        when(registry.activeGrants(eq(TENANT), eq(RUN), eq(AGENT))).thenReturn(grants);
        filter = new HttpGatewayScopeFilter(registry, secretProvider);
        auth = new AdminAuthService(mock(SysUserMapper.class), mock(SysEnterpriseMapper.class), new BCryptPasswordEncoder(), secretProvider);
    }

    private String gatewayToken() {
        return auth.mintHttpGatewayToken(Long.valueOf(USER), TENANT, AGENT, RUN, 60_000);
    }

    private static String token(String purpose, long ttlMs) {
        Map<String, Object> p = new HashMap<>();
        Date now = new Date();
        p.put("iat", now);
        p.put("nbf", now);
        p.put("exp", new Date(now.getTime() + ttlMs));
        p.put("id", USER);
        p.put("tenant_id", TENANT);
        p.put("realm", "ENTERPRISE");
        if (purpose != null) p.put("purpose", purpose);
        p.put("aid", String.valueOf(AGENT));
        p.put("rid", RUN);
        return JWTUtil.createToken(p, SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        r.addHeader("Authorization", token);
        r.addHeader("X-Tenant-Id", TENANT);
        r.addHeader("user-id", USER);
        return r;
    }

    private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    private static void assertForbidden(MockHttpServletResponse resp, MockFilterChain chain, String token) throws Exception {
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("\"respCode\":\"4030\"").doesNotContain(token);
        assertThat(chain.getRequest()).as("被拒的请求不能进到控制器").isNull();
    }

    @Test
    @DisplayName("AdminAuthService 签的凭据，过滤器认：主体、可用 skill 快照写进请求属性；MDC 结束后还原")
    void validCredentialPasses() throws Exception {
        MDC.put(HttpGatewayTokens.MDC_HTTP_RUN, "outer");
        MockFilterChain chain = new MockFilterChain();
        run(request(TOOL, gatewayToken()), chain);
        ServletRequest passed = chain.getRequest();
        assertThat(passed).isNotNull();
        HttpGatewayPrincipal p = (HttpGatewayPrincipal) passed.getAttribute(HttpGatewayTokens.PRINCIPAL_ATTRIBUTE);
        assertThat(p).isEqualTo(new HttpGatewayPrincipal(TENANT, Long.valueOf(USER), AGENT, RUN));
        assertThat(passed.getAttribute(HttpGatewayTokens.GRANTS_ATTRIBUTE)).isEqualTo(grants);
        assertThat(MDC.get(HttpGatewayTokens.MDC_HTTP_RUN)).isEqualTo("outer");
        MDC.remove(HttpGatewayTokens.MDC_HTTP_RUN);
    }

    @Test
    @DisplayName("反向限制：http-gateway 凭据访问普通接口 → 403（窄凭据不能变成全权凭据）")
    void narrowTokenCannotReachOtherApis() throws Exception {
        String t = gatewayToken();
        MockFilterChain chain = new MockFilterChain();
        assertForbidden(run(request("/data/admin/users", t), chain), chain, t);
    }

    @Test
    @DisplayName("普通登录凭据、连接器回调凭据都进不了网关回调")
    void otherCredentialsRejected() throws Exception {
        for (String t : List.of(token(null, 60_000), token("connector-agent", 60_000), token("semantic-agent", 60_000))) {
            MockFilterChain chain = new MockFilterChain();
            assertForbidden(run(request(TOOL, t), chain), chain, t);
        }
    }

    @Test
    @DisplayName("签名不对、alg=none、过期 → 403")
    void badSignatureOrExpired() throws Exception {
        String wrongKey = JWTUtil.createToken(Map.of("purpose", "http-gateway", "id", USER, "tenant_id", TENANT,
                "realm", "ENTERPRISE", "aid", String.valueOf(AGENT), "rid", RUN,
                "exp", new Date(System.currentTimeMillis() + 60_000)), "another-secret-0123456789abcdef0123".getBytes());
        String[] parts = gatewayToken().split("\\.");
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes())
                + "." + parts[1] + ".";
        for (String t : List.of(wrongKey, none, token("http-gateway", -1_000))) {
            MockFilterChain chain = new MockFilterChain();
            assertForbidden(run(request(TOOL, t), chain), chain, t);
        }
    }

    @Test
    @DisplayName("token 自称的租户与网关注入的头对不上 → 403")
    void claimsMustMatchInjectedHeaders() throws Exception {
        String t = gatewayToken();
        MockHttpServletRequest r = request(TOOL, t);
        r.removeHeader("X-Tenant-Id");
        r.addHeader("X-Tenant-Id", "t2");
        MockFilterChain chain = new MockFilterChain();
        assertForbidden(run(r, chain), chain, t);
    }

    @Test
    @DisplayName("运行已结束（登记被撤销）→ 403")
    void runEnded() throws Exception {
        when(registry.activeGrants(anyString(), anyString(), anyLong())).thenReturn(null);
        String t = gatewayToken();
        MockFilterChain chain = new MockFilterChain();
        assertForbidden(run(request(TOOL, t), chain), chain, t);
    }

    @Test
    @DisplayName("回调路径里有 .. 或编码的分隔符 → 403")
    void suspiciousPaths() throws Exception {
        String t = gatewayToken();
        for (String path : List.of("/data/internal/http-gateway/../admin/users", "/data/internal/http-gateway/%2e%2e/admin")) {
            MockFilterChain chain = new MockFilterChain();
            assertForbidden(run(request(path, t), chain), chain, t);
        }
    }

    @Test
    @DisplayName("与本过滤器无关的请求原样放行")
    void unrelatedRequestPasses() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run(request("/data/admin/users", token(null, 60_000)), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("purpose 常量与签发方一致")
    void purposeParity() {
        assertThat(HttpGatewayTokens.PURPOSE).isEqualTo(AdminAuthService.PURPOSE_HTTP_GATEWAY);
    }
}

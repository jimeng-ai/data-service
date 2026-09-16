package com.jimeng.dataserver.admin.auth.service;

import cn.hutool.core.convert.NumberWithFormat;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAuthService#mintSemanticAgentToken}：语义层 agent 一片运行的窄权限回调 token（设计文档 7.3）。
 *
 * <p>这枚 token 要同时过两道关，两道都是「错了不报错、只是进不来」：
 * <ul>
 *   <li><b>网关</b>照常验签，缺 {@code id} 或 {@code tenant_id} 直接 401，并据 {@code tenant_id} 注入租户头——
 *       所以它必须与登录 token 同一把密钥、带齐这两个 claim；</li>
 *   <li><b>data-server 回调前缀上的过滤器</b>按 {@code purpose}、{@code gen}、{@code cid}、{@code slice}、{@code rid}
 *       与库里进行中的批次逐项核对——任何一项名字或类型不对，每个回调都是 403，编排器看到的是「回调没到达」。</li>
 * </ul>
 * 两个 id 用字符串是硬要求：雪花 id 超出 JS 安全整数，边车的 MCP 工具与任何 JS 端解析 JWT 时都会静默改值，
 * 所以这里看的是 JWT payload 的<b>原始 JSON</b>，不是 hutool 取 claim 时转换过的对象。
 */
class AdminAuthServiceSemanticTokenTest {

    private static final String SECRET = "semantic-token-test-secret-0123456789abcdef";   // ≥ 32 位
    private static final long TRIGGERED_BY = 1900000000000000001L;
    private static final String TENANT = "t_review";
    private static final long GENERATION_ID = 2099066794181541890L;
    private static final long CONNECTOR_ID = 2099066794181541891L;
    private static final String RUN_ID = "semgen-2099066794181541890-s3-a2";

    private SysUserMapper sysUserMapper;
    private SysEnterpriseMapper sysEnterpriseMapper;
    private JwtSecretProvider jwtSecretProvider;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        sysEnterpriseMapper = mock(SysEnterpriseMapper.class);
        jwtSecretProvider = new JwtSecretProvider();
        ReflectionTestUtils.setField(jwtSecretProvider, "secret", SECRET);
        service = new AdminAuthService(sysUserMapper, sysEnterpriseMapper, new BCryptPasswordEncoder(), jwtSecretProvider);
    }

    private String mint(int sliceWallClockSec) {
        return service.mintSemanticAgentToken(TRIGGERED_BY, TENANT, GENERATION_ID, CONNECTOR_ID, 3, RUN_ID,
                AdminAuthService.semanticAgentTokenTtlMs(sliceWallClockSec));
    }

    /** JWT 第二段按 base64url 解出来的原始 JSON。 */
    private static JsonNode rawPayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "不是三段式 JWT：" + token);
        byte[] json = Base64.getUrlDecoder().decode(parts[1]);
        return new ObjectMapper().readTree(new String(json, StandardCharsets.UTF_8));
    }

    private static long seconds(JWTPayload payload, String claim) {
        return ((NumberWithFormat) payload.getClaim(claim)).longValue();
    }

    @Test
    @DisplayName("★ claim 完整：网关要的 id / tenant_id / realm，过滤器要的 purpose / gen / cid / slice / rid，外加 iat / nbf / exp，不多不少")
    void claim完整() throws Exception {
        String token = mint(900);
        JWTPayload payload = JWTUtil.parseToken(token).getPayload();

        assertEquals(String.valueOf(TRIGGERED_BY), payload.getClaim("id"));
        assertEquals(TENANT, payload.getClaim("tenant_id"));
        assertEquals(PlatformConstant.REALM_ENTERPRISE, payload.getClaim("realm"));
        assertEquals("semantic-agent", payload.getClaim("purpose"));
        assertEquals(AdminAuthService.PURPOSE_SEMANTIC_AGENT, payload.getClaim("purpose"));
        assertEquals(String.valueOf(GENERATION_ID), payload.getClaim("gen"));
        assertEquals(String.valueOf(CONNECTOR_ID), payload.getClaim("cid"));
        assertEquals(3, ((Number) payload.getClaim("slice")).intValue());
        assertEquals(RUN_ID, payload.getClaim("rid"));

        // 不多不少：不带登录 token 的 username / user_type（这枚 token 不代表一次人的登录），也没有漏掉哪一项
        Set<String> expected = Set.of(JWTPayload.ISSUED_AT, JWTPayload.NOT_BEFORE, JWTPayload.EXPIRES_AT,
                "id", "tenant_id", "realm", "purpose", "gen", "cid", "slice", "rid");
        Set<String> actual = new java.util.HashSet<>();
        rawPayload(token).fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);

        // 网关 AuthorizeFilter 的两条必填判定：String.valueOf(claim) 非空且不是 "null"
        assertFalse("null".equals(String.valueOf(payload.getClaim("id"))));
        assertFalse("null".equals(String.valueOf(payload.getClaim("tenant_id"))));
    }

    @Test
    @DisplayName("★ gen、cid（以及 id）在 JWT 原始 JSON 里是字符串且逐位不变；slice 是数字")
    void gen与cid为字符串() throws Exception {
        JsonNode raw = rawPayload(mint(900));

        assertTrue(raw.get("gen").isTextual(), "gen 不是 JSON 字符串：" + raw);
        assertTrue(raw.get("cid").isTextual(), "cid 不是 JSON 字符串：" + raw);
        assertTrue(raw.get("id").isTextual(), "id 不是 JSON 字符串（与登录签发、mintInternalToken 不一致）：" + raw);
        // 逐位不变：按数字写进去再被 JS 读出来会变成 2099066794181541900
        assertEquals("2099066794181541890", raw.get("gen").asText());
        assertEquals("2099066794181541891", raw.get("cid").asText());
        assertEquals("1900000000000000001", raw.get("id").asText());

        assertTrue(raw.get("slice").isIntegralNumber(), "slice 应是 JSON 数字：" + raw);
        assertTrue(raw.get("rid").isTextual());
    }

    @Test
    @DisplayName("★ exp = iat + 本片墙钟 + 120 秒；nbf = iat")
    void exp为墙钟加120秒() {
        assertEquals((900 + 120) * 1000L, AdminAuthService.semanticAgentTokenTtlMs(900));
        assertEquals((1200 + 120) * 1000L, AdminAuthService.semanticAgentTokenTtlMs(1200));
        assertEquals(120, AdminAuthService.SEMANTIC_AGENT_TOKEN_GRACE_SEC);

        for (int wallClock : new int[]{120, 900, 1200}) {
            long before = System.currentTimeMillis() / 1000L;
            JWTPayload payload = JWTUtil.parseToken(mint(wallClock)).getPayload();
            long after = System.currentTimeMillis() / 1000L;

            long iat = seconds(payload, JWTPayload.ISSUED_AT);
            long nbf = seconds(payload, JWTPayload.NOT_BEFORE);
            long exp = seconds(payload, JWTPayload.EXPIRES_AT);
            assertEquals(wallClock + 120L, exp - iat, "墙钟 " + wallClock + " 秒时 token 有效期不对");
            assertEquals(iat, nbf);
            assertTrue(iat >= before && iat <= after, "iat 不是签发时刻：" + iat);
        }
    }

    @Test
    @DisplayName("★ 与登录 token 同一把密钥：两枚都能用 jwt.secret 验签、都过网关的过期与必填判定；换一把密钥两枚都验不过")
    void 与登录token同一密钥可验签() {
        SysUser user = new SysUser();
        user.setId(TRIGGERED_BY);
        user.setTenantId(TENANT);
        user.setUsername("admin");
        user.setUserType("SUPER_ADMIN");
        user.setStatus(1);
        when(sysUserMapper.selectById(TRIGGERED_BY)).thenReturn(user);
        SysEnterprise ent = new SysEnterprise();
        ent.setTenantId(TENANT);
        ent.setStatus(1);
        when(sysEnterpriseMapper.selectOne(any())).thenReturn(ent);

        LoginResponse login = service.refresh(TRIGGERED_BY);   // 走登录同一段签发（signToken）
        String loginToken = login.getToken();
        String semanticToken = mint(900);

        byte[] key = jwtSecretProvider.key();
        assertTrue(JWTUtil.verify(loginToken, key), "夹具不对：登录 token 用 jwt.secret 验不过");
        assertTrue(JWTUtil.verify(semanticToken, key), "语义层 token 与登录 token 不是同一把密钥，网关会 401");

        byte[] otherKey = "another-secret-0123456789abcdefghijklmn".getBytes(StandardCharsets.UTF_8);
        assertFalse(JWTUtil.verify(loginToken, otherKey));
        assertFalse(JWTUtil.verify(semanticToken, otherKey), "换了密钥还能验过，说明根本没签名");

        // 照网关 AuthorizeFilter 的判定再走一遍：exp（秒）晚于现在，id 与 tenant_id 非空，且与登录 token 指向同一个人、同一个租户
        for (String token : new String[]{loginToken, semanticToken}) {
            JWT jwt = JWTUtil.parseToken(token);
            JWTPayload p = jwt.getPayload();
            assertTrue(seconds(p, JWTPayload.EXPIRES_AT) * 1000L > System.currentTimeMillis(), "已过期：" + token);
            assertEquals(String.valueOf(TRIGGERED_BY), String.valueOf(p.getClaim("id")));
            assertEquals(TENANT, String.valueOf(p.getClaim("tenant_id")));
        }
    }
}

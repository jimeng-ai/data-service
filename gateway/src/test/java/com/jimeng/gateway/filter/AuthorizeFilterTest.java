package com.jimeng.gateway.filter;

import cn.hutool.jwt.JWTUtil;
import com.jimeng.gateway.config.AuthConfiguration;
import com.jimeng.gateway.security.JwtSecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 下游（data-server）按请求头认人：user-id 认用户，X-Tenant-Id 认租户。这两个头只能由网关从令牌里解出来注入，
 * 客户端自己带的必须在网关丢掉——免登录路径也一样，否则白名单一放宽，谁都能带个 user-id 冒充任意用户。
 */
class AuthorizeFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private AuthorizeFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        AuthConfiguration auth = new AuthConfiguration();
        auth.getAuth().setWhitesUrl(List.of("/**/auth/login"));
        JwtSecretProvider jwt = new JwtSecretProvider();
        setField(jwt, "secret", SECRET);
        setField(jwt, "additionalSecrets", "");
        // init() 是包内可见的（由 Spring 的 @PostConstruct 调用），测试在另一个包里，只能反射调用
        Method init = JwtSecretProvider.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(jwt);
        filter = new AuthorizeFilter(auth, jwt);
    }

    @Test
    @DisplayName("免登录路径：客户端带的 user-id 和 X-Tenant-Id 都不往下游传")
    void whitelistedPathDropsClientIdentityHeaders() {
        ServerHttpRequest forwarded = forward(MockServerHttpRequest.post("/data/admin/auth/login")
                .header("user-id", "42")
                .header("X-Tenant-Id", "t-other"));

        assertNull(forwarded.getHeaders().getFirst("user-id"));
        assertNull(forwarded.getHeaders().getFirst("X-Tenant-Id"));
        assertNotNull(forwarded.getHeaders().getFirst("x-trace-id"));
    }

    @Test
    @DisplayName("免登录路径：头名换大小写也一样丢掉")
    void whitelistedPathDropsIdentityHeadersRegardlessOfCase() {
        ServerHttpRequest forwarded = forward(MockServerHttpRequest.post("/data/admin/auth/login")
                .header("User-Id", "42")
                .header("x-tenant-id", "t-other"));

        assertNull(forwarded.getHeaders().getFirst("user-id"));
        assertNull(forwarded.getHeaders().getFirst("X-Tenant-Id"));
    }

    @Test
    @DisplayName("需要登录的路径：身份只来自令牌，客户端带的同名头被覆盖")
    void authenticatedPathTakesIdentityFromTokenOnly() {
        String token = JWTUtil.createToken(Map.of("id", "7", "tenant_id", "t1"), SECRET.getBytes(StandardCharsets.UTF_8));
        ServerHttpRequest forwarded = forward(MockServerHttpRequest.get("/data/admin/me/permissions")
                .header("Authorization", token)
                .header("user-id", "42")
                .header("X-Tenant-Id", "t-other"));

        assertEquals(List.of("7"), forwarded.getHeaders().get("user-id"));
        assertEquals(List.of("t1"), forwarded.getHeaders().get("X-Tenant-Id"));
    }

    /** 跑一遍过滤器，返回它交给下游的请求。 */
    private ServerHttpRequest forward(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> seen = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            seen.set(ex.getRequest());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertNotNull(seen.get(), "请求应当被放行到下游");
        return seen.get();
    }

    private static void setField(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

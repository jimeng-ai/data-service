package com.jimeng.gateway.filter;

import cn.hutool.core.convert.NumberWithFormat;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.jimeng.gateway.config.AuthConfiguration;
import com.jimeng.gateway.constants.JWTConstant;
import com.jimeng.gateway.entity.GatewayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * @Author spider
 * @Date 2023/11/4 19:58
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorizeFilter implements GlobalFilter, Ordered {

    // 获取 nacos 配置
    private final AuthConfiguration authConfiguration;

    @Override
    public int getOrder() {
        // 优先级设置为99，为了使请求路径处理的过滤器优先执行
        return 99;
    }

    /** 租户头。所有非白名单请求都由网关从 JWT 解出后强制注入；客户端传入的同名头会被丢弃。 */
    private static final String HEADER_TENANT_ID = "X-Tenant-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        // 获取请求路径
        String urlPath = request.getURI().getRawPath();
        // 判断是否在白名单
        if (!whetherThePathIsNotVerified(urlPath)) {
            // 白名单路径也注入 trace-id，确保下游服务日志可追踪；
            // 同时剥离客户端可能带的 X-Tenant-Id，避免绕过租户校验。
            ServerWebExchange whiteListExchange = exchange.mutate()
                    .request(builder -> builder
                            .headers(h -> h.remove(HEADER_TENANT_ID))
                            .header("x-trace-id", UUID.randomUUID().toString()))
                    .build();
            return chain.filter(whiteListExchange);
        }
        // 获取token
        HttpHeaders headers = request.getHeaders();
        String token = headers.getFirst("Authorization");

        // 记录请求但不记录敏感的token内容
        log.info("Processing authentication for path: {}", urlPath);

        if (StrUtil.isBlank(token)) {
            log.warn("Missing Authorization header for path: {}", urlPath);
            return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }

        try {
            // 验证token签名
            boolean verify = JWTUtil.verify(token, JWTConstant.TOKEN_SECRET.getBytes());
            if (!verify) {
                log.warn("Invalid JWT signature for path: {}", urlPath);
                return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Invalid token signature");
            }

            // 解析token
            JWT jwt = JWTUtil.parseToken(token);
            JWTPayload payload = jwt.getPayload();

            // 验证token是否过期
            Object expObj = payload.getClaim("exp");
            if (expObj != null) {
                Date expiration = new Date(((NumberWithFormat) expObj).longValue() * 1000L); // JWT exp是秒级时间戳
                Date now = new Date();
                if (now.after(expiration)) {
                    log.warn("JWT token expired for path: {}", urlPath);
                    return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Token expired");
                }
            }

            // 验证必要的payload字段
            String userId = String.valueOf(payload.getClaim("id"));
            if (StrUtil.isBlank(userId) || "null".equals(userId)) {
                log.warn("Invalid user ID in token for path: {}", urlPath);
                return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Invalid token payload");
            }

            // 租户 ID 由 JWT 决定。客户端任何 X-Tenant-Id 都会被覆盖，
            // 这是租户隔离的关键不变量——否则 admin 改个 header 就能越权访问别家数据。
            Object tenantClaim = payload.getClaim("tenant_id");
            String tenantId = tenantClaim == null ? null : String.valueOf(tenantClaim);
            if (StrUtil.isBlank(tenantId) || "null".equals(tenantId)) {
                log.warn("Missing tenant_id claim in token for path: {}", urlPath);
                return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Invalid token payload");
            }

            // 将userId/tenantId放入请求头，用于下游服务使用
            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(builder -> {
                        builder.headers(h -> h.remove(HEADER_TENANT_ID));
                        builder.header("user-id", userId);
                        builder.header(HEADER_TENANT_ID, tenantId);
                        builder.header("x-trace-id", UUID.randomUUID().toString());
                    })
                    .build();

            log.debug("Authentication successful for user: {} tenant: {} on path: {}", userId, tenantId, urlPath);
            return chain.filter(modifiedExchange);

        } catch (Exception e) {
            log.error("JWT token processing error for path: {}, error: {}", urlPath, e.getMessage());
            return buildErrorResponse(response, HttpStatus.UNAUTHORIZED, "Token processing error");
        }
    }

    // 校验是否需要进行身份认证(true-需要，false-不需要)
    private boolean whetherThePathIsNotVerified(String urlPath){
        // 获取网关白名单
        List<String> whitesUrl = authConfiguration.getAuth().getWhitesUrl();
        log.info("网关白名单：{}", whitesUrl);
        PathMatcher pathMatcher = new AntPathMatcher();
        for (String whiteUrl : whitesUrl) {
            if (pathMatcher.match(whiteUrl, urlPath)){
                // 不需要认证
                return false;
            }
        }
        return true;
    }

    /**
     * 构建统一的错误响应
     */
    private Mono<Void> buildErrorResponse(ServerHttpResponse response, HttpStatus status, String message) {
        String jsonStr = JSONUtil.toJsonStr(GatewayResponse.Resp.newBuilder()
                .setRespCode(String.valueOf(status.value()))
                .setRespMsg(message)
                .build());
        DataBuffer buffer = response.bufferFactory().wrap(jsonStr.getBytes(StandardCharsets.UTF_8));
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        return response.writeWith(Mono.just(buffer));
    }

}

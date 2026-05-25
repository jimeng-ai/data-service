package com.jimeng.common.core.tenant;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 租户上下文 Filter：
 * <ul>
 *   <li>从请求头读取 {@code X-Tenant-Id}（由 gateway 从 JWT 提取后注入）</li>
 *   <li>缺失或非法 → 直接返回 403</li>
 *   <li>合法 → 设置 {@link TenantContext}，finally 清理（防 ThreadLocal 泄漏）</li>
 *   <li>白名单路径放行（actuator、knife4j、健康检查等）</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class TenantContextFilter implements Filter {

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** 默认放行路径：基础设施类端点不走业务，无租户概念。 */
    private static final List<String> DEFAULT_WHITELIST = Arrays.asList(
            "/actuator/**",
            "/doc.html",
            "/swagger-resources/**",
            "/webjars/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    );

    /** 业务方可以在配置里追加 skip 路径，逗号分隔。 */
    @Value("${tenant.skip-paths:}")
    private String configuredSkipPaths;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        if (isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }

        String tenantHeader = httpRequest.getHeader(HEADER_TENANT_ID);
        if (!StringUtils.hasText(tenantHeader)) {
            log.warn("拒绝请求：缺少租户头 {} [path={}]", HEADER_TENANT_ID, path);
            writeForbidden(httpResponse, "missing " + HEADER_TENANT_ID + " header");
            return;
        }

        // 与 ai_model_call_log.tenant_id (VARCHAR(64)) 保持一致：tenant_id 用字符串表达，
        // 既支持数字 ID 也支持 UUID / slug。这里只做长度/字符基本校验，业务合法性由 gateway 保证。
        String tenantId = tenantHeader.trim();
        if (tenantId.length() > 64 || !isValidTenantToken(tenantId)) {
            log.warn("拒绝请求：租户头非法 {}={} [path={}]", HEADER_TENANT_ID, tenantHeader, path);
            writeForbidden(httpResponse, "invalid " + HEADER_TENANT_ID + " header");
            return;
        }

        try {
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** 仅允许字母数字、连字符、下划线、点号——拦下控制字符/SQL 注入字符。 */
    private boolean isValidTenantToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        return !token.isEmpty();
    }

    private boolean isWhitelisted(String path) {
        for (String pattern : DEFAULT_WHITELIST) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        if (StringUtils.hasText(configuredSkipPaths)) {
            for (String pattern : configuredSkipPaths.split(",")) {
                if (StringUtils.hasText(pattern) && pathMatcher.match(pattern.trim(), path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(
                "{\"code\":\"4030\",\"msg\":\"" + message + "\",\"data\":null}"
        );
    }
}

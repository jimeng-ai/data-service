package com.jimeng.dataserver.admin.common;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 企业账号 / 企业（租户）状态校验：让"禁用成员 / 停用企业"对**已签发的 token**也立即生效。
 *
 * <p>stateless JWT 下网关只校验签名+有效期，不查库状态；本过滤器在每个企业请求上补一次状态校验：
 * <ul>
 *   <li>{@code sys_user.status != 1} → 401（账号已禁用）</li>
 *   <li>{@code sys_enterprise.status != 1} → 401（企业已停用）</li>
 * </ul>
 * 前端拦截器收到 401 会自动登出并跳登录页。
 *
 * <p>跳过：未带 {@code user-id} 的请求（登录/文档等网关白名单）、运营请求（{@code X-Tenant-Id=platform}，
 * 其账号在 sys_operator，不在 sys_user）。
 *
 * <p>性能：每个企业请求两次主键/唯一索引查询。量大时可在此加 Redis 短 TTL 缓存（disable 时驱逐）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
@RequiredArgsConstructor
public class AccountStatusFilter implements Filter {

    private final SysUserMapper sysUserMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String userId = req.getHeader(AdminRequestContext.HEADER_USER_ID);
        String tenantId = req.getHeader(AdminRequestContext.HEADER_TENANT_ID);

        // 非认证请求（无 user-id）或运营请求（platform 租户）直接放行
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(tenantId)
                || PlatformConstant.PLATFORM_TENANT.equals(tenantId)) {
            chain.doFilter(request, response);
            return;
        }

        Long uid;
        try {
            uid = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            chain.doFilter(request, response);
            return;
        }

        // sys_user / sys_enterprise 不在租户白名单，用 runAsSystem 跨租户安全查询
        SysUser user = TenantContext.runAsSystem(() -> sysUserMapper.selectById(uid));
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            writeUnauthorized(resp, "账号已禁用");
            return;
        }
        SysEnterprise ent = TenantContext.runAsSystem(() -> sysEnterpriseMapper.selectOne(
                Wrappers.<SysEnterprise>lambdaQuery().eq(SysEnterprise::getTenantId, user.getTenantId())));
        if (ent == null || ent.getStatus() == null || ent.getStatus() != 1) {
            writeUnauthorized(resp, "企业已停用");
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(
                "{\"success\":false,\"respCode\":\"4001\",\"respMsg\":\"" + message + "\",\"data\":null}");
    }
}

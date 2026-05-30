package com.jimeng.dataserver.admin.rbac.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 企业管理接口守卫：确认当前请求来自本租户的企业超级管理员（{@code user_type=SUPER_ADMIN}）。
 *
 * <p>{@code user-id} / {@code X-Tenant-Id} 来自 gateway 注入。成员（MEMBER）调用 RBAC 管理接口会被拒绝。
 */
@Component
@RequiredArgsConstructor
public class SuperAdminGuard {

    private final SysUserMapper sysUserMapper;

    /** 校验并返回当前超管账号；非超管/跨租户/禁用抛 4001。 */
    public SysUser requireSuperAdmin() {
        Long userId = AdminRequestContext.requireUserId();
        String tenantId = AdminRequestContext.requireTenantId();
        SysUser u = TenantContext.runAsSystem(() -> sysUserMapper.selectById(userId));
        if (u == null || u.getStatus() == null || u.getStatus() != 1
                || !SysUser.TYPE_SUPER_ADMIN.equals(u.getUserType())
                || !tenantId.equals(u.getTenantId())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "需要企业超级管理员权限");
        }
        return u;
    }

    /** 当前租户 ID。 */
    public String currentTenantId() {
        return AdminRequestContext.requireTenantId();
    }
}

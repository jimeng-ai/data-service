package com.jimeng.dataserver.admin.operator.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.persistence.entity.SysOperator;
import com.jimeng.persistence.mapper.SysOperatorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 运营接口守卫：确认当前请求来自一个有效的平台运营账号。
 *
 * <p>{@code user-id} 头来自 gateway 注入的 JWT id claim。运营 id 落在 {@code sys_operator} 表，
 * 企业账号 id 落在 {@code sys_user} 表，两者不会串号；用 sys_operator 表查不到即拒绝，
 * 防止企业账号越权调用 {@code /data/admin/operator/**}。
 */
@Component
@RequiredArgsConstructor
public class OperatorGuard {

    private final SysOperatorMapper sysOperatorMapper;

    /** 校验并返回当前运营 ID；非运营或已禁用抛 4001。 */
    public Long requireOperatorId() {
        Long userId = AdminRequestContext.requireUserId();
        SysOperator op = TenantContext.runAsSystem(() -> sysOperatorMapper.selectById(userId));
        if (op == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无运营权限");
        }
        if (op.getStatus() == null || op.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "运营账号已禁用");
        }
        return op.getId();
    }
}

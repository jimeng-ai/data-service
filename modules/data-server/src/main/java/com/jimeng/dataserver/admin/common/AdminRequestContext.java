package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * 从当前请求读取 gateway 注入的 {@code user-id} / {@code X-Tenant-Id} 头。
 *
 * <p>与 {@code MyMetaObjectHandler} / {@code RequestUtil} 同一套（RequestContextHolder），
 * 让 service 层不必把 userId / tenantId 一路透传过控制器签名。
 */
public final class AdminRequestContext {

    public static final String HEADER_USER_ID = "user-id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    private AdminRequestContext() {
    }

    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "无请求上下文");
        }
        return attrs.getRequest();
    }

    /** 当前登录用户 ID（gateway 从 JWT id claim 注入）。缺失/非法抛 4001。 */
    public static Long requireUserId() {
        String raw = Optional.ofNullable(currentRequest().getHeader(HEADER_USER_ID)).orElse(null);
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "缺少 user-id 头");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "user-id 头格式错误");
        }
    }

    /** 当前租户 ID（gateway 从 JWT tenant_id claim 注入）。缺失抛 4001。 */
    public static String requireTenantId() {
        String raw = Optional.ofNullable(currentRequest().getHeader(HEADER_TENANT_ID)).orElse(null);
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "缺少 X-Tenant-Id 头");
        }
        return raw.trim();
    }
}

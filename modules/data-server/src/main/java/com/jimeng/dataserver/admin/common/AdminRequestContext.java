package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前请求读取 gateway 注入的 {@code user-id} / {@code X-Tenant-Id} 头。
 *
 * <p>与 {@code MyMetaObjectHandler} / {@code RequestUtil} 同一套（RequestContextHolder），
 * 让 service 层不必把 userId / tenantId 一路透传过控制器签名。
 *
 * <p><b>异步流式场景</b>：SSE 回答跑在 executor 线程，请求级 ThreadLocal（RequestContextHolder）
 * 不会自动传递。{@link com.jimeng.dataserver.web.MdcAsyncSupport} 会在请求线程捕获 userId 并通过
 * {@link #setAsyncUserId} 注入到 executor 线程；tenantId 则复用已传递的 {@link TenantContext}。
 * 因此 {@link #requireUserId()} / {@link #requireTenantId()} 在无 servlet 请求时回退到这两个来源。
 */
public final class AdminRequestContext {

    public static final String HEADER_USER_ID = "user-id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** 异步线程上由 MdcAsyncSupport 注入的 userId（请求线程捕获后捎带过来）。 */
    private static final ThreadLocal<Long> ASYNC_USER_ID = new ThreadLocal<>();

    private AdminRequestContext() {
    }

    private static HttpServletRequest currentRequestOrNull() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    private static String headerOrNull(HttpServletRequest req, String name) {
        if (req == null) return null;
        String raw = req.getHeader(name);
        return (raw == null || raw.isBlank() || "null".equals(raw)) ? null : raw.trim();
    }

    /** 供 MdcAsyncSupport 在请求线程捕获 userId 用（无则 null，不抛）。 */
    public static Long findUserIdOrNull() {
        String raw = headerOrNull(currentRequestOrNull(), HEADER_USER_ID);
        if (raw != null) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ASYNC_USER_ID.get();
    }

    public static void setAsyncUserId(Long userId) {
        if (userId != null) ASYNC_USER_ID.set(userId);
    }

    public static void clearAsyncUserId() {
        ASYNC_USER_ID.remove();
    }

    /** 当前登录用户 ID（gateway 从 JWT id claim 注入；异步线程回退到 MdcAsyncSupport 捎带值）。缺失/非法抛 4001。 */
    public static Long requireUserId() {
        HttpServletRequest req = currentRequestOrNull();
        String raw = headerOrNull(req, HEADER_USER_ID);
        if (raw != null) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "user-id 头格式错误");
            }
        }
        Long async = ASYNC_USER_ID.get();
        if (async != null) {
            return async;
        }
        if (req == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "无请求上下文");
        }
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "缺少 user-id 头");
    }

    /** 当前租户 ID（gateway 从 JWT tenant_id claim 注入；异步线程回退到已传递的 TenantContext）。缺失抛 4001。 */
    public static String requireTenantId() {
        String raw = headerOrNull(currentRequestOrNull(), HEADER_TENANT_ID);
        if (raw != null) {
            return raw;
        }
        String fromContext = TenantContext.get();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        if (currentRequestOrNull() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "无请求上下文");
        }
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "缺少 X-Tenant-Id 头");
    }
}

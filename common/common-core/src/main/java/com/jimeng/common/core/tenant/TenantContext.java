package com.jimeng.common.core.tenant;

import java.util.function.Supplier;

/**
 * 当前请求的租户上下文，基于 ThreadLocal 存储。
 *
 * <p>使用方式：
 * <ul>
 *   <li>请求路径上：{@link TenantContextFilter} 在请求进入业务逻辑前调用 {@link #set(Long)}，
 *       并在 finally 中调用 {@link #clear()} 防止线程池泄漏。</li>
 *   <li>系统级查询（如启动期插件缓存加载、跨租户的 admin 操作）：用
 *       {@link #runAsSystem(Supplier)} 包一层，此时
 *       {@link JimengTenantLineHandler#ignoreTable(String)} 会跳过租户过滤。</li>
 * </ul>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM_MODE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static String required() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null || tenantId.isEmpty()) {
            throw new IllegalStateException("TenantContext 未设置：当前线程缺少租户上下文");
        }
        return tenantId;
    }

    public static boolean isSet() {
        String t = CURRENT_TENANT.get();
        return t != null && !t.isEmpty();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * 在系统模式下执行操作（跳过租户过滤），用于跨租户的系统级查询。
     * 非业务请求路径上使用，例如：启动期把所有租户的插件加载进缓存。
     */
    public static <T> T runAsSystem(Supplier<T> supplier) {
        boolean previous = SYSTEM_MODE.get();
        SYSTEM_MODE.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            SYSTEM_MODE.set(previous);
        }
    }

    public static void runAsSystem(Runnable runnable) {
        runAsSystem(() -> {
            runnable.run();
            return null;
        });
    }

    public static boolean isSystemMode() {
        return Boolean.TRUE.equals(SYSTEM_MODE.get());
    }
}

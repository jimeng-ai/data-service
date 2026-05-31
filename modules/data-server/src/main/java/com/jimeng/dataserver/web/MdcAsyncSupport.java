package com.jimeng.dataserver.web;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 把当前线程（通常是 Tomcat 请求线程）的 MDC + TenantContext + AgentContext 捎带到 executor 线程，
 * 并附加 {@code connectionId}（SSE 异步流的关联键）。
 *
 * <p>用法：
 * <pre>{@code
 * streamExecutor.execute(MdcAsyncSupport.wrap(connectionId, () -> someService.doStream(...)));
 * }</pre>
 */
public final class MdcAsyncSupport {

    public static final String MDC_CONNECTION_ID = "connectionId";

    private MdcAsyncSupport() {}

    public static Runnable wrap(String connectionId, Runnable task) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentTenant = TenantContext.get();
        AgentRuntimeView parentAgent = AgentContext.get();
        // 请求级 userId 不随 RequestContextHolder 传递，这里在请求线程显式捕获后捎带到 executor 线程，
        // 让异步流式里的实例级鉴权（PermissionResolver → AdminRequestContext.requireUserId）能正常工作。
        Long parentUserId = AdminRequestContext.findUserIdOrNull();
        return () -> {
            try {
                if (parentMdc != null) MDC.setContextMap(parentMdc);
                if (connectionId != null) MDC.put(MDC_CONNECTION_ID, connectionId);
                if (parentTenant != null) TenantContext.set(parentTenant);
                if (parentAgent != null) AgentContext.set(parentAgent);
                if (parentUserId != null) AdminRequestContext.setAsyncUserId(parentUserId);
                task.run();
            } finally {
                MDC.clear();
                TenantContext.clear();
                AgentContext.clear();
                AdminRequestContext.clearAsyncUserId();
            }
        };
    }
}

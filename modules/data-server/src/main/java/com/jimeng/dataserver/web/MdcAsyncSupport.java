package com.jimeng.dataserver.web;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 把当前线程（通常是 Tomcat 请求线程）的 MDC 上下文捎带到 executor 线程，
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
        return () -> {
            try {
                if (parentMdc != null) MDC.setContextMap(parentMdc);
                if (connectionId != null) MDC.put(MDC_CONNECTION_ID, connectionId);
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }
}

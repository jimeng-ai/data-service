package com.jimeng.dataserver.web;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 全局 MDC 上下文 + access log。
 *
 * <p>对每个 HTTP 请求：
 * <ul>
 *   <li>抽取/兜底 {@code traceId}（来自 header {@code trace-id} 或 {@code x-trace-id}，没有就 UUID）；</li>
 *   <li>抽取 {@code userId}（gateway 鉴权通过后注入的 {@code user-id} header）；</li>
 *   <li>抽取请求路径 {@code path}；</li>
 *   <li>请求结束打一行 access 日志（method / path / status / elapsedMs / userId）；</li>
 *   <li>finally 中 {@code MDC.clear()} 防止 Tomcat 线程复用串号。</li>
 * </ul>
 *
 * <p><b>异步限制</b>：SSE / @Async 等场景下 chain.doFilter 会很快返回（response body 还在另一个线程流式写出），
 * 所以这里记录的 elapsed 不代表真实业务耗时；真实流式耗时由 AiConversationLoop 的 sendSummary 单独打。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcContextFilter implements Filter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_PATH = "path";

    private static final String HEADER_TRACE_ID = "trace-id";
    private static final String HEADER_TRACE_ID_ALT = "x-trace-id";
    private static final String HEADER_USER_ID = "user-id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq) || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        String traceId = pickTraceId(httpReq);
        String userId = httpReq.getHeader(HEADER_USER_ID);
        String path = httpReq.getRequestURI();

        MDC.put(MDC_TRACE_ID, traceId);
        if (StrUtil.isNotBlank(userId)) MDC.put(MDC_USER_ID, userId);
        MDC.put(MDC_PATH, path);
        // 把 traceId 也回写到响应头，方便前端/调用方收到后做客户端侧聚合
        httpResp.setHeader(HEADER_TRACE_ID, traceId);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("access method={} path={} status={} elapsedMs={} userId={}",
                    httpReq.getMethod(), path, httpResp.getStatus(), elapsed,
                    StrUtil.isBlank(userId) ? "-" : userId);
            MDC.clear();
        }
    }

    private static String pickTraceId(HttpServletRequest req) {
        String t = req.getHeader(HEADER_TRACE_ID);
        if (StrUtil.isNotBlank(t)) return t;
        t = req.getHeader(HEADER_TRACE_ID_ALT);
        if (StrUtil.isNotBlank(t)) return t;
        return UUID.randomUUID().toString();
    }
}

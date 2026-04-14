package com.jimeng.common.core.config;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.RequestUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @Author Moonlight
 * @Description 远程调用配置
 * @Date 2024/7/19 13:18
 */

@Configuration
public class FeignConfig implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate requestTemplate) {
        // 获取userId，设置请求头
        String currentUserId = RequestUtil.getCurrentUserId();
        if (!StrUtil.isBlank(currentUserId)) {
            requestTemplate.header("user-id", currentUserId);
        }
        // 传递 x-trace-id，保证跨服务链路可追踪
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String traceId = request.getHeader("x-trace-id");
            if (!StrUtil.isBlank(traceId)) {
                requestTemplate.header("x-trace-id", traceId);
            }
        }
    }

}

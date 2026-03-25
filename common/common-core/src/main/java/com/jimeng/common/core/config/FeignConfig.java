package com.jimeng.common.core.config;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.RequestUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Configuration;

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
    }

}

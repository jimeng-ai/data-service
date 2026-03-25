package com.jimeng.common.core.config;

import com.jimeng.common.core.interceptor.CommonInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @Author Moonlight
 * @Description 用户信息拦截器配置
 * @Date 2024/7/19 8:37
 */

@Configuration
public class CommonWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new CommonInterceptor()).addPathPatterns("/**");
    }

}

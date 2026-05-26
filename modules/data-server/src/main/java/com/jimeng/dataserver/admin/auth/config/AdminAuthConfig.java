package com.jimeng.dataserver.admin.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 注册 {@link BCryptPasswordEncoder} 单例。
 * <p>未引入完整 spring-security-starter，所以需要手动声明 bean。
 */
@Configuration
public class AdminAuthConfig {

    @Bean
    public BCryptPasswordEncoder bcryptPasswordEncoder() {
        // strength=10 是默认，~10ms / 次，足以抵御暴力破解
        return new BCryptPasswordEncoder();
    }
}

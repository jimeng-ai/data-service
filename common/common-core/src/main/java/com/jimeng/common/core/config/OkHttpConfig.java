package com.jimeng.common.core.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/9/14 22:56
 */

@Configuration
public class OkHttpConfig {

    @Value("${okhttp.read-timeout}")
    private Long readTimeout;

    @Value("${okhttp.connect-timeout}")
    private Long connectTimeout;

    @Value("${okhttp.write-timeout}")
    private Long writeTimeout;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .readTimeout(readTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .connectTimeout(connectTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
    }

}

package com.jimeng.common.core.config;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * @Author Moonlight
 * @Description OkHttp 客户端配置
 * @Date 2024/9/14 22:56
 *
 * <p>{@link #okHttpClient()}（{@code @Primary}，通用/LLM）：RequestService 注入，承载 LLM 调用（含流式）。
 * 默认 Dispatcher 的 {@code maxRequestsPerHost=5} 对同一 LLM 网关是隐形瓶颈（第 6 个并发流就排队），
 * 这里显式调高，并配连接池。读超时沿用 Nacos {@code okhttp.read-timeout}（LLM 需要大超时）。
 *
 * <p>2026-10 之前还有一个给 HTTP 类连接器用的短超时客户端，随 HTTP 类连接器一起删了。
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
    @Primary
    public OkHttpClient okHttpClient() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(512);
        dispatcher.setMaxRequestsPerHost(256);
        return new OkHttpClient.Builder()
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(64, 5, TimeUnit.MINUTES))
                .build();
    }

}

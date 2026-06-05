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
 * <p>拆成两个独立客户端，避免 LLM 长流与插件短连接共用一个池互相干扰：
 * <ul>
 *   <li>{@link #okHttpClient()}（{@code @Primary}，通用/LLM）：RequestService 注入，承载 LLM 调用（含流式）。
 *       默认 Dispatcher 的 {@code maxRequestsPerHost=5} 对同一 LLM 网关是隐形瓶颈（第 6 个并发流就排队），
 *       这里显式调高，并配连接池。读超时沿用 Nacos {@code okhttp.read-timeout}（LLM 需要大超时）。</li>
 *   <li>{@link #pluginHttpClient()}（插件）：PluginHttpInvoker 注入。独立 Dispatcher，短读超时，
 *       让挂死的插件后端快速失败、且不占用 LLM 的并发配额。</li>
 * </ul>
 */
@Configuration
public class OkHttpConfig {

    @Value("${okhttp.read-timeout}")
    private Long readTimeout;

    @Value("${okhttp.connect-timeout}")
    private Long connectTimeout;

    @Value("${okhttp.write-timeout}")
    private Long writeTimeout;

    /** 插件 HTTP 默认读超时（毫秒）。未配 Nacos key 时回落 30s，避免插件回落到 LLM 的大超时。 */
    @Value("${okhttp.plugin.read-timeout:30000}")
    private Long pluginReadTimeout;

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

    @Bean("pluginHttpClient")
    public OkHttpClient pluginHttpClient() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(256);
        dispatcher.setMaxRequestsPerHost(64);
        return new OkHttpClient.Builder()
                .readTimeout(pluginReadTimeout, TimeUnit.MILLISECONDS)
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .build();
    }

}

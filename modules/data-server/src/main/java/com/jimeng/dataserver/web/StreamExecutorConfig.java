package com.jimeng.dataserver.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 流式（SSE）任务统一线程池。
 *
 * <p>取代 4 个流式 Controller（Claude/OpenAI/RAG/AgentExec）各自 new 的
 * {@code Executors.newCachedThreadPool()}——后者无上限，上游 LLM 变慢或被刷请求时，
 * 每个流占一个线程且最长 {@code latch.await} 5 分钟，线程数随并发线性暴涨直至 OOM，
 * 拖垮同 JVM 内的 web/admin/RAG。
 *
 * <p>设计为"有硬上限的 cachedThreadPool"：{@code queueCapacity=0}（底层 SynchronousQueue，
 * 直接交接），任务来了直接起线程、增长到 {@code maxPoolSize} 为止；超过则
 * {@link ThreadPoolExecutor.CallerRunsPolicy} 由调用线程兜底执行（绝不丢任务、绝不无限涨）。
 * 正常负载下行为与原 cachedThreadPool 一致，只在并发流 &gt; maxPoolSize 时才退化为调用线程兜底。
 *
 * <p>当前是项目里唯一的 {@link java.util.concurrent.Executor} bean，会抑制 Spring Boot 默认的
 * applicationTaskExecutor（项目未用 @EnableAsync，无影响）。后续若引入 @Async，请用
 * {@code @Async("具体执行器名")} 显式指定，勿复用本流式池。
 */
@Configuration
public class StreamExecutorConfig {

    @Bean("streamExecutor")
    public ThreadPoolTaskExecutor streamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(200);
        executor.setQueueCapacity(0);          // SynchronousQueue：直接交接，先涨线程到 max 再拒绝
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

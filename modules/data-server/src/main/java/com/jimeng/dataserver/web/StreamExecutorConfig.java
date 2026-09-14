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
 *
 * <h3>★ 什么活儿<b>不能</b>放上面那两个池</h3>
 * 两者都是 {@code queueCapacity=0} + {@link ThreadPoolExecutor.CallerRunsPolicy}：池满时任务
 * <b>就地跑在调用线程上</b>。对一个几秒钟的 SSE 流，那只是慢一次；对一个以<b>分钟</b>计的后台作业，
 * 那是把几十分钟直接加到某次 HTTP 请求的响应时间上——网关早就读超时了，而调用方看到的是
 * 「建连接超时」，没有任何线索指向真凶是一个后台剖析任务。
 * 长作业要自己的池、自己的队列，见 {@link #semanticStageExecutor()}。
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

    /**
     * 续播泵专用线程池：每个观众（GET /runs/{id}/stream）占一个线程做短超时阻塞读 Redis Stream。
     * 与 {@code streamExecutor} 隔离——绝不让阻塞读占用生成线程，也避免生成池的 CallerRunsPolicy
     * 把阻塞泵压回 Tomcat 线程。容量耗尽时同样 CallerRunsPolicy 兜底（仅在并发观众 &gt; max 时退化）。
     */
    @Bean("runPumpExecutor")
    public ThreadPoolTaskExecutor runPumpExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("run-pump-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 语义层<b>采样验证阶段</b>（S3 表关系包含性验证 + S4 值域采集）专用线程池。
     *
     * <h3>为什么不能复用 {@code streamExecutor}</h3>
     * 这两件事都是<b>自我节流</b>的：S3 每分钟最多 30 次探查（200 条候选 ≈ 20 分钟），
     * S4 每分钟最多 20 条语句（200 列 ≈ 30 分钟）。节流不是可以调快的性能问题，
     * 它是「别把客户的生产库打爆」这条纪律的实现方式，所以一个阶段跑<b>半小时</b>是正常形态。
     *
     * <p>而 {@code streamExecutor} 是 {@code SynchronousQueue} + {@code CallerRunsPolicy}：
     * 并发流一旦超过 {@code maxPoolSize}，新任务<b>就地跑在调用线程上</b>。派发采样验证的那条调用
     * 线程是一次「新建连接」或「重新生成语义层」的 HTTP 请求线程——把半小时的剖析压回去，
     * 这次建连就永远回不去了。慢一次看得见、丢一次看不见那条取舍在这里不成立：
     * <b>这里两种都看不见，而且慢的那一种还会顺手拖垮一次本来已经成功的建连。</b>
     *
     * <h3>所以这个池的三个参数都是反过来选的</h3>
     * <ul>
     *   <li><b>有界队列</b>（不是 SynchronousQueue）：排队是这类作业的正常状态，不是异常。</li>
     *   <li><b>core = max</b>：{@code ThreadPoolExecutor} 只在<b>队列满了之后</b>才把线程数涨过
     *       core，而这里的队列有 64 个坑——写成 {@code core=2, max=8} 的话，第 3 个线程要等到
     *       积压 64 个作业才会出现，{@code maxPoolSize} 形同虚设。两者写等就是说实话：
     *       同时最多 4 条连接在被剖析。</li>
     *   <li><b>{@link ThreadPoolExecutor.AbortPolicy}</b>（默认策略，这里显式写出来）：满了就拒，
     *       派发方收到 {@code TaskRejectedException} 后<b>会把「这次没派出去」写进
     *       {@code connection.semantic_note}</b>。既不悄悄丢，也不就地跑。</li>
     * </ul>
     *
     * <p>队列 64 个坑 × 单个作业最长约 50 分钟，意味着极端积压下末尾的作业要等到很久以后。
     * 这是刻意的：语义层是叠加的注解，它晚一点到没关系；它把客户的库打爆、或者把一次建连拖超时，
     * 才是不能接受的。
     */
    @Bean("semanticStageExecutor")
    public ThreadPoolTaskExecutor semanticStageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        // 一个作业可能跑半小时，keepAlive 要明显大于「两批作业之间的间隙」才有意义；
        // 这里给 5 分钟，allowCoreThreadTimeOut 让空闲期不常驻 4 条线程。
        executor.setKeepAliveSeconds(300);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("semantic-stage-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关停时不等这半小时：阶段内部按 Thread.interrupted() 主动收手，已经落库的结论一条不丢
        //（每决完一条就写一条，见 ConnectorSemanticDeriveService 的 ProbeProgress 回调）。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

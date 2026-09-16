package com.jimeng.dataserver.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code semanticGenerationExecutor}（设计文档 6.4）：语义层 agent 生成的编排线程池。
 *
 * <p>设计文档对它的验证是「启动无 bean 冲突」，要真起进程。这里先钉住池子自己的三条行为，它们错了都不报错：
 * <ul>
 *   <li>同时只跑 1 个、只排 1 个信号，第三个<b>被拒而不是就地跑</b>——就地跑会把几十分钟的生成压到建连请求线程上；</li>
 *   <li>被拒抛的是 Spring 的 {@link TaskRejectedException}：编排器靠接住它把「排空中」标志复位；</li>
 *   <li>关停时中断正在跑的线程：编排器靠中断走 cancel 路径，不让发版卡在一片最长 20 分钟的等待上。</li>
 * </ul>
 */
class StreamExecutorConfigTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("★ 1 线程、队列 1、AbortPolicy：第三个任务被拒（TaskRejectedException），绝不在调用线程上就地跑")
    void 单线程队列1满了就拒() throws Exception {
        executor = new StreamExecutorConfig().semanticGenerationExecutor();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        assertEquals(1, pool.getCorePoolSize());
        assertEquals(1, pool.getMaximumPoolSize());
        assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
        assertEquals(1, pool.getQueue().remainingCapacity(), "队列容量应为 1：真正的队列在库里");

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        executor.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            started.countDown();
            awaitQuietly(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(threadName.get().startsWith("semantic-gen-"), "线程名前缀不对：" + threadName.get());

        executor.execute(() -> { });   // 占住唯一的队列位：「开始排空」的信号
        String caller = Thread.currentThread().getName();
        AtomicReference<String> ranOn = new AtomicReference<>();
        assertThrows(TaskRejectedException.class, () -> executor.execute(() -> ranOn.set(Thread.currentThread().getName())));
        assertTrue(ranOn.get() == null || !ranOn.get().equals(caller), "被拒的任务在调用线程上就地跑了");

        release.countDown();
    }

    @Test
    @DisplayName("★ 关停不等任务结束：正在跑的编排线程收到中断")
    void 关停时中断正在跑的线程() throws Exception {
        executor = new StreamExecutorConfig().semanticGenerationExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(60, TimeUnit.SECONDS);   // 模拟等一片沙箱运行
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        executor.shutdown();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "关停没有中断正在等待的编排线程");
        executor = null;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

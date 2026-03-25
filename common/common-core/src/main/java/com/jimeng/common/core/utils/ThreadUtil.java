package com.jimeng.common.core.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/10/13 9:30
 */

@Slf4j
public class ThreadUtil {

    // 最大线程数
    private static final int maximumPoolSize = 50;
    // 核心线程数
    private static final int corePoolSize = 10;
    // 线程存活时间
    private static final long keepAliveTime = 60L;
    // 队列容量
    private static final int capacity = 10;
    private static ThreadPoolExecutor threadPoolExecutor;

    public static ThreadPoolExecutor getThreadPoolExecutor(String threadName) {
        if (threadPoolExecutor == null) {
            threadPoolExecutor = new ThreadPoolExecutor(corePoolSize,
                    maximumPoolSize, keepAliveTime, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(capacity),
                    new ThreadFactoryBuilder().setNameFormat(threadName+"%d").build());
        }
        return threadPoolExecutor;
    }

    // 同步执行线程
    public static void submit(Runnable task, String threadName){
        ThreadPoolExecutor executor = getThreadPoolExecutor(threadName);
        Future<?> future = executor.submit(task);
        try {
            future.get();
        } catch (Exception e) {
            log.info("当前线程：{}，异常信息：{}", Thread.currentThread(), e.getMessage());
        }
    }

    // 异步执行线程
    public static void asyncExecute(Runnable task, String threadName){
        ThreadPoolExecutor executor = getThreadPoolExecutor(threadName);
        executor.execute(task);
    }

}

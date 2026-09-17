package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.dataserver.web.StreamExecutorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("单次语义推导生成器")
class SingleCallSemanticGeneratorTest {

    private ConnectorSemanticDeriveService deriveService;
    private AnnotationConfigApplicationContext context;
    private ThreadPoolTaskExecutor semanticGenerationExecutor;
    private ThreadPoolTaskExecutor semanticFallbackExecutor;
    private SingleCallSemanticGenerator generator;

    @BeforeEach
    void setUp() {
        deriveService = mock(ConnectorSemanticDeriveService.class);
        context = new AnnotationConfigApplicationContext();
        context.register(StreamExecutorConfig.class, SingleCallSemanticGenerator.class);
        context.registerBean(ConnectorSemanticDeriveService.class, () -> deriveService);
        context.refresh();
        semanticGenerationExecutor = context.getBean("semanticGenerationExecutor", ThreadPoolTaskExecutor.class);
        semanticFallbackExecutor = context.getBean("semanticFallbackExecutor", ThreadPoolTaskExecutor.class);
        generator = context.getBean(SingleCallSemanticGenerator.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MDC.clear();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 提交立即返回且推导只在fallback线程执行并继承调用时上下文() throws Exception {
        CountDownLatch deriveStarted = new CountDownLatch(1);
        CountDownLatch releaseDerive = new CountDownLatch(1);
        CountDownLatch submitReturned = new CountDownLatch(1);
        AtomicReference<String> deriveThread = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> connectionId = new AtomicReference<>();
        AtomicReference<String> prefix = new AtomicReference<>();
        AtomicReference<GenerationAck> ack = new AtomicReference<>();
        doAnswer(invocation -> {
            deriveThread.set(Thread.currentThread().getName());
            tenant.set(TenantContext.get());
            traceId.set(MDC.get("traceId"));
            connectionId.set(MDC.get(MdcAsyncSupport.MDC_CONNECTION_ID));
            prefix.set(invocation.getArgument(1));
            deriveStarted.countDown();
            awaitQuietly(releaseDerive);
            return null;
        }).when(deriveService).deriveAsync(eq(7L), any());

        Thread caller = new Thread(() -> {
            TenantContext.set("tenant-http");
            MDC.put("traceId", "trace-http");
            try {
                GenerationRequest request = new GenerationRequest(
                        7L, "tenant-http", 9L, GenerationTrigger.MANUAL_REGENERATE, "sandbox 未配置");
                ack.set(generator.submit(request));
            } finally {
                TenantContext.clear();
                MDC.clear();
                submitReturned.countDown();
            }
        }, "http-request-test");

        caller.start();
        assertTrue(deriveStarted.await(5, TimeUnit.SECONDS), "后台推导没有启动");
        boolean returnedBeforeDeriveFinished = submitReturned.await(1, TimeUnit.SECONDS);
        releaseDerive.countDown();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(returnedBeforeDeriveFinished, "submit 等待了后台推导完成");
        assertFalse(caller.isAlive(), "模拟 HTTP 请求线程没有退出");
        assertNotEquals(caller.getName(), deriveThread.get(), "推导跑到了 HTTP 请求线程");
        assertTrue(deriveThread.get().startsWith("semantic-fallback-"), deriveThread.get());
        assertEquals("tenant-http", tenant.get());
        assertEquals("trace-http", traceId.get());
        assertEquals("semantic-fallback-7", connectionId.get());
        assertEquals("降级原因：sandbox 未配置", prefix.get());
        assertEquals(GeneratorKind.SINGLE_CALL, ack.get().kind());
        assertTrue(ack.get().accepted());
        assertNull(ack.get().generationId());
    }

    @Test
    void 空降级原因不伪造前缀() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        doAnswer(invocation -> {
            called.countDown();
            return null;
        }).when(deriveService).deriveAsync(7L, null);
        TenantContext.set("tenant-a");
        GenerationRequest request = new GenerationRequest(
                7L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "  ");

        GenerationAck ack = generator.submit(request);

        assertTrue(ack.accepted());
        assertTrue(called.await(5, TimeUnit.SECONDS));
        verify(deriveService).deriveAsync(7L, null);
    }

    @Test
    void agent编排池被长任务占用不影响fallback及时提交() throws Exception {
        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);
        CountDownLatch deriveCalled = new CountDownLatch(1);
        doAnswer(invocation -> {
            deriveCalled.countDown();
            return null;
        }).when(deriveService).deriveAsync(eq(8L), any());
        semanticGenerationExecutor.execute(() -> {
            agentStarted.countDown();
            awaitQuietly(releaseAgent);
        });
        assertTrue(agentStarted.await(5, TimeUnit.SECONDS));
        TenantContext.set("tenant-a");

        GenerationAck ack;
        boolean ranWhileAgentBlocked;
        try {
            ack = generator.submit(new GenerationRequest(
                    8L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "agent 不可用"));
            ranWhileAgentBlocked = deriveCalled.await(5, TimeUnit.SECONDS);
        } finally {
            releaseAgent.countDown();
        }

        assertTrue(ack.accepted(), ack.note());
        assertTrue(ranWhileAgentBlocked, "fallback 被 agent 编排池的长任务阻塞了");
    }

    @Test
    void 未启用agent时多个并发submit不会退化为单线程队列1() throws Exception {
        int submissions = 3;
        CountDownLatch derivesStarted = new CountDownLatch(submissions);
        CountDownLatch releaseDerives = new CountDownLatch(1);
        doAnswer(invocation -> {
            derivesStarted.countDown();
            awaitQuietly(releaseDerives);
            return null;
        }).when(deriveService).deriveAsync(any(), any());
        TenantContext.set("tenant-a");
        List<GenerationAck> acknowledgements = new ArrayList<>();

        boolean allStartedConcurrently;
        try {
            for (long connectorId = 1; connectorId <= submissions; connectorId++) {
                acknowledgements.add(generator.submit(new GenerationRequest(
                        connectorId, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "agent 未开启")));
            }
            allStartedConcurrently = derivesStarted.await(5, TimeUnit.SECONDS);
        } finally {
            releaseDerives.countDown();
        }

        assertTrue(acknowledgements.stream().allMatch(GenerationAck::accepted));
        assertTrue(allStartedConcurrently, "fallback 退化成了 agent 编排池的单线程 + 队列 1");
    }

    @Test
    void fallback池自己饱和时返回拒绝且不在调用线程推导() throws Exception {
        ThreadPoolExecutor pool = semanticFallbackExecutor.getThreadPoolExecutor();
        int workers = pool.getCorePoolSize();
        int queueCapacity = pool.getQueue().remainingCapacity();
        CountDownLatch workersStarted = new CountDownLatch(workers);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            semanticFallbackExecutor.execute(() -> {
                workersStarted.countDown();
                awaitQuietly(releaseWorkers);
            });
        }
        assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < queueCapacity; i++) {
            semanticFallbackExecutor.execute(() -> { });
        }
        TenantContext.set("tenant-a");

        GenerationAck ack;
        try {
            ack = generator.submit(new GenerationRequest(
                    7L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "sandbox 未配置"));
        } finally {
            releaseWorkers.countDown();
        }

        assertEquals(GeneratorKind.SINGLE_CALL, ack.kind());
        assertFalse(ack.accepted());
        assertNull(ack.generationId());
        assertTrue(ack.note().startsWith("单次推导提交失败："), ack.note());
        verify(deriveService, never()).deriveAsync(any(), any());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

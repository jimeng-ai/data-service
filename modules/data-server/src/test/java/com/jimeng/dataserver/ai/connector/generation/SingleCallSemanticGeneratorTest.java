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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
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
    private ThreadPoolTaskExecutor semanticGenerationExecutor;
    private SingleCallSemanticGenerator generator;

    @BeforeEach
    void setUp() {
        deriveService = mock(ConnectorSemanticDeriveService.class);
        semanticGenerationExecutor = new StreamExecutorConfig().semanticGenerationExecutor();
        generator = new SingleCallSemanticGenerator(deriveService, semanticGenerationExecutor);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MDC.clear();
        if (semanticGenerationExecutor != null) {
            semanticGenerationExecutor.shutdown();
        }
    }

    @Test
    void 提交立即返回且推导只在语义生成线程执行并继承调用时上下文() throws Exception {
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
        assertTrue(deriveThread.get().startsWith("semantic-gen-"), deriveThread.get());
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
    void 线程池满时返回拒绝且不在调用线程推导() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        semanticGenerationExecutor.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(releaseWorker);
        });
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
        semanticGenerationExecutor.execute(() -> { });
        TenantContext.set("tenant-a");

        GenerationAck ack;
        try {
            ack = generator.submit(new GenerationRequest(
                    7L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "sandbox 未配置"));
        } finally {
            releaseWorker.countDown();
        }

        assertEquals(GeneratorKind.SINGLE_CALL, ack.kind());
        assertFalse(ack.accepted());
        assertNull(ack.generationId());
        assertTrue(ack.note().startsWith("单次推导提交失败："), ack.note());
        verify(deriveService, never()).deriveAsync(any(), any());
    }

    @Test
    void 语义生成线程内提交会入队并在当前任务返回后推导() throws Exception {
        CountDownLatch submitReturned = new CountDownLatch(1);
        CountDownLatch allowOuterReturn = new CountDownLatch(1);
        CountDownLatch deriveCalled = new CountDownLatch(1);
        AtomicReference<GenerationAck> ack = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> prefix = new AtomicReference<>();
        doAnswer(invocation -> {
            tenant.set(TenantContext.get());
            prefix.set(invocation.getArgument(1));
            deriveCalled.countDown();
            return null;
        }).when(deriveService).deriveAsync(eq(8L), any());

        semanticGenerationExecutor.execute(() -> {
            TenantContext.set("tenant-worker");
            try {
                ack.set(generator.submit(new GenerationRequest(
                        8L, "tenant-worker", 9L, GenerationTrigger.CONNECTOR_CREATED, "healthz 异常")));
                submitReturned.countDown();
                awaitQuietly(allowOuterReturn);
            } finally {
                TenantContext.clear();
            }
        });

        assertTrue(submitReturned.await(5, TimeUnit.SECONDS), "worker 内的 submit 没有返回");
        assertTrue(ack.get().accepted(), ack.get().note());
        verify(deriveService, never()).deriveAsync(any(), any());
        allowOuterReturn.countDown();

        assertTrue(deriveCalled.await(5, TimeUnit.SECONDS), "当前 worker 返回后队列任务没有执行");
        assertEquals("tenant-worker", tenant.get());
        assertEquals("降级原因：healthz 异常", prefix.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

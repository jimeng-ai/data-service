package com.jimeng.dataserver.ai.agent.exec.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SidecarClient}：派发日志只记 runId / runProfile（设计文档 6.8），以及语义层选择器要用的两个探测
 * {@code healthz(Duration)}、{@code capabilities(Duration)}（6.2）。
 *
 * <p>上游是本机 {@link MockWebServer}，不连真实边车。探测要钉的三件事都是「错了不报错」：
 * capabilities 漏带 service token → 永远 401、被当成「sandbox 鉴权失败」回落；
 * 探测沿用全局读超时 → 边车只收连接不回话时，建连请求白等 3 分钟；
 * 派发日志把 payload 打出来 → 模型 key 与回调 JWT 进 ES。
 */
class SidecarClientTest {

    private static final String SERVICE_TOKEN = "svc-SENTINEL-token";
    private static final String LLM_KEY = "sk-SENTINEL-deepseek";
    private static final String ACCESS_TOKEN = "eyJSENTINEL.semantic.callback";

    private MockWebServer server;
    private SidecarClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AgentSandboxProperties props = new AgentSandboxProperties();
        props.setBaseUrl(server.url("/").toString());   // 带尾斜杠：拼路径时不能出现 //
        props.setServiceToken(SERVICE_TOKEN);
        // 共享客户端读超时 60 秒：探测若沿用它，下面「单次超时」那条就会等满 60 秒
        OkHttpClient shared = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        client = new SidecarClient(new RequestService(shared), props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static SidecarRunPayload payload(String runProfile) {
        SidecarRunPayload p = new SidecarRunPayload();
        p.setRunId("semgen-2099066794181541890-s1-a1");
        p.setTenantId("t_review");
        p.setPrompt("开始本片工作。先调用 get_run_scope 读取本片范围。");
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setAuthToken(LLM_KEY);
        llm.setModel("deepseek-flash");
        p.setLlm(llm);
        p.setRunProfile(runProfile);
        if (runProfile != null) {
            SidecarRunPayload.SemanticContext ctx = new SidecarRunPayload.SemanticContext();
            ctx.setAccessToken(ACCESS_TOKEN);
            ctx.setGenerationId("2099066794181541890");
            p.setSemanticContext(ctx);
        }
        return p;
    }

    @Nested
    @DisplayName("派发")
    class Run {

        private final Logger sidecarLog = (Logger) LoggerFactory.getLogger(SidecarClient.class);
        private final Logger requestLog = (Logger) LoggerFactory.getLogger(RequestService.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private Level sidecarLevel;
        private Level requestLevel;

        @BeforeEach
        void attach() {
            sidecarLevel = sidecarLog.getLevel();
            requestLevel = requestLog.getLevel();
            sidecarLog.setLevel(Level.TRACE);
            requestLog.setLevel(Level.TRACE);
            appender.start();
            sidecarLog.addAppender(appender);
            requestLog.addAppender(appender);
        }

        @AfterEach
        void detach() {
            sidecarLog.detachAppender(appender);
            requestLog.detachAppender(appender);
            appender.stop();
            sidecarLog.setLevel(sidecarLevel);
            requestLog.setLevel(requestLevel);
        }

        private void runAndAwait(SidecarRunPayload p) throws InterruptedException {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: summary\ndata: {\"status\":\"success\"}\n\n"));
            CountDownLatch done = new CountDownLatch(1);
            EventSource es = client.run(p, new EventSourceListener() {
                @Override
                public void onClosed(EventSource eventSource) {
                    done.countDown();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    done.countDown();
                }
            });
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS), "SSE 没有结束");
            } finally {
                es.cancel();
            }
        }

        private String logs() {
            return appender.list.stream()
                    .map(e -> e.getFormattedMessage() + " " + Arrays.toString(e.getArgumentArray()))
                    .collect(Collectors.joining("\n"));
        }

        @Test
        @DisplayName("★ 日志记 runId 与 runProfile，不含模型 key、回调 token、service token；请求照常带 token 与完整请求体")
        void 派发日志记runId与runProfile且不含密钥() throws Exception {
            runAndAwait(payload("semantic-layer"));

            RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(req);
            assertEquals("POST", req.getMethod());
            assertEquals("/sandbox/run", req.getPath());
            assertEquals(SERVICE_TOKEN, req.getHeader("x-service-token"));
            String body = req.getBody().readUtf8();
            assertTrue(body.contains(ACCESS_TOKEN) && body.contains(LLM_KEY), "请求体里的 token 被弄丢了：" + body);

            String all = logs();
            assertTrue(all.contains("semgen-2099066794181541890-s1-a1"), "日志里没有 runId：\n" + all);
            assertTrue(all.contains("runProfile=semantic-layer"), "日志里没有 runProfile：\n" + all);
            for (String secret : List.of(LLM_KEY, ACCESS_TOKEN, SERVICE_TOKEN)) {
                assertFalse(all.contains(secret), "日志里出现了密钥 " + secret + "：\n" + all);
            }
        }

        @Test
        @DisplayName("runProfile 未设时日志记 default，与边车对缺省的解释一致")
        void 未设runProfile记default() throws Exception {
            runAndAwait(payload(null));
            assertTrue(logs().contains("runProfile=default"), logs());
        }
    }

    @Nested
    @DisplayName("探测")
    class Probe {

        @Test
        @DisplayName("★ healthz：GET /healthz、不带 service token、原样返回状态码与正文（正文判定留给调用方）")
        void healthz不带token且原样返回() throws Exception {
            server.enqueue(new MockResponse().setBody("ok (AUTH DISABLED: SANDBOX_SERVICE_TOKEN not set)"));

            RequestService.HttpResp resp = client.healthz(Duration.ofSeconds(2));

            assertEquals(200, resp.getStatusCode());
            assertEquals("ok (AUTH DISABLED: SANDBOX_SERVICE_TOKEN not set)", resp.getBody());
            RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("GET", req.getMethod());
            assertEquals("/healthz", req.getPath());
            assertNull(req.getHeader("x-service-token"), "healthz 不校验 token，没必要把 token 多发一处");
        }

        @Test
        @DisplayName("★ capabilities：GET /sandbox/capabilities、带 x-service-token；401 / 404 原样返回不抛")
        void capabilities带token且原样返回状态码() throws Exception {
            server.enqueue(new MockResponse().setBody("{\"runProfiles\":[\"default\",\"semantic-layer\"],\"semanticToolsVersion\":1}"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
            server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

            RequestService.HttpResp ok = client.capabilities(Duration.ofSeconds(2));
            assertEquals(200, ok.getStatusCode());
            assertTrue(ok.getBody().contains("semantic-layer"));
            RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("GET", req.getMethod());
            assertEquals("/sandbox/capabilities", req.getPath());
            assertEquals(SERVICE_TOKEN, req.getHeader("x-service-token"));

            assertEquals(401, client.capabilities(Duration.ofSeconds(2)).getStatusCode());
            assertEquals(404, client.capabilities(Duration.ofSeconds(2)).getStatusCode());
        }

        @Test
        @DisplayName("★ 两个探测都按调用给的超时：边车收了连接不回话，300 毫秒就超时，不等共享客户端的 60 秒")
        void 探测使用单次超时() {
            for (int i = 0; i < 2; i++) {
                server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            }
            for (Runnable probe : Stream.<Runnable>of(
                    () -> client.healthz(Duration.ofMillis(300)),
                    () -> client.capabilities(Duration.ofMillis(300))).toList()) {
                long t0 = System.nanoTime();
                RuntimeException ex = assertThrows(RuntimeException.class, probe::run);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                assertInstanceOf(SocketTimeoutException.class, ex.getCause(), "期望读超时，实际：" + ex.getCause());
                assertTrue(ms < 10_000, "单次超时没生效，等了 " + ms + " 毫秒");
            }
        }
    }
}

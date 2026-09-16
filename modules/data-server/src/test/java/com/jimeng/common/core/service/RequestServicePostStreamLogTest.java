package com.jimeng.common.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestService#postStream}：日志只打 url 与请求体字节数，<b>不打请求体</b>（设计文档 6.8、7.10）。
 *
 * <p>为什么要单测钉住：派发沙箱边车的请求体里有真实计费的模型 key（{@code llm.authToken}）和能以用户身份回调网关的 JWT
 * （{@code semanticContext.accessToken}、{@code ragContext.accessToken}），此前这里以 INFO 打整个请求体，
 * 日志又经 Filebeat 进 ES。「顺手加回一行 debug 日志」是最容易发生的回退，而它不会让任何功能测试变红。
 *
 * <p>做法：在 {@code RequestService} 的 logger 上挂内存 appender，并把级别临时调到 TRACE——<b>任何级别</b>的日志里
 * 都不许出现请求体。上游是本机 {@link MockWebServer}，顺带确认请求体本身原样发了出去（去掉日志没有误伤请求）。
 * common-core 没有测试目录，与 {@code RequestServiceReadTimeoutTest} 一样放在 data-server 里。
 */
class RequestServicePostStreamLogTest {

    private static final String LLM_KEY = "sk-SENTINEL-deepseek-key-0f3a";
    private static final String CALLBACK_JWT = "eyJSENTINEL.callback.jwt";

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        server.shutdown();
    }

    @Test
    @DisplayName("★ postStream 的日志不含请求体：只有 url 与字节数，密钥哨兵值在任何级别的日志里都找不到")
    void postStream日志不含请求体() throws Exception {
        // 形状照抄语义层派发的 payload：含中文（字节数 ≠ 字符数）、模型 key、回调 JWT
        String body = "{\"runId\":\"semgen-1-s1-a1\",\"prompt\":\"开始本片工作。先调用 get_run_scope 读取本片范围。\","
                + "\"llm\":{\"authToken\":\"" + LLM_KEY + "\"},"
                + "\"semanticContext\":{\"accessToken\":\"" + CALLBACK_JWT + "\",\"sliceNo\":1}}";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: summary\ndata: {\"status\":\"success\"}\n\n"));
        String url = server.url("/sandbox/run").toString();

        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)   // 本机若配了系统代理，回环请求不能被它截走
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        CountDownLatch done = new CountDownLatch(1);
        EventSource es = new RequestService(client).postStream(url, Map.of("x-service-token", "svc"), body,
                new EventSourceListener() {
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

        // 请求本身不受影响：请求体逐字节原样发出
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(recorded, "上游没收到请求");
        assertEquals(body, recorded.getBody().readUtf8());

        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertFalse(events.isEmpty(), "postStream 一行日志都没打——url 与字节数也是排查要用的");
        String all = events.stream()
                // 连参数数组一起看：占位符少于参数时，多出来的参数不进 formattedMessage，但 JSON 编码器照样会把它写出去
                .map(e -> e.getFormattedMessage() + " " + e.getMessage() + " " + Arrays.toString(e.getArgumentArray()))
                .collect(Collectors.joining("\n"));
        assertFalse(all.contains(LLM_KEY), "日志里出现了模型 key：\n" + all);
        assertFalse(all.contains(CALLBACK_JWT), "日志里出现了回调 JWT：\n" + all);
        assertFalse(all.contains("开始本片工作"), "日志里出现了请求体的其它内容（prompt）：\n" + all);
        assertFalse(all.contains("\"authToken\""), "日志里出现了请求体的键名：\n" + all);

        // 仍然记下排查要用的两件事：打到哪、发了多大（UTF-8 字节，不是字符数）
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes > body.length(), "夹具要含多字节字符，才能区分字节数与字符数");
        assertTrue(events.stream().anyMatch(e -> e.getFormattedMessage().contains(url)
                        && e.getFormattedMessage().contains(bytes + " 字节")),
                "日志里没有 url 与 UTF-8 字节数：\n" + all);
    }
}

package com.jimeng.common.core.service;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestService#post(String, Map, Map, Map, Duration)}：单次读超时只作用于这一次调用。
 *
 * <p>common-core 没有测试目录，放在 data-server 里测（与 {@code TenantContextTest} 同样的做法）。
 * 上游是本机回环上的假服务：「沉默上游」只收连接、永远不回响应头，模拟非流式 LLM 还在生成；
 * 「立刻回 200 的上游」用来看连接是否回到了共享池。不连任何外部服务。
 *
 * <p>callTimeout 没法靠「等它触发」来测：它比读超时多出 {@link RequestService#CALL_TIMEOUT_GRACE}，
 * 最小也得白等 60 秒以上。改用 {@link TimeoutProbe} 在拦截器里直接读 OkHttp 这一次<b>实际拿去计时</b>的值。
 */
class RequestServiceReadTimeoutTest {

    private final List<Closeable> closeables = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 测试收尾，关不掉也无所谓
            }
        }
    }

    private static OkHttpClient shared(long readTimeoutMs) {
        return new OkHttpClient.Builder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                // 本机若配了系统代理，回环请求不能被它截走
                .proxy(Proxy.NO_PROXY)
                .build();
    }

    /** 收下连接、不读不写，直到测试结束。 */
    private String silentUpstream() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        closeables.add(server);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    closeables.add(server.accept());
                } catch (IOException e) {
                    return;
                }
            }
        }, "silent-upstream");
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
    }

    /** 每条连接答一次：读完请求头与请求体，回一个 keep-alive 的 200 {}。 */
    private String okUpstream() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        closeables.add(server);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return;
                }
                closeables.add(s);
                try {
                    answerOnce(s);
                } catch (IOException ignored) {
                    // 这条连接出错不影响下一条
                }
            }
        }, "ok-upstream");
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
    }

    private static void answerOnce(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        in.readNBytes(contentLength);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("★ 单次读超时生效：共享客户端 60 秒，这一次给 300 毫秒，就按 300 毫秒超时")
    void 单次读超时生效() throws IOException {
        RequestService rs = new RequestService(shared(60_000));
        String url = silentUpstream();

        long t0 = System.nanoTime();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> rs.post(url, Map.of(), Map.of(), Map.of("model", "x"), Duration.ofMillis(300)));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertInstanceOf(SocketTimeoutException.class, ex.getCause(), "期望读超时，实际：" + ex.getCause());
        assertTrue(ms < 10_000, "单次超时没生效，等了 " + ms + " 毫秒");
    }

    @Test
    @DisplayName("★ 共享客户端本身不被改：这一次的超时不外溢给其它调用方")
    void 共享客户端不被改() throws IOException {
        OkHttpClient shared = shared(60_000);
        RequestService rs = new RequestService(shared);
        String url = silentUpstream();

        assertThrows(RuntimeException.class,
                () -> rs.post(url, Map.of(), Map.of(), Map.of(), Duration.ofMillis(200)));

        assertEquals(60_000, shared.readTimeoutMillis());
        assertEquals(0, shared.callTimeoutMillis());
    }

    @Test
    @DisplayName("readTimeout 为 null 与 4 参版本一致：沿用共享客户端的超时")
    void null沿用共享超时() throws IOException {
        RequestService rs = new RequestService(shared(300));
        String url = silentUpstream();

        RuntimeException viaNull = assertThrows(RuntimeException.class,
                () -> rs.post(url, Map.of(), Map.of(), Map.of(), null));
        assertInstanceOf(SocketTimeoutException.class, viaNull.getCause());

        RuntimeException viaFourArgs = assertThrows(RuntimeException.class,
                () -> rs.post(url, Map.of(), Map.of(), Map.of()));
        assertInstanceOf(SocketTimeoutException.class, viaFourArgs.getCause());
    }

    @Test
    @DisplayName("派生客户端共享连接池：这一次用完的连接回到共享池，别的调用方能复用")
    void 共享连接池() throws IOException {
        OkHttpClient shared = shared(60_000);
        RequestService rs = new RequestService(shared);

        RequestService.HttpResp resp = rs.post(okUpstream(), Map.of(), Map.of(), Map.of("model", "x"),
                Duration.ofSeconds(5));

        assertEquals(200, resp.getStatusCode());
        assertEquals("{}", resp.getBody());
        assertEquals(1, shared.connectionPool().connectionCount(),
                "连接没有回到共享池——派生客户端另起了一个池？");
    }

    @Test
    @DisplayName("非正数超时直接拒绝：OkHttp 里 0 是「不限时」，不是调用方想要的上限")
    void 非正数拒绝() {
        RequestService rs = new RequestService(shared(1000));

        assertThrows(IllegalArgumentException.class,
                () -> rs.post("http://127.0.0.1:1/x", Map.of(), Map.of(), Map.of(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> rs.post("http://127.0.0.1:1/x", Map.of(), Map.of(), Map.of(), Duration.ofSeconds(-1)));
    }

    // ================================================================ callTimeout：探针读实际生效值

    /**
     * 挂在共享客户端上的应用拦截器：记下这一次调用<b>实际生效</b>的 callTimeout 与 readTimeout，
     * 然后不调 proceed、直接回一个假 200——不建连、不花时间。
     *
     * <p>读的是 OkHttp 自己的值，不是测试里重算的数：{@code chain.call().timeout()} 就是 RealCall
     * 用来掐整通调用的那个 AsyncTimeout（值取自客户端的 callTimeoutMillis，0 = 不限时）；
     * {@code chain.readTimeoutMillis()} 是这次调用的客户端读超时。应用拦截器排在建连之前，
     * 所以 URL 指向一个没人听的端口也不会真的发出去。
     */
    private static final class TimeoutProbe implements Interceptor {
        final AtomicLong callTimeoutMs = new AtomicLong(-1);
        final AtomicLong readTimeoutMs = new AtomicLong(-1);

        @Override
        public Response intercept(Chain chain) {
            callTimeoutMs.set(TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos()));
            readTimeoutMs.set(chain.readTimeoutMillis());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{}", MediaType.get("application/json")))
                    .build();
        }

        void reset() {
            callTimeoutMs.set(-1);
            readTimeoutMs.set(-1);
        }
    }

    private static final String UNREACHABLE = "http://127.0.0.1:1/v1/chat/completions";

    @Test
    @DisplayName("★ 给了单次读超时就同时设上 callTimeout = 读超时 + 余量：慢慢吐字节的上游只剩它这一道硬上限")
    void 单次超时同时设上callTimeout() {
        TimeoutProbe probe = new TimeoutProbe();
        RequestService rs = new RequestService(shared(60_000).newBuilder().addInterceptor(probe).build());

        RequestService.HttpResp resp = rs.post(UNREACHABLE, Map.of(), Map.of(), Map.of("model", "x"),
                Duration.ofSeconds(900));

        assertEquals(200, resp.getStatusCode(), "探针没拦住，请求走到了真实网络");
        assertEquals(900_000, probe.readTimeoutMs.get());
        // 900 秒 + CALL_TIMEOUT_GRACE（60 秒）= 960_000。读到 0 就是 callTimeout 没设上：上游每隔一会儿吐几个字节，
        // 读超时永远不触发，这次调用就能无限期占着推导线程，一路拖过认领过期的 30 分钟
        // （ConnectorSemanticDeriveServiceTest「区间内原样与上限不变式」算的最坏占用正是建立在这道上限上）。
        assertEquals(Duration.ofSeconds(900).plus(RequestService.CALL_TIMEOUT_GRACE).toMillis(),
                probe.callTimeoutMs.get(), "callTimeout 没按「读超时 + 余量」设上");
        assertTrue(probe.callTimeoutMs.get() > probe.readTimeoutMs.get(),
                "callTimeout 不比读超时大，会抢在读超时之前掐掉一次本来来得及的调用");
    }

    @Test
    @DisplayName("4 参版本与 readTimeout=null 不设 callTimeout、读超时沿用共享值：其它调用方零变化")
    void 四参与null不设callTimeout() {
        TimeoutProbe probe = new TimeoutProbe();
        RequestService rs = new RequestService(shared(60_000).newBuilder().addInterceptor(probe).build());

        assertEquals(200, rs.post(UNREACHABLE, Map.of(), Map.of(), Map.of()).getStatusCode());
        assertEquals(0, probe.callTimeoutMs.get(), "4 参版本被加上了整通调用上限");
        assertEquals(60_000, probe.readTimeoutMs.get());

        probe.reset();
        assertEquals(200, rs.post(UNREACHABLE, Map.of(), Map.of(), Map.of(), null).getStatusCode());
        assertEquals(0, probe.callTimeoutMs.get(), "readTimeout=null 被加上了整通调用上限");
        assertEquals(60_000, probe.readTimeoutMs.get());
    }
}

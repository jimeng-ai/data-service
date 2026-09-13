package com.jimeng.dataserver.ai.connector.impl.http;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.HttpCallGuard;
import com.jimeng.dataserver.ai.connector.model.InvokeResult;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.InvokeCapable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 一次 HTTP 接口会话。无状态：每次 invoke 自己发一个请求。 */
@Slf4j
public class HttpSession implements ConnectorSession, InvokeCapable {

    /** 模型不许覆盖的请求头——覆盖它们等于顶掉平台注入的身份或改变目标主机。 */
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "authorization", "x-api-key", "host", "content-length", "cookie");

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ConnectorInstance instance;
    private final OkHttpClient client;
    private final HttpCallGuard callGuard;
    private final String baseUrl;
    private final String credential;
    private final String authScheme;
    private final int timeoutSec;
    private final int maxResponseBytes;

    HttpSession(ConnectorInstance instance, OkHttpClient client, HttpCallGuard callGuard,
                String baseUrl, String credential, String authScheme, int timeoutSec, int maxResponseBytes) {
        this.instance = instance;
        this.client = client;
        this.callGuard = callGuard;
        this.baseUrl = baseUrl;
        this.credential = credential;
        this.authScheme = authScheme == null ? "bearer" : authScheme.toLowerCase(Locale.ROOT);
        this.timeoutSec = timeoutSec;
        this.maxResponseBytes = maxResponseBytes;
    }

    // ================================================================ 探测三步

    /**
     * 探活。
     *
     * <p><b>4xx 也算连得上。</b>这条判据容易写错：把 404 当成「连不上」会让一堆
     * baseUrl 指向子路径（{@code https://api.x.com/v1}）的正常连接在接入时就被拒。
     * 我们要判的是「这台服务在不在、网络通不通」，那只有连接层失败才说明不通。
     * 5xx 才算对方有问题。
     */
    @Override
    public void ping() {
        Request req = new Request.Builder()
                .url(HttpUrl.get(normalizeBase(baseUrl)))
                .head()
                .build();
        try (Response resp = newClient(10).newCall(req).execute()) {
            if (resp.code() >= 500) {
                throw ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR,
                        "目标服务返回了 " + resp.code());
            }
        } catch (ConnectorException e) {
            throw e;
        } catch (IOException e) {
            throw classify(e, "探活");
        } catch (IllegalArgumentException e) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "接口基地址不是合法的 URL");
        }
    }

    /**
     * ★ HTTP 的只读<b>不是靠试写来验的</b>，而是看方法白名单。
     *
     * <p>这和数据库连接器有本质区别，必须讲清楚：MySQL 那边我们能真的试一条 UPDATE、
     * 让<b>客户的数据库</b>来拒绝我们——承重的是客户侧的授权，我们改不了。
     * HTTP 这边没有等价物：对方接口没有「只读账号」这个概念，我们也不可能真发一个 POST
     * 去试（那可能真的建一张单）。所以承重的只能是<b>我们自己的方法白名单</b>。
     *
     * <p>由此：这里返回 {@code confirmed} 的含义比数据库那边弱一档。
     * 界面上不该把两者显示成同一个「已验证只读」。
     */
    @Override
    public ReadOnlyVerdict verifyReadOnly() {
        Set<String> methods = methodSet();
        Set<String> writeMethods = new LinkedHashSet<>(methods);
        writeMethods.removeAll(IDEMPOTENT_METHODS);
        if (writeMethods.isEmpty()) {
            return ReadOnlyVerdict.confirmed("方法白名单只含只读方法（" + String.join(" / ", methods) + "）");
        }
        // 不是错误，是如实报告：这条连接被显式授予了写方法。
        return ReadOnlyVerdict.writable("方法白名单包含写方法：" + String.join(" / ", writeMethods));
    }

    @Override
    public Set<Capability> probeCapabilities() {
        return Set.of(Capability.INVOKE, Capability.HEALTH);
    }

    // ================================================================ 能调用

    @Override
    public boolean isIdempotent(String operation) {
        return IDEMPOTENT_METHODS.contains(methodOf(operation));
    }

    @Override
    @SuppressWarnings("unchecked")
    public InvokeResult invoke(String operation, Map<String, Object> params) {
        String method = methodOf(operation);
        String path = pathOf(operation);

        // 白名单 + 路径穿越 + SSRF 全在这一步。它返回的是拼好、已校验的最终 URL。
        String finalUrl = callGuard.check(baseUrl, method, path, methodsCsv(), pathsJson());

        HttpUrl url = HttpUrl.parse(finalUrl);
        if (url == null) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "拼接出的请求地址不合法");
        }
        // query 参数追加在 guard 校验之后：它只影响查询串，不改变主机与路径，
        // 所以不会绕过上面任何一道校验。
        Object q = params == null ? null : params.get("query");
        if (q instanceof Map<?, ?> qm) {
            HttpUrl.Builder b = url.newBuilder();
            for (Map.Entry<?, ?> e : qm.entrySet()) {
                if (e.getKey() == null) continue;
                b.addQueryParameter(String.valueOf(e.getKey()),
                        e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            url = b.build();
        }

        Request.Builder rb = new Request.Builder().url(url);
        applyAuth(rb);
        applyHeaders(rb, params);
        rb.method(method, buildBody(method, params));

        long start = System.currentTimeMillis();
        try (Response resp = newClient(timeoutSec).newCall(rb.build()).execute()) {
            BodyRead body = readBody(resp.body());
            long elapsed = System.currentTimeMillis() - start;
            if (resp.code() >= 400) {
                throw statusToException(resp.code());
            }
            return new InvokeResult(resp.code(), body.text, body.truncated, elapsed);
        } catch (ConnectorException e) {
            throw e;
        } catch (IOException e) {
            throw classify(e, "调用接口");
        }
    }

    // ================================================================ 内部

    private void applyAuth(Request.Builder rb) {
        // 与沙箱 egress 代理的注入形状保持一致：同一条连接在两条执行路径上必须表现相同，
        // 否则「在沙箱里能调、在对话里调不通」会变成一个没人查得清的问题。
        if ("api-key".equals(authScheme)) {
            rb.header("X-API-Key", credential);
        } else {
            rb.header("Authorization", "Bearer " + credential);
        }
    }

    private void applyHeaders(Request.Builder rb, Map<String, Object> params) {
        Object h = params == null ? null : params.get("headers");
        if (!(h instanceof Map<?, ?> hm)) {
            return;
        }
        for (Map.Entry<?, ?> e : hm.entrySet()) {
            if (e.getKey() == null) continue;
            String k = String.valueOf(e.getKey());
            if (PROTECTED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                // 拒绝而不是静默忽略：静默忽略会让模型以为自己换了身份、然后对结果做出错误解读。
                // 平台自己的受保护请求头白名单，请求没发出去；模型去掉这个头重发即可。
                throw ConnectorException.of(ConnectorErrorCode.GUARD_BLOCKED,
                        "不允许自定义 " + k + " 请求头，身份与目标主机由连接配置决定");
            }
            rb.header(k, e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
    }

    private RequestBody buildBody(String method, Map<String, Object> params) {
        boolean bodyAllowed = !("GET".equals(method) || "HEAD".equals(method));
        Object b = params == null ? null : params.get("body");
        if (!bodyAllowed) {
            return null;
        }
        if (b == null) {
            // POST/PUT 没给 body 时要给一个空体，否则 OkHttp 直接抛 IllegalArgumentException。
            return RequestBody.create(new byte[0], null);
        }
        if (b instanceof String s) {
            return RequestBody.create(s, MediaType.parse("application/json; charset=utf-8"));
        }
        try {
            String json = CommonUtil.getObjectMapper().writeValueAsString(b);
            return RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        } catch (Exception e) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "请求体无法序列化成 JSON");
        }
    }

    private record BodyRead(String text, boolean truncated) {}

    /**
     * 读响应体，<b>带硬上限</b>。
     *
     * <p>不用 {@code body.string()}：它会把整个响应读进内存，客户接口返回一个 200MB 的导出文件
     * 就能把 JVM 打挂。这里按字节流读到上限为止，超了明确标记——静默截断会让模型把半截 JSON
     * 当成完整响应去解析。
     */
    private BodyRead readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return new BodyRead("", false);
        }
        try (InputStream in = body.byteStream()) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0;
            int n;
            boolean truncated = false;
            while ((n = in.read(buf)) > 0) {
                if (total + n > maxResponseBytes) {
                    out.write(buf, 0, maxResponseBytes - total);
                    truncated = true;
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return new BodyRead(out.toString(StandardCharsets.UTF_8), truncated);
        }
    }

    private OkHttpClient newClient(int seconds) {
        // newBuilder 共享连接池与 dispatcher，不会新建线程池。
        return client.newBuilder()
                .callTimeout(seconds, TimeUnit.SECONDS)
                .readTimeout(seconds, TimeUnit.SECONDS)
                .connectTimeout(Math.min(seconds, 10), TimeUnit.SECONDS)
                // 不跟随重定向：3xx 到别的主机就绕过了 baseUrl 白名单和 SSRF 校验，
                // 而且平台注入的 Authorization 头会跟着发到那个主机上去。
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private ConnectorException statusToException(int code) {
        // 响应体绝不进 safeDetail：它是客户系统返回的内容，可能含业务数据甚至凭据回显。
        return switch (code) {
            case 401 -> ConnectorException.of(ConnectorErrorCode.AUTH_FAILED, null);
            case 403 -> ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                    "目标接口拒绝了这次访问（403）");
            case 404 -> ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                    "目标接口不存在（404），请确认路径是否正确");
            case 408 -> ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
            case 429 -> ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, null);
            default -> code >= 500
                    ? ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR,
                        "目标系统返回了 " + code)
                    : ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR,
                        "目标接口返回了 " + code);
        };
    }

    private ConnectorException classify(IOException e, String action) {
        log.warn("HTTP 连接器{}失败 connectorId={}", action, instance.id(), e);
        if (e instanceof SocketTimeoutException || e instanceof java.io.InterruptedIOException) {
            return ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
        }
        if (e instanceof UnknownHostException || e instanceof ConnectException) {
            return ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null);
        }
        return ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null);
    }

    @Override
    public void close() {
        // 无状态，无需释放。
    }

    // ---------------------------------------------------------------- operation 解析

    /** {@code operation} 的形状是「GET /v1/orders」。 */
    static String methodOf(String operation) {
        if (operation == null || operation.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "operation 不能为空，形状应当是「GET /v1/orders」");
        }
        String[] parts = operation.trim().split("\\s+", 2);
        return parts[0].toUpperCase(Locale.ROOT);
    }

    static String pathOf(String operation) {
        String[] parts = operation.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "operation 缺少路径，形状应当是「GET /v1/orders」");
        }
        return parts[1].trim();
    }

    @SuppressWarnings("unchecked")
    private Set<String> methodSet() {
        Object v = instance.params().get("allowMethods");
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? Set.of("GET") : out;
    }

    private String methodsCsv() {
        return String.join(",", methodSet());
    }

    private String pathsJson() {
        Object v = instance.params().get("allowPaths");
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return null;   // null = 留空 = 等价于 ["/**"]，与 HttpCallGuard 的约定一致
        }
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "路径白名单无法序列化");
        }
    }

    private static String normalizeBase(String base) {
        return base.endsWith("/") ? base : base + "/";
    }
}

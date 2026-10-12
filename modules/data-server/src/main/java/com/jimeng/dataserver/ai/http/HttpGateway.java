package com.jimeng.dataserver.ai.http;

import com.jimeng.dataserver.ai.http.policy.AddressPolicy;
import com.jimeng.dataserver.ai.http.policy.HostPattern;
import com.jimeng.dataserver.ai.http.policy.PathPattern;
import com.jimeng.dataserver.ai.http.policy.PrivateAllowList;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 通用 HTTP 网关（设计文档 §4），全平台只有这一份。三个调用方——对话平面的 http_request、沙箱的 http_request、
 * 沙箱脚本的 $JM_HTTP——都到这里，按 §4.3 的八步处理一次请求：
 * <ol>
 *   <li>只认 http / https 的完整网址；</li>
 *   <li>找出 URL、请求头、请求体里的全部 {{名字}}（含编码过的写法）：每个都必须是本次可用 skill 的 key，
 *       且目标域名、方法、路径都在它的范围内，否则整次 403 并点名；</li>
 *   <li>目标域名必须由 key 或 skill 声明过（不限端口），或者有 skill 声明了 web:true（端口限 80 / 443）；</li>
 *   <li>DNS 解析出的所有记录都必须能连（{@link AddressPolicy}），锁定第一条去连，之后不再解析；</li>
 *   <li>替换占位符并发送：连接 10 秒；总超时取放行这个域名的 skill 里最大的 timeout-seconds；</li>
 *   <li>没替换过 key 的请求最多跟 3 跳重定向，每一跳重做第 3、4 步；替换过 key 的不跟，3xx 抹掉 key 后原样返回；</li>
 *   <li>回包里的 key 真值（所有正文、所有响应头）抹回 {{名字}}；工具 2 MB、脚本 50 MB，超出截断并注明；</li>
 *   <li>审计日志见 {@link HttpGatewayAudit}。</li>
 * </ol>
 *
 * <p>★ 这个类<b>从不抛异常</b>：所有失败都变成带 gatewayError 的结果，且错误信息先过 scrubber。对话循环会把执行器的异常信息
 * 原样交给模型，而 OkHttp 对非敏感头名的非法值会把值写进异常文案——那个值可能就是刚替换进去的 key。
 */
@Slf4j
@Component
public class HttpGateway {

    /** 编码过的路径分隔符：上游解码后可能变成另一个路径，有路径限制的 key 遇到它一律不发。 */
    private static final Pattern ENCODED_SEPARATOR = Pattern.compile("%(?:2[Ff]|5[Cc]|2[Ee])");
    /** 不转发给上游的请求头：逐跳头；Host / Content-Length 由 OkHttp 按真实请求填；Accept-Encoding 交给 OkHttp 透明解压，回包才能抹 key。 */
    private static final Set<String> DROP_REQUEST_HEADERS = Set.of("host", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "content-length",
            "accept-encoding");
    private static final Set<String> DROP_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "content-encoding");
    private static final Set<String> BODY_REQUIRED = Set.of("POST", "PUT", "PATCH");

    private record Authorization(boolean declared, List<String> skills, int timeoutSeconds) {}

    private record Pin(InetAddress address, HttpGatewayResult denied) {}

    private record Outcome(HttpGatewayResult result, List<String> skills, int hops) {
        static Outcome rejected(int status, String code, String message) {
            return new Outcome(HttpGatewayResult.error(status, code, message), List.of(), 0);
        }
    }

    // 系统 DNS 可能不响应中断；固定进程级容量，不为每个调用创建线程或无限排队。
    private static final java.util.concurrent.ThreadPoolExecutor DNS_WORKERS = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "http-gateway-dns");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private final HttpGatewayProperties props;
    private final SkillSecretService secrets;
    private final GatewayResolver resolver;
    private final AddressPolicy addressPolicy;
    private final OkHttpClient baseClient;

    @Autowired
    public HttpGateway(HttpGatewayProperties props, SkillSecretService secrets) {
        this(props, secrets, GatewayResolver.SYSTEM, AddressPolicy.standard(PrivateAllowList.parse(props.getAllowPrivate())));
    }

    HttpGateway(HttpGatewayProperties props, SkillSecretService secrets, GatewayResolver resolver, AddressPolicy addressPolicy) {
        this.props = props;
        this.secrets = secrets;
        this.resolver = resolver;
        this.addressPolicy = addressPolicy;
        this.baseClient = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)                 // 必须直连锁定的 IP：经代理等于让代理再解析一次 DNS
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(props.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(16, 1, TimeUnit.MINUTES))
                .build();
    }

    public HttpGatewayResult execute(HttpGatewayContext ctx, HttpGatewayRequest req) {
        if (ctx == null || req == null || ctx.plane() == null) {
            return HttpGatewayResult.error(400, "bad_request", "请求或运行上下文不完整");
        }
        long started = System.nanoTime();
        List<SkillKey> keys;
        try {
            keys = secrets.loadKeys(ctx.grants());
        } catch (RuntimeException e) {
            log.error("http-gateway 读不到 key tenant={} run={} err={}", ctx.tenantId(), ctx.runId(), e.getClass().getSimpleName());
            HttpGatewayResult r = HttpGatewayResult.error(500, "key_unavailable", "平台暂时读不到技能里的 key，请稍后再试");
            audit(ctx, new HttpGatewayRequest("", null, null, null), r, List.of(), 0, started, KeyScrubber.NONE);
            return r;
        }
        // ★ 下面三处都不能让异常逃出 execute（类注释的「从不抛」）：抹值器建不起来按读不到 key 处理，文案不带异常原文；
        // 审计写不出来只记 ERROR（只带异常类名），不影响返回。
        KeyScrubber scrubber;
        try {
            scrubber = new KeyScrubber(keys);
        } catch (RuntimeException e) {
            log.error("http-gateway 抹值器建不起来 tenant={} run={} err={}", ctx.tenantId(), ctx.runId(), e.getClass().getSimpleName());
            HttpGatewayResult r = HttpGatewayResult.error(500, "key_unavailable", "平台暂时读不到技能里的 key，请稍后再试");
            audit(ctx, new HttpGatewayRequest("", null, null, null), r, List.of(), 0, started, KeyScrubber.NONE);
            return r;
        }
        Outcome out;
        try {
            out = run(ctx, req, keys, scrubber);
        } catch (RuntimeException e) {
            out = new Outcome(HttpGatewayResult.error(502, "upstream_error",
                    "调用失败：" + scrubber.scrub(String.valueOf(e.getMessage()))), List.of(), 0);
        }
        if (out.result().gatewayError() != null) {
            HttpGatewayResult r = out.result();
            out = new Outcome(HttpGatewayResult.error(r.status(), r.gatewayError(), scrubber.scrub(r.message())), out.skills(), out.hops());
        }
        audit(ctx, req, out.result(), out.skills(), out.hops(), started, scrubber);
        return out.result();
    }

    private static void audit(HttpGatewayContext ctx, HttpGatewayRequest req, HttpGatewayResult r, List<String> skills,
                              int hops, long started, KeyScrubber scrubber) {
        try {
            HttpGatewayAudit.record(ctx, req, r, skills, hops, started, scrubber);
        } catch (RuntimeException e) {
            log.error("http-gateway 审计日志写入失败 tenant={} run={} err={}", ctx.tenantId(), ctx.runId(), e.getClass().getSimpleName());
        }
    }

    private Outcome run(HttpGatewayContext ctx, HttpGatewayRequest req, List<SkillKey> keys, KeyScrubber scrubber) {
        long requestStarted = System.nanoTime();
        // 第 1 步：方法、网址
        String method = req.method() == null ? "" : req.method().strip().toUpperCase(Locale.ROOT);
        if (!SkillSecretsFile.METHODS.contains(method)) {
            return Outcome.rejected(400, "bad_request", "不支持的方法：" + scrubber.scrub(String.valueOf(req.method())));
        }
        HttpUrl parsed = req.url() == null ? null : HttpUrl.parse(req.url().strip());
        if (parsed == null) {
            return Outcome.rejected(400, "bad_request", "网址不合法：只支持 http:// 或 https:// 开头的完整网址");
        }
        HttpUrl url = parsed.newBuilder().fragment(null).build();
        byte[] body = req.body() == null || req.body().length == 0 ? null : req.body();
        if (body != null && ("GET".equals(method) || "HEAD".equals(method))) {
            return Outcome.rejected(400, "bad_request", method + " 请求不能带请求体");
        }
        int requestCap = ctx.plane() == HttpGatewayContext.Plane.TOOL ? props.getToolRequestMaxBytes() : props.getScriptRequestMaxBytes();
        if (body != null && body.length > requestCap) {
            return Outcome.rejected(400, "bad_request", "请求内容过大，请缩短后再试");
        }
        List<Map.Entry<String, String>> headers = without(req.headers(), DROP_REQUEST_HEADERS);

        // 第 2 步：占位符
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : Placeholders.find(url.toString(), headers, body)) {
            String denied = resolveKey(name, keys, url, method, values);
            if (denied != null) return Outcome.rejected(403, "denied", scrubber.scrub(denied));
            if (values.get(name) == null) return Outcome.rejected(500, "key_unavailable", scrubber.scrub(
                    "平台读不到技能里的 key " + name + "（加密密钥没配置或已更换），重试没有用，请联系管理员"));
        }

        // 第 3 步：目标域名
        Authorization auth = authorize(ctx.grants(), keys, url);
        if (auth == null) return Outcome.rejected(403, "denied", notAllowed(ctx.grants(), url));

        boolean substituted = !values.isEmpty();
        HttpUrl target = substituted ? HttpUrl.parse(Placeholders.substituteUrl(url.toString(), values)) : url;
        if (target == null) return Outcome.rejected(400, "bad_request", "替换 key 之后网址不合法");
        List<Map.Entry<String, String>> sendHeaders = Placeholders.substituteHeaders(headers, values);
        // 模板检查不代替实际地址检查；不扫描或再次展开插入的 credential 原文。
        Map<String, String> finalValues = new LinkedHashMap<>();
        for (String name : values.keySet()) {
            String denied = resolveKey(name, keys, target, method, finalValues);
            if (denied != null || !Objects.equals(values.get(name), finalValues.get(name))) {
                return Outcome.rejected(403, "denied", scrubber.scrub(denied == null
                        ? "key " + name + " 替换后的地址不在原发送范围内" : denied));
            }
        }
        byte[] sendBody;
        try {
            sendBody = Placeholders.substituteBody(body, values, requestCap);
        } catch (Placeholders.BodyTooLarge e) {
            return Outcome.rejected(400, "bad_request", e.getMessage());
        }
        String sendMethod = method;
        long deadline = requestStarted + TimeUnit.SECONDS.toNanos(auth.timeoutSeconds());
        Authorization hopAuth = auth;

        for (int hops = 0; ; hops++) {
            // 第 4 步：解析、校验全部记录、锁定第一条
            Pin pin = pin(target.host(), hopAuth.declared(), deadline, auth);
            if (pin.denied() != null) return new Outcome(pin.denied(), auth.skills(), hops);
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) return new Outcome(timeout(auth), auth.skills(), hops);
            // 第 5 步：发送
            try (Response resp = send(target, sendMethod, sendHeaders, sendBody, pin.address(), leftMs)) {
                // 第 6 步：重定向
                String location = resp.header("Location");
                HttpUrl next = location == null ? null : target.resolve(location);
                if (resp.isRedirect() && !substituted && next != null && hops < props.getMaxRedirects()) {
                    Authorization nextAuth = authorize(ctx.grants(), keys, next);
                    if (nextAuth == null) {
                        return new Outcome(HttpGatewayResult.error(403, "denied", "重定向到了 " + next.host() + "："
                                + notAllowed(ctx.grants(), next)), auth.skills(), hops + 1);
                    }
                    int code = resp.code();
                    if (code == 303 || ((code == 301 || code == 302) && !"GET".equals(sendMethod) && !"HEAD".equals(sendMethod))) {
                        sendMethod = "GET";
                        sendBody = null;
                        sendHeaders = without(sendHeaders, Set.of("content-type"));
                    }
                    if (!next.host().equalsIgnoreCase(target.host())) {
                        sendHeaders = without(sendHeaders, Set.of("authorization", "cookie"));
                    }
                    target = next;
                    hopAuth = nextAuth;
                    continue;
                }
                // 第 7 步：回包
                return new Outcome(readResponse(resp, ctx.plane(), scrubber), auth.skills(), hops);
            } catch (InterruptedIOException e) {
                return new Outcome(timeout(auth), auth.skills(), hops);
            } catch (IOException e) {
                return new Outcome(HttpGatewayResult.error(502, "upstream_error",
                        "访问 " + target.host() + " 失败：" + scrubber.scrub(String.valueOf(e.getMessage()))), auth.skills(), hops);
            } catch (IllegalArgumentException e) {
                // OkHttp 对不合法的请求头直接抛这个，文案里可能带着头的值（也就是刚替换进去的 key）
                return new Outcome(HttpGatewayResult.error(400, "bad_request",
                        "请求头不合法：" + scrubber.scrub(String.valueOf(e.getMessage()))), auth.skills(), hops);
            }
        }
    }

    /** 第 2 步：一个占位符。通过时把值放进 values 并返回 null；否则返回拒绝理由（只有名字、域名、范围，没有值）。 */
    private static String resolveKey(String name, List<SkillKey> keys, HttpUrl url, String method, Map<String, String> values) {
        List<SkillKey> named = keys.stream().filter(k -> k.name().equals(name)).toList();
        if (named.isEmpty()) return "key " + name + " 不存在：已激活的技能里没有声明这个 key";
        String path = url.encodedPath();
        if (named.stream().anyMatch(k -> k.paths() != null)
                && (ENCODED_SEPARATOR.matcher(path).find() || path.contains("\\"))) {
            return "key " + name + " 限定了路径，请求路径里不能有编码过的分隔符（%2F、%5C、%2E）";
        }
        // ** 能匹配空段，而上游会把 // 合并：/v1/orders/**/items 匹配 /v1/orders//items，上游看到的却是不在 paths 里的 /v1/orders/items
        if (named.stream().anyMatch(k -> k.paths() != null) && path.contains("//")) {
            return "key " + name + " 限定了路径，请求路径里不能有空的一段（//）";
        }
        List<SkillKey> inScope = named.stream().filter(k -> inScope(k, url.host(), method, path)).toList();
        if (inScope.isEmpty()) {
            SkillKey k = named.get(0);
            return "key " + name + " 不允许发往 " + method + " " + url.host() + path + "（只能发往 " + String.join("、", k.hosts())
                    + (k.methods() == null ? "" : "，方法 " + String.join("、", k.methods()))
                    + (k.paths() == null ? "" : "，路径 " + String.join("、", k.paths())) + "）";
        }
        if (inScope.stream().anyMatch(k -> !k.available())) {
            values.put(name, null);
            return null;
        }
        if (inScope.stream().map(SkillKey::value).distinct().count() > 1) {
            return "key " + name + " 在多个技能里都声明了且值不同（"
                    + inScope.stream().map(SkillKey::skillName).distinct().collect(Collectors.joining("、")) + "），无法确定用哪一个";
        }
        values.put(name, inScope.get(0).value());
        return null;
    }

    private static boolean inScope(SkillKey k, String host, String method, String path) {
        return k.hosts().stream().anyMatch(h -> HostPattern.parse(h).matches(host))
                && (k.methods() == null || k.methods().contains(method))
                && (k.paths() == null || k.paths().stream().anyMatch(p -> PathPattern.parse(p).matches(path)));
    }

    /** 第 3 步：谁放行这个域名。key 或 skill 声明过 → declared（不限端口）；否则看 web（端口限 80 / 443）；都不是 → null。 */
    private static Authorization authorize(List<SkillGrant> grants, List<SkillKey> keys, HttpUrl url) {
        String host = url.host();
        Set<String> declaredBy = new LinkedHashSet<>();
        int declaredTimeout = 0;
        for (SkillGrant g : grants) {
            boolean viaHosts = g.manifest().hosts().stream().anyMatch(h -> HostPattern.parse(h).matches(host));
            boolean viaKeys = keys.stream().anyMatch(k -> Objects.equals(k.skillId(), g.skillId())
                    && k.hosts().stream().anyMatch(h -> HostPattern.parse(h).matches(host)));
            if (viaHosts || viaKeys) {
                declaredBy.add(g.skillName());
                declaredTimeout = Math.max(declaredTimeout, g.manifest().timeoutSeconds());
            }
        }
        if (!declaredBy.isEmpty()) return new Authorization(true, List.copyOf(declaredBy), declaredTimeout);
        if (url.port() != 80 && url.port() != 443) return null;
        Set<String> webBy = new LinkedHashSet<>();
        int webTimeout = 0;
        for (SkillGrant g : grants) {
            if (Boolean.TRUE.equals(g.manifest().web())) {
                webBy.add(g.skillName());
                webTimeout = Math.max(webTimeout, g.manifest().timeoutSeconds());
            }
        }
        return webBy.isEmpty() ? null : new Authorization(false, List.copyOf(webBy), webTimeout);
    }

    private static String notAllowed(List<SkillGrant> grants, HttpUrl url) {
        boolean anyWeb = grants.stream().anyMatch(g -> Boolean.TRUE.equals(g.manifest().web()));
        if (anyWeb && url.port() != 80 && url.port() != 443) {
            return "不允许访问 " + url.host() + ":" + url.port() + "：没有声明过的域名只能用 80 或 443 端口";
        }
        return "不允许访问 " + url.host() + "：已激活的技能没有声明这个域名";
    }

    /** 第 4 步：所有记录都必须能连，锁定第一条。解析不到是上游问题（502），不是拒绝。 */
    private Pin pin(String host, boolean declared, long deadline, Authorization auth) {
        List<InetAddress> records;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return new Pin(null, timeout(auth));
        java.util.concurrent.Future<List<InetAddress>> future;
        try {
            future = DNS_WORKERS.submit(() -> resolver.resolve(host));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return new Pin(null, HttpGatewayResult.error(502, "upstream_error", "目标地址解析暂时繁忙，请稍后再试"));
        }
        try {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            records = future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Pin(null, timeout(auth));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Pin(null, timeout(auth));
        } catch (java.util.concurrent.ExecutionException e) {
            records = List.of();
        } finally {
            if (!future.isDone()) future.cancel(true);
            // remove 已取消的排队任务，避免累计一批已经超时的 Future；卡住的系统 resolver 至多占固定四个 worker。
            DNS_WORKERS.remove((Runnable)future);
        }
        if (records == null || records.isEmpty()) {
            return new Pin(null, HttpGatewayResult.error(502, "upstream_error", "解析不到域名 " + host));
        }
        for (InetAddress a : records) {
            String reason = addressPolicy.check(host, a, declared);
            if (reason != null) return new Pin(null, HttpGatewayResult.error(403, "denied", reason));
        }
        return new Pin(records.get(0), null);
    }

    private Response send(HttpUrl url, String method, List<Map.Entry<String, String>> headers, byte[] body,
                          InetAddress pinned, long timeoutMs) throws IOException {
        String host = url.host();
        OkHttpClient client = baseClient.newBuilder()
                .dns(h -> {
                    if (h.equalsIgnoreCase(host)) return List.of(pinned);
                    throw new UnknownHostException("网关只连接已经校验过的地址");
                })
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> h : headers) rb.addHeader(h.getKey(), h.getValue());
        // contentType 传 null：保留调用方自己写的 Content-Type（含 charset），OkHttp 不会改写它
        RequestBody requestBody = body != null ? RequestBody.create(body, null)
                : BODY_REQUIRED.contains(method) ? RequestBody.create(new byte[0], null) : null;
        rb.method(method, requestBody);
        return client.newCall(rb.build()).execute();
    }

    private HttpGatewayResult readResponse(Response resp, HttpGatewayContext.Plane plane, KeyScrubber scrubber) throws IOException {
        int cap = plane == HttpGatewayContext.Plane.TOOL ? props.getToolMaxBytes() : props.getScriptMaxBytes();
        byte[] raw = new byte[0];
        ResponseBody rb = resp.body();
        if (rb != null) {
            // 多读「最长那种写法的长度」个字节：先抹再截
            raw = readUpTo(rb.byteStream(), (long) cap + scrubber.maxLength() + 1);
        }
        // R29：截断时，读到的最后一段里可能有一个 key 的前半截（后半截没读进来，抹不到）；抹值又会让前面变短，光「截到 cap」切不掉它
        // （预检实测 cap=1000 时漏出了 key 的前 21 个字符）。所以只要截了，就连抹值结果末尾 maxLength 个字节一起去掉。
        // 抹值的输出也有上限（占位符可能比值长，不能先整份放大再截）：KeyScrubber 的有界重载，名字和返回形状按 R28 你定，
        // 要能拿到结果和「是否因为到了上限而停下」。
        int max = scrubber.maxLength();
        KeyScrubber.Scrubbed s = scrubber.scrub(raw, cap + max);
        boolean truncated = raw.length > cap || s.stopped() || s.bytes().length > cap;
        byte[] body = truncated ? Arrays.copyOf(s.bytes(), Math.min(cap, Math.max(0, s.bytes().length - max))) : s.bytes();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : resp.headers().names()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (DROP_RESPONSE_HEADERS.contains(lower) || scrubber.secretBearingName(name)) continue;
            for (String v : resp.headers(name)) headers.add(Map.entry(lower, scrubber.scrub(v)));
        }
        return new HttpGatewayResult(resp.code(), headers, body, truncated, null, null);
    }

    private static HttpGatewayResult timeout(Authorization auth) {
        return HttpGatewayResult.error(504, "timeout", "请求超时（" + auth.timeoutSeconds() + " 秒）");
    }

    private static List<Map.Entry<String, String>> without(List<Map.Entry<String, String>> headers, Set<String> lowerNames) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey() == null || h.getValue() == null) continue;
            if (!lowerNames.contains(h.getKey().toLowerCase(Locale.ROOT))) out.add(h);
        }
        return out;
    }

    static byte[] readUpTo(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - total))) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }
}

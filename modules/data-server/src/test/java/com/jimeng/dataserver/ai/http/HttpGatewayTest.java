package com.jimeng.dataserver.ai.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimeng.dataserver.ai.http.policy.AddressPolicy;
import com.jimeng.dataserver.ai.http.policy.PrivateAllowList;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpGatewayTest {

    private static final String TOKEN = "sk-live/CRM+0123456789";
    private static final HttpGatewayContext.Plane TOOL = HttpGatewayContext.Plane.TOOL;
    private static final HttpGatewayContext.Plane SCRIPT = HttpGatewayContext.Plane.SCRIPT;

    /** 测试专用：127.0.0.1 当成公网（假的客户系统跑在本机上），其余地址照生产规则判。 */
    private static final AddressPolicy LOOPBACK_AS_PUBLIC = (host, ip, declared) ->
            ip.isLoopbackAddress() ? null : AddressPolicy.standard(PrivateAllowList.EMPTY).check(host, ip, declared);

    record Seen(String method, String path, String rawQuery, Map<String, List<String>> headers, byte[] body) {}

    interface Route {
        void handle(HttpExchange ex) throws Exception;
    }

    private java.util.concurrent.ExecutorService serverExecutor;
    private HttpServer server;
    private int port;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<String, List<InetAddress>> dns = new HashMap<>();
    private final HttpGatewayProperties props = new HttpGatewayProperties();
    private final SkillSecretService secrets = mock(SkillSecretService.class);
    private AddressPolicy policy = LOOPBACK_AS_PUBLIC;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            try {
                seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                        Map.copyOf(ex.getRequestHeaders()), ex.getRequestBody().readAllBytes()));
                routes.getOrDefault(ex.getRequestURI().getPath(), x -> reply(x, 200, "text/plain", "ok")).handle(ex);
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        port = server.getAddress().getPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (String h : List.of("api.customer.test", "other.test", "news.test", "down.test")) dns.put(h, List.of(loopback));
        dns.put("intranet.test", List.of(InetAddress.getByName("10.0.0.5")));
        dns.put("ula.test", List.of(InetAddress.getByName("fd00::1")));
        dns.put("nat64.test", List.of(InetAddress.getByName("64:ff9b::a00:1")));
        dns.put("mixed.test", List.of(loopback, InetAddress.getByName("10.0.0.1")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ helpers

    private static void reply(HttpExchange ex, int status, String contentType, Object body) throws IOException {
        byte[] b = body instanceof byte[] bytes ? bytes : String.valueOf(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    private static void redirect(HttpExchange ex, int status, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private HttpGateway gateway() { return gateway(secrets); }

    private HttpGateway gateway(SkillSecretService service) {
        GatewayResolver resolver = host -> {
            List<InetAddress> r = dns.get(host);
            if (r != null) return r;
            if (host.matches("[0-9.]+") || host.contains(":")) return List.of(InetAddress.getByName(host));
            throw new UnknownHostException(host);
        };
        return new HttpGateway(props, service, resolver, policy);
    }

    private String url(String host, String pathAndQuery) {
        return "http://" + host + ":" + port + pathAndQuery;
    }

    private static SkillGrant grant(long id, String name, List<String> hosts, boolean web, int timeoutSeconds) {
        return new SkillGrant(id, 1, name, new SkillManifest(hosts, web, timeoutSeconds, null, null, List.of()));
    }

    private static SkillKey key(long skillId, String skill, String name, String value, List<String> methods, List<String> paths) {
        return new SkillKey(name, skillId, skill, List.of("api.customer.test"), methods, paths, value);
    }

    private void keys(SkillKey... ks) {
        when(secrets.loadKeys(any())).thenReturn(List.of(ks));
    }

    private static HttpGatewayContext ctx(HttpGatewayContext.Plane plane, SkillGrant... grants) {
        return new HttpGatewayContext("t1", 5L, "run-1", plane, List.of(grants));
    }

    private static HttpGatewayRequest req(String method, String url, byte[] body, String... headerPairs) {
        List<Map.Entry<String, String>> h = new ArrayList<>();
        for (int i = 0; i < headerPairs.length; i += 2) h.add(Map.entry(headerPairs[i], headerPairs[i + 1]));
        return new HttpGatewayRequest(method, url, h, body);
    }

    private static String text(HttpGatewayResult r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static final SkillGrant CRM = grant(1L, "crm", List.of(), false, 60);

    // ------------------------------------------------------------------ 第 2 步：占位符

    @Test
    @DisplayName("占位符只在允许范围内替换：请求头、请求体、表单里编码过的写法都换成真值")
    void 占位符只在范围内替换() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, List.of("GET", "POST"), List.of("/v1/orders/**")));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("POST", url("api.customer.test", "/v1/orders/1"),
                "{\"t\":\"{{CRM_TOKEN}}\",\"f\":\"%7B%7BCRM_TOKEN%7D%7D\"}".getBytes(StandardCharsets.UTF_8),
                "Authorization", "Bearer {{CRM_TOKEN}}", "Content-Type", "application/json"));
        assertThat(r.gatewayError()).isNull();
        assertThat(r.status()).isEqualTo(200);
        Seen s = seen.get(0);
        assertThat(s.headers().get("Authorization")).containsExactly("Bearer " + TOKEN);
        assertThat(new String(s.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"t\":\"" + TOKEN + "\",\"f\":\"" + Placeholders.percentEncode(TOKEN) + "\"}");
    }

    @Test
    @DisplayName("编码过的占位符也能识别和替换（Python requests 会把查询串里的 {{K}} 编码成 %7B%7BK%7D%7D）")
    void 编码过的占位符也能识别和替换() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        HttpGatewayResult r = gateway().execute(ctx(SCRIPT, CRM), req("GET",
                url("api.customer.test", "/v1/{{CRM_TOKEN}}/x?token=%7B%7BCRM_TOKEN%7D%7D"), null));
        assertThat(r.gatewayError()).isNull();
        Seen s = seen.get(0);
        assertThat(URLDecoder.decode(s.rawQuery().substring("token=".length()), StandardCharsets.UTF_8)).isEqualTo(TOKEN);
        assertThat(URLDecoder.decode(s.path(), StandardCharsets.UTF_8)).isEqualTo("/v1/" + TOKEN + "/x");
    }

    @Test
    @DisplayName("范围外整次拒绝并点名：方法不对、域名不对；客户系统一次请求都收不到")
    void 范围外拒绝并点名() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, List.of("GET"), null));
        SkillGrant both = grant(1L, "crm", List.of("other.test"), false, 60);
        HttpGatewayResult post = gateway().execute(ctx(TOOL, both),
                req("POST", url("api.customer.test", "/v1"), "x".getBytes(), "X-Key", "{{CRM_TOKEN}}"));
        HttpGatewayResult elsewhere = gateway().execute(ctx(TOOL, both),
                req("GET", url("other.test", "/v1"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(post.status()).isEqualTo(403);
        assertThat(post.message()).contains("CRM_TOKEN").contains("POST");
        assertThat(elsewhere.status()).isEqualTo(403);
        assertThat(elsewhere.message()).contains("CRM_TOKEN").contains("other.test");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("没声明过的 key 拒绝")
    void 未声明的key拒绝() {
        keys();
        HttpGatewayResult r = gateway().execute(ctx(TOOL, grant(1L, "crm", List.of("api.customer.test"), false, 60)),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{NOPE_KEY}}"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("NOPE_KEY");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("同名key值相同可用：两个都激活的技能带着同一个 key，照常替换")
    void 同名key值相同可用() {
        keys(key(1L, "crm-orders", "CRM_TOKEN", TOKEN, null, null), key(2L, "crm-refunds", "CRM_TOKEN", TOKEN, null, null));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, grant(1L, "crm-orders", List.of(), false, 60), grant(2L, "crm-refunds", List.of(), false, 60)),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(r.gatewayError()).isNull();
        assertThat(seen.get(0).headers().get("X-key")).containsExactly(TOKEN);
    }

    @Test
    @DisplayName("同名key值不同拒绝，并说明是哪两个技能")
    void 同名key值不同拒绝() {
        keys(key(1L, "crm-orders", "CRM_TOKEN", TOKEN, null, null), key(2L, "crm-refunds", "CRM_TOKEN", "another-value-999", null, null));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, grant(1L, "crm-orders", List.of(), false, 60), grant(2L, "crm-refunds", List.of(), false, 60)),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("crm-orders").contains("crm-refunds");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("路径里编码的分隔符不能绕过paths：%2F、%5C 一律拒绝；%2e%2e 被 OkHttp 当成 .. 解析掉，落在 paths 之外同样拒绝")
    void 路径里编码的分隔符不能绕过paths() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, List.of("/v1/orders/*")));
        for (String path : List.of("/v1/orders/a%2F..%2Fadmin", "/v1/orders/x%5C..%5Cadmin")) {
            HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM),
                    req("GET", url("api.customer.test", path), null, "X-Key", "{{CRM_TOKEN}}"));
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.message()).as(path).contains("编码");
        }
        assertThat(gateway().execute(ctx(TOOL, CRM),
                req("GET", url("api.customer.test", "/v1/orders/%2e%2e"), null, "X-Key", "{{CRM_TOKEN}}")).status()).isEqualTo(403);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("路径里有空段（//）：限定了 paths 的 key 一律拒绝——** 能匹配空段，上游把 // 合并后看到的路径不在 paths 里")
    void 路径里的空段不能绕过paths() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, List.of("/v1/orders/**/items")));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM),
                req("GET", url("api.customer.test", "/v1/orders//items"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("//");
        assertThat(seen).isEmpty();
        // 没有路径限制的 key 不受影响：它本来就能发往这个域名的任何路径
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        assertThat(gateway().execute(ctx(TOOL, CRM),
                req("GET", url("api.customer.test", "/v1/orders//items"), null, "X-Key", "{{CRM_TOKEN}}")).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 第 3、4 步：目标地址

    @Test
    @DisplayName("各类内网地址都被拒绝：DNS 解析出私网 / ULA / NAT64 / 混合记录、IP 字面量")
    void 各类内网地址都被拒绝() {
        keys();
        SkillGrant g = grant(1L, "crm", List.of("intranet.test", "ula.test", "nat64.test", "mixed.test", "10.0.0.5"), false, 60);
        for (String host : List.of("intranet.test", "ula.test", "nat64.test", "mixed.test", "10.0.0.5")) {
            HttpGatewayResult r = gateway().execute(ctx(TOOL, g), req("GET", url(host, "/"), null));
            assertThat(r.status()).as(host).isEqualTo(403);
            assertThat(r.message()).as(host).contains("内网");
        }
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("IP被锁定不会重新解析：第一次解析是本机、之后改成私网，请求照常打到第一次的地址")
    void IP被锁定不会重新解析() throws Exception {
        keys();
        AtomicInteger calls = new AtomicInteger();
        InetAddress first = InetAddress.getByName("127.0.0.1");
        InetAddress later = InetAddress.getByName("10.0.0.9");
        HttpGateway g = new HttpGateway(props, secrets, host -> calls.getAndIncrement() == 0 ? List.of(first) : List.of(later), LOOPBACK_AS_PUBLIC);
        HttpGatewayResult r = g.execute(ctx(TOOL, grant(1L, "crm", List.of("pin.test"), false, 60)), req("GET", url("pin.test", "/"), null));
        assertThat(r.gatewayError()).isNull();
        assertThat(seen).hasSize(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("web只在声明时生效：没声明拒绝；声明了也只放行 80 / 443 端口")
    void web只在声明时生效() {
        keys();
        HttpGatewayResult undeclared = gateway().execute(ctx(TOOL, grant(1L, "a", List.of(), false, 60)),
                req("GET", url("news.test", "/"), null));
        assertThat(undeclared.status()).isEqualTo(403);
        HttpGatewayResult oddPort = gateway().execute(ctx(TOOL, grant(1L, "a", List.of(), true, 60)),
                req("GET", url("news.test", "/"), null));
        assertThat(oddPort.status()).isEqualTo(403);
        assertThat(oddPort.message()).contains("80");
        HttpGatewayResult port80 = gateway().execute(ctx(TOOL, grant(1L, "a", List.of(), true, 60)),
                req("GET", "http://news.test/", null));
        assertThat(port80.gatewayError()).as("放行了，连不上也不是 denied").isNotEqualTo("denied");
    }

    @Test
    @DisplayName("靠 web 放进来的域名不享受私网白名单：地址规则拿到的 declared=false")
    void web放进来的域名不享受私网白名单() {
        keys();
        List<Boolean> declaredFlags = new CopyOnWriteArrayList<>();
        policy = (host, ip, declared) -> { declaredFlags.add(declared); return LOOPBACK_AS_PUBLIC.check(host, ip, declared); };
        gateway().execute(ctx(TOOL, grant(1L, "a", List.of(), true, 60)), req("GET", "http://news.test/", null));
        gateway().execute(ctx(TOOL, grant(1L, "a", List.of("api.customer.test"), false, 60)), req("GET", url("api.customer.test", "/"), null));
        assertThat(declaredFlags).containsExactly(false, true);
    }

    // ------------------------------------------------------------------ 第 6 步：重定向

    @Test
    @DisplayName("重定向按规则处理：没带 key 跟随；带 key 不跟、3xx 抹掉 key 返回；跳去内网或没声明的域名拒绝；最多 3 跳")
    void 重定向按规则处理() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test", "intranet.test"), false, 60);
        routes.put("/r1", ex -> redirect(ex, 302, "/r2"));
        routes.put("/r2", ex -> reply(ex, 200, "text/plain", "final"));
        routes.put("/with-key", ex -> redirect(ex, 302, "http://other.test/x?t=" + TOKEN));
        routes.put("/to-intranet", ex -> redirect(ex, 302, "http://intranet.test/"));
        routes.put("/to-undeclared", ex -> redirect(ex, 302, "http://evil.test/"));
        for (int i = 0; i < 5; i++) {
            int next = i + 1;
            routes.put("/c" + i, ex -> redirect(ex, 302, "/c" + next));
        }

        HttpGatewayResult followed = gateway().execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/r1"), null));
        assertThat(followed.status()).isEqualTo(200);
        assertThat(text(followed)).isEqualTo("final");

        seen.clear();
        HttpGatewayResult withKey = gateway().execute(ctx(TOOL, g),
                req("GET", url("api.customer.test", "/with-key"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(withKey.status()).isEqualTo(302);
        assertThat(withKey.header("location")).isEqualTo("http://other.test/x?t={{CRM_TOKEN}}");
        assertThat(seen).hasSize(1);

        assertThat(gateway().execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/to-intranet"), null)).message()).contains("内网");
        assertThat(gateway().execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/to-undeclared"), null)).message()).contains("evil.test");

        seen.clear();
        HttpGatewayResult chain = gateway().execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/c0"), null));
        assertThat(chain.status()).isEqualTo(302);
        assertThat(chain.header("location")).isEqualTo("/c4");
        assertThat(seen).hasSize(4);
    }

    @Test
    @DisplayName("POST 遇到 303 改成 GET 并丢掉请求体；换了域名就不再带 Authorization")
    void 重定向改方法和丢凭据() {
        keys();
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test", "other.test"), false, 60);
        routes.put("/submit", ex -> redirect(ex, 303, url("other.test", "/done")));
        gateway().execute(ctx(TOOL, g), req("POST", url("api.customer.test", "/submit"), "payload".getBytes(),
                "Authorization", "Bearer literal-not-a-key", "Content-Type", "text/plain"));
        Seen second = seen.get(1);
        assertThat(second.method()).isEqualTo("GET");
        assertThat(second.body()).isEmpty();
        assertThat(second.headers()).doesNotContainKey("Authorization");
    }

    // ------------------------------------------------------------------ 第 5、7 步：超时、回包

    @Test
    @DisplayName("回包里的key被抹掉：正文、响应头、各种编码形式")
    void 回包里的key被抹掉() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        routes.put("/echo", ex -> {
            ex.getResponseHeaders().add("X-Echo", TOKEN);
            reply(ex, 200, "application/json", "{\"a\":\"" + TOKEN.replace("/", "\\/") + "\",\"b\":\""
                    + Placeholders.percentEncode(TOKEN) + "\",\"c\":\"" + TOKEN + "\"}");
        });
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/echo"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(text(r)).doesNotContain("0123456789").contains("{{CRM_TOKEN}}");
        assertThat(r.header("x-echo")).isEqualTo("{{CRM_TOKEN}}");
    }

    @Test
    @DisplayName("二进制回包里的key也被抹掉（脚本的回包会进沙箱容器，不能只抹文本）")
    void 二进制回包里的key也被抹掉() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        routes.put("/bin", ex -> reply(ex, 200, "application/octet-stream", ("\0\1" + TOKEN + "\2").getBytes(StandardCharsets.UTF_8)));
        HttpGatewayResult r = gateway().execute(ctx(SCRIPT, CRM), req("GET", url("api.customer.test", "/bin"), null));
        assertThat(text(r)).isEqualTo("\0\1{{CRM_TOKEN}}\2");
    }

    @Test
    @DisplayName("截断边界上的key不会漏半截")
    void 截断边界上的key不会漏半截() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        props.setToolMaxBytes(100);
        routes.put("/edge", ex -> reply(ex, 200, "text/plain", "a".repeat(95) + TOKEN + "b".repeat(50)));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/edge"), null));
        assertThat(r.truncated()).isTrue();
        assertThat(r.body()).hasSizeLessThanOrEqualTo(100);
        assertThat(text(r)).doesNotContain(TOKEN.substring(0, 5));
        assertThat(text(r)).startsWith("a".repeat(85));
    }

    @Test
    @DisplayName("抹值让前面变短时，截断点上的半个 key 也不会留下（R29，预检 BoundaryProbe）")
    void 抹值变短后截断点上也不留半个key() {
        SkillKey k = key(1L, "crm", "CRM_TOKEN", TOKEN, null, null);
        keys(k);
        props.setToolMaxBytes(1000);
        int readLimit = 1000 + new KeyScrubber(List.of(k)).maxLength() + 1;
        // 前面回显 20 次（每次抹值都让正文变短），第 21 次的头 10 个字符正好落在读取上限之内
        String head = (TOKEN + "\n").repeat(20);
        String body = head + "x".repeat(readLimit - head.length() - 10) + TOKEN + "y".repeat(3000);
        routes.put("/echo", ex -> reply(ex, 200, "text/plain", body));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/echo"), null));
        assertThat(r.truncated()).isTrue();
        String text = text(r);
        for (int i = 0; i + 8 <= TOKEN.length(); i++) assertThat(text).doesNotContain(TOKEN.substring(i, i + 8));
    }

    @Test
    @DisplayName("key 解不开只影响引用它的请求：回 key_unavailable、客户系统收不到请求；不引用它的照常（R31）")
    void 解不开的key只影响用到它的请求() {
        com.jimeng.dataserver.ai.connection.CredentialCipher cipher = new com.jimeng.dataserver.ai.connection.CredentialCipher();
        org.springframework.test.util.ReflectionTestUtils.setField(cipher, "keyBase64", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(cipher, "init");
        com.jimeng.persistence.mapper.SkillSecretMapper mapper = mock(com.jimeng.persistence.mapper.SkillSecretMapper.class);
        List<com.jimeng.persistence.entity.SkillSecret> rows = new ArrayList<>();
        for (String name : List.of("CRM_TOKEN", "BROKEN_KEY")) {
            com.jimeng.persistence.entity.SkillSecret row = new com.jimeng.persistence.entity.SkillSecret();
            row.setSkillId(1L); row.setVersion(1); row.setName(name);
            row.setHosts("[\"api.customer.test\"]");
            row.setCipherVersion(com.jimeng.dataserver.ai.connection.CredentialCipher.CURRENT_VERSION);
            row.setCiphertext(name.equals("CRM_TOKEN") ? cipher.encrypt(TOKEN) : "not-a-ciphertext");
            rows.add(row);
        }
        when(mapper.selectList(any())).thenReturn(rows);
        HttpGateway g = gateway(new SkillSecretService(mapper, cipher));
        SkillGrant crm = new SkillGrant(1L, 1, "crm", new SkillManifest(List.of(), false, 60, null, null,
                List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.test"), null, null))));
        HttpGatewayResult broken = g.execute(ctx(TOOL, crm),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{BROKEN_KEY}}"));
        assertThat(broken.status()).isEqualTo(500);
        assertThat(broken.gatewayError()).isEqualTo("key_unavailable");
        assertThat(broken.message()).contains("BROKEN_KEY").contains("联系管理员").doesNotContain("Exception");
        assertThat(seen).isEmpty();
        HttpGatewayResult ok = g.execute(ctx(TOOL, crm),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("大小上限生效：脚本、工具各按自己的上限截断并注明")
    void 大小上限生效() {
        keys();
        props.setScriptMaxBytes(1000);
        props.setToolMaxBytes(300);
        routes.put("/big", ex -> reply(ex, 200, "text/plain", "x".repeat(5000)));
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test"), false, 60);
        HttpGatewayResult script = gateway().execute(ctx(SCRIPT, g), req("GET", url("api.customer.test", "/big"), null));
        HttpGatewayResult tool = gateway().execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/big"), null));
        assertThat(script.truncated()).isTrue();
        assertThat(script.body()).hasSize(1000);
        assertThat(tool.truncated()).isTrue();
        assertThat(tool.body()).hasSize(300);
    }

    @Test
    @DisplayName("超时生效：skill 声明 1 秒，上游 3 秒才回 → 504")
    void 超时生效() {
        keys();
        routes.put("/slow", ex -> { Thread.sleep(3000); reply(ex, 200, "text/plain", "late"); });
        long t0 = System.nanoTime();
        HttpGatewayResult r = gateway().execute(ctx(TOOL, grant(1L, "crm", List.of("api.customer.test"), false, 1)),
                req("GET", url("api.customer.test", "/slow"), null));
        assertThat(r.status()).isEqualTo(504);
        assertThat(r.gatewayError()).isEqualTo("timeout");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2500);
    }

    // ------------------------------------------------------------------ 第 8 步：日志

    @Test
    @DisplayName("日志里不出现key真值：成功、连不上、请求头不合法（OkHttp 的异常文案会带出头的值）三种情况")
    void 日志里不出现key真值() throws Exception {
        String unicodeKey = "密钥-abcdefgh-1234";
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null), key(1L, "crm", "UNI_KEY", unicodeKey, null, null));
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) { closedPort = s.getLocalPort(); }
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        // 测试环境的日志级别不一定是 INFO：审计 logger 显式调到 INFO，事件经 additivity 照样到根上的 appender
        Logger audit = (Logger) LoggerFactory.getLogger("jm.http-gateway.audit");
        ch.qos.logback.classic.Level oldLevel = audit.getLevel();
        audit.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        List<HttpGatewayResult> results = new ArrayList<>();
        try {
            results.add(gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/?t={{CRM_TOKEN}}"), null, "X-Key", "{{CRM_TOKEN}}")));
            // 连接失败：key 只允许发往 api.customer.test，所以连它一个没人监听的端口（换成别的域名，第 2 步就被拒了，测不到连接失败）
            results.add(gateway().execute(ctx(TOOL, CRM), req("GET", "http://api.customer.test:" + closedPort + "/", null, "X-Key", "{{CRM_TOKEN}}")));
            results.add(gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/"), null, "X-Uni", "{{UNI_KEY}}")));
        } finally {
            root.detachAppender(appender);
            audit.setLevel(oldLevel);
        }
        assertThat(results.get(1).gatewayError()).isEqualTo("upstream_error");
        assertThat(results.get(2).gatewayError()).isEqualTo("bad_request");
        for (HttpGatewayResult r : results) {
            assertThat(String.valueOf(r.message())).doesNotContain("0123456789").doesNotContain("abcdefgh");
        }
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent e : appender.list) {
            String all = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
            assertThat(all).doesNotContain("0123456789").doesNotContain("abcdefgh");
        }
        assertThat(appender.list).anyMatch(e -> e.getLoggerName().equals("jm.http-gateway.audit")
                && e.getFormattedMessage().contains("hgw_url=") && e.getFormattedMessage().contains("{{CRM_TOKEN}}"));
    }
    @Test
    void nullInputsAndLoadFailureNeverThrowOrLogRequestValues() {
        assertThat(gateway().execute(null, null).gatewayError()).isEqualTo("bad_request");
        assertThat(gateway().execute(ctx(TOOL, CRM), null).gatewayError()).isEqualTo("bad_request");
        when(secrets.loadKeys(any())).thenThrow(new IllegalStateException("private-ciphertext"));
        Logger audit = (Logger)LoggerFactory.getLogger("jm.http-gateway.audit");
        ch.qos.logback.classic.Level old = audit.getLevel();
        audit.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); audit.addAppender(appender);
        try {
            HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/?t=" + TOKEN), null));
            assertThat(r.gatewayError()).isEqualTo("key_unavailable");
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain("private-ciphertext"));
        } finally { audit.detachAppender(appender); audit.setLevel(old); }
    }

    @Test
    void literalValuesInDeniedPathsAndEncodedHeadersAreScrubbed() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, List.of("POST"), null));
        HttpGatewayResult denied = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/" + TOKEN), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(denied.message()).doesNotContain("0123456789");
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null));
        assertThat(gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/"), null, "X-Key", "%7b%7bCRM_TOKEN%7d%7d")).status()).isEqualTo(200);
        assertThat(seen.get(0).headers().get("X-key")).containsExactly(TOKEN);
    }
    @Test
    void unavailableSameNameInScopeIsKeyUnavailable() {
        keys(key(1L, "crm", "CRM_TOKEN", TOKEN, null, null), key(2L, "crm-second", "CRM_TOKEN", null, null, null));
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM, grant(2L, "crm-second", List.of(), false, 60)),
                req("GET", url("api.customer.test", "/"), null, "X-Key", "{{CRM_TOKEN}}"));
        assertThat(r.gatewayError()).isEqualTo("key_unavailable");
        assertThat(r.status()).isEqualTo(500);
        assertThat(seen).isEmpty();
    }

    @Test
    void finalCredentialPathMustStayWithinScope() {
        for (String value : List.of("abcdefgh/../../admin", "abcdefgh")) {
            keys(key(1L, "crm", "TOKEN", value, null, List.of(value.contains("/") ? "/v1/orders/*" : "/v1/orders/%7B%7BTOKEN%7D%7D")));
            HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/v1/orders/{{TOKEN}}"), null));
            assertThat(r.status()).isEqualTo(403);
            assertThat(seen).isEmpty();
        }
    }

    @Test
    void secretBearingResponseHeaderNamesAreOmitted() {
        keys(key(1L, "crm", "TOKEN", "PRIVATE-SECRET-123", null, null));
        routes.put("/headers", ex -> { ex.getResponseHeaders().add("x-private-secret-123", "ok"); ex.getResponseHeaders().add("X-Safe", "ok"); reply(ex, 200, "text/plain", "ok"); });
        HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/headers"), null));
        assertThat(r.headers()).noneMatch(h -> h.getKey().contains("private-secret-123"));
        assertThat(r.header("x-safe")).isEqualTo("ok");
    }

    @Test
    void pendingDnsAndRedirectDnsShareTotalDeadline() throws Exception {
        keys();
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test"), false, 1);
        GatewayResolver stalled = host -> { try { Thread.sleep(1600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return List.of(InetAddress.getByName("127.0.0.1")); };
        long start = System.nanoTime();
        HttpGatewayResult r = new HttpGateway(props, secrets, stalled, policy).execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/"), null));
        assertThat(r.gatewayError()).isEqualTo("timeout");
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(1400);
        assertThat(seen).isEmpty();
        AtomicInteger calls = new AtomicInteger();
        GatewayResolver redirected = host -> { try { Thread.sleep(calls.getAndIncrement() == 0 ? 400 : 1600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return List.of(InetAddress.getByName("127.0.0.1")); };
        routes.put("/dns-next", ex -> redirect(ex, 302, "/done"));
        start = System.nanoTime();
        r = new HttpGateway(props, secrets, redirected, policy).execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/dns-next"), null));
        assertThat(r.gatewayError()).isEqualTo("timeout");
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(1400);
        assertThat(seen).hasSize(1);
    }

    @Test
    void strictScrubbingCoversBodyHeaderErrorsAndAudit() {
        String overlap = "abcdefgh123456789";
        String mixed = "token%2Fwith%2bchars123";
        keys(key(1L, "crm", "FIRST_KEY", "abcdefgh", null, null), key(1L, "crm", "OTHER_KEY", "bcdefgh123456789", null, null), key(1L, "crm", "TOKEN", "token/with+chars123", null, null));
        routes.put("/strict", ex -> { ex.getResponseHeaders().add("X-Echo", overlap + " " + mixed); reply(ex, 200, "text/plain", overlap + " " + mixed); });
        Logger audit = (Logger) LoggerFactory.getLogger("jm.http-gateway.audit");
        ch.qos.logback.classic.Level old = audit.getLevel(); audit.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); audit.addAppender(appender);
        try {
            HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/strict?t=" + overlap + "&m=" + mixed), null));
            assertThat(text(r)).doesNotContain("123456789").doesNotContain(mixed);
            assertThat(r.header("x-echo")).doesNotContain("123456789").doesNotContain(mixed);
            HttpGatewayResult error = gateway().execute(ctx(TOOL, CRM), req(overlap + " " + mixed, url("api.customer.test", "/strict"), null));
            assertThat(error.message()).doesNotContain("123456789").doesNotContain(mixed);
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("123456789").doesNotContain(mixed));
        } finally { audit.detachAppender(appender); audit.setLevel(old); }
    }

    @Test
    void expandedRequestBodyAndOrdinaryUploadsRespectPlaneCaps() {
        keys(key(1L, "crm", "TOKEN", "a".repeat(8192), null, null));
        HttpGatewayResult large = gateway().execute(ctx(TOOL, CRM), req("POST", url("api.customer.test", "/"), "{{TOKEN}}".repeat(1024).getBytes(StandardCharsets.UTF_8)));
        assertThat(large.gatewayError()).isEqualTo("bad_request");
        assertThat(seen).isEmpty();
        keys();
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test"), false, 60);
        props.setToolRequestMaxBytes(8); props.setScriptRequestMaxBytes(16);
        assertThat(gateway().execute(ctx(TOOL, g), req("POST", url("api.customer.test", "/"), new byte[9])).gatewayError()).isEqualTo("bad_request");
        assertThat(gateway().execute(ctx(SCRIPT, g), req("POST", url("api.customer.test", "/"), new byte[17])).gatewayError()).isEqualTo("bad_request");
        assertThat(seen).isEmpty();
        assertThat(gateway().execute(ctx(TOOL, g), req("POST", url("api.customer.test", "/"), new byte[8])).status()).isEqualTo(200);
        assertThat(gateway().execute(ctx(SCRIPT, g), req("POST", url("api.customer.test", "/"), new byte[16])).status()).isEqualTo(200);
    }

    @Test
    void upstreamFailureUsesPublicAccessWording() throws Exception {
        keys();
        int closedPort; try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        HttpGatewayResult r = gateway().execute(ctx(TOOL, grant(1L, "crm", List.of("api.customer.test"), false, 60)), req("GET", "http://api.customer.test:"+closedPort+"/", null));
        assertThat(r.message()).contains("访问").doesNotContain("连接");
    }

    @Test
    void stalledDnsHasBoundedCapacityAndTimeoutQueueDoesNotAccumulate() throws Exception {
        keys();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        GatewayResolver stuck = host -> {
            entered.incrementAndGet();
            boolean done = false;
            while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) {} }
            return List.of(InetAddress.getByName("127.0.0.1"));
        };
        HttpGateway gateway = new HttpGateway(props, secrets, stuck, policy);
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test"), false, 1);
        java.util.concurrent.ExecutorService callers = Executors.newFixedThreadPool(24);
        List<java.util.concurrent.Future<HttpGatewayResult>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) pending.add(callers.submit(() -> gateway.execute(ctx(TOOL, g), req("GET", url("api.customer.test", "/"), null))));
            List<HttpGatewayResult> results = new ArrayList<>();
            for (var future : pending) results.add(future.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(entered.get()).isLessThanOrEqualTo(4);
            assertThat(results).allMatch(r -> "timeout".equals(r.gatewayError()) || "upstream_error".equals(r.gatewayError()));
            assertThat(results).anyMatch(r -> "upstream_error".equals(r.gatewayError()));
            assertThat(seen).isEmpty();
        } finally { release.countDown(); callers.shutdownNow(); callers.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); }
        // 超时排队任务已移除，恢复的 resolver 能服务下一次调用。
        assertThat(gateway().execute(ctx(TOOL, grant(1L, "crm", List.of("api.customer.test"), false, 60)), req("GET", url("api.customer.test", "/"), null)).status()).isEqualTo(200);
    }

    @Test
    void emptyEnvelopeBodyWorksForGetHeadAndRequiredBodyMethods() {
        keys();
        SkillGrant g = grant(1L, "crm", List.of("api.customer.test"), false, 60);
        routes.put("/empty", ex -> { if (ex.getRequestMethod().equals("HEAD")) { ex.sendResponseHeaders(200, -1); ex.close(); } else reply(ex, 200, "text/plain", "ok"); });
        for (String method : List.of("GET", "HEAD", "POST", "PUT", "PATCH")) {
            assertThat(gateway().execute(ctx(SCRIPT, g), req(method, url("api.customer.test", "/empty"), new byte[0])).status()).as(method).isEqualTo(200);
        }
        assertThat(seen).hasSize(5).allSatisfy(request -> assertThat(request.body()).isEmpty());
    }

    @Test
    void adjacentPercentCannotExposeEncodedCredentialInGatewayOutputs() {
        keys(key(1L, "crm", "TOKEN", "AB/secret123", null, null));
        String echo = "%AB%2Fsecret123,%AB%2fsecret123";
        routes.put("/boundary", ex -> { ex.getResponseHeaders().add("X-Echo", echo); reply(ex, 200, "text/plain", echo); });
        Logger audit = (Logger)LoggerFactory.getLogger("jm.http-gateway.audit");
        ch.qos.logback.classic.Level old = audit.getLevel(); audit.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); audit.addAppender(appender);
        try {
            HttpGatewayResult r = gateway().execute(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/boundary?q=" + echo), null));
            assertThat(r.status()).isEqualTo(200);
            assertThat(text(r)).isEqualTo("%{{TOKEN}},%{{TOKEN}}");
            assertThat(r.header("x-echo")).isEqualTo("%{{TOKEN}},%{{TOKEN}}");
            HttpGatewayResult error = gateway().execute(ctx(TOOL, CRM), req(echo, url("api.customer.test", "/"), null));
            assertThat(error.message()).doesNotContain("secret123");
            assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("secret123"));
        } finally { audit.detachAppender(appender); audit.setLevel(old); }
    }

    @Test
    void htmlEntityDecodingMustBeScrubbedBeforeModelVisibleText() {
        String token = "a&b12345678";
        keys(key(1, "crm", "TOKEN", token, null, null));
        routes.put("/html", ex -> reply(ex, 200, "text/html", "<p>a&amp;b12345678</p>"));
        HttpGatewayResult r = gateway().executeTool(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/html"), null), true);
        Map<String, Object> payload = com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100);
        assertThat(payload).containsEntry("body", "{{TOKEN}}");
        assertThat(payload.toString()).doesNotContain(token);
    }

    @Test
    void asTextKeepsNulContainingUnknownBinaryResponseBinary() {
        keys();
        byte[] binary = "<html><p>bytes\0</p></html>".getBytes(StandardCharsets.UTF_8);
        routes.put("/binary-html", ex -> reply(ex, 200, "application/octet-stream", binary));
        HttpGatewayResult r = gateway().executeTool(ctx(TOOL, grant(1, "reader", List.of("api.customer.test"), false, 60)),
                req("GET", url("api.customer.test", "/binary-html"), null), true);
        assertThat(r.body()).isEqualTo(binary);
        assertThat(com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100))
                .doesNotContainKey("body").containsEntry("binary", Map.of("content_type", "application/octet-stream", "bytes", binary.length));
    }

    @Test
    void toolTransformationPreservesOriginalByteCapAndProtectsTruncatedSecretTail() {
        String token = "a&b12345678";
        keys(key(1, "crm", "TOKEN", token, null, null));
        props.setToolMaxBytes(80);
        routes.put("/clipped-html", ex -> reply(ex, 200, "text/html", "<p>" + "x".repeat(60) + "a&amp;b12345678" + "z".repeat(120)));
        HttpGatewayResult r = gateway().executeTool(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/clipped-html"), null), true);
        assertThat(r.truncated()).isTrue();
        assertThat(r.body().length).isLessThanOrEqualTo(80);
        Map<String, Object> payload = com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100);
        assertThat(payload).containsEntry("truncated", true).containsKey("note");
        assertThat(payload.get("body").toString()).doesNotContain(token).doesNotContain("a&b123");
    }

    @Test
    void rawPathAndSingleHtmlDecodingRemainDistinctAndCharacterClipFollowsScrubbing() {
        String token = "a&b12345678";
        keys(key(1, "crm", "TOKEN", token, null, null));
        String html = "<html><p>ok a&amp;b12345678 a&amp;amp;b12345678</p></html>";
        routes.put("/single-html", ex -> reply(ex, 200, "application/octet-stream", html));
        HttpGateway g = gateway();
        HttpGatewayRequest request = req("GET", url("api.customer.test", "/single-html"), null);
        assertThat(text(g.execute(ctx(SCRIPT, CRM), request))).isEqualTo(html);
        assertThat(text(g.executeTool(ctx(TOOL, CRM), request, false))).isEqualTo(html);
        HttpGatewayResult converted = g.executeTool(ctx(TOOL, CRM), request, true);
        assertThat(text(converted)).isEqualTo("ok {{TOKEN}} a&amp;b12345678");
        Map<String, Object> payload = com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(converted, false, 14);
        assertThat(payload).containsEntry("body", "ok {{TOKEN}} a").containsEntry("truncated", true).containsKey("note");
        assertThat(payload.toString()).doesNotContain(token);
    }

    @Test
    void toolUtf8ReplacementMustBeScrubbedEvenWithoutHtmlConversion() {
        String token = "token-\uFFFD-12345678";
        keys(key(1, "crm", "TOKEN", token, null, null));
        byte[] raw = new byte[]{'t', 'o', 'k', 'e', 'n', '-', (byte) 0xff, '-', '1', '2', '3', '4', '5', '6', '7', '8'};
        routes.put("/malformed-text", ex -> reply(ex, 200, "text/plain", raw));
        HttpGateway g = gateway();
        HttpGatewayRequest request = req("GET", url("api.customer.test", "/malformed-text"), null);
        assertThat(g.execute(ctx(SCRIPT, CRM), request).body()).isEqualTo(raw);
        HttpGatewayResult r = g.executeTool(ctx(TOOL, CRM), request, false);
        Map<String, Object> payload = com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100);
        assertThat(payload).containsEntry("body", "{{TOKEN}}");
        assertThat(payload.toString()).doesNotContain(token);
    }

    @Test
    void originalByteTruncationIsReportedEvenWhenHtmlConversionShrinksBody() {
        keys();
        props.setToolMaxBytes(80);
        routes.put("/shrunk-html", ex -> reply(ex, 200, "text/html", "<html><p>ok</p>" + " ".repeat(200)));
        HttpGatewayResult r = gateway().executeTool(ctx(TOOL, grant(1, "reader", List.of("api.customer.test"), false, 60)),
                req("GET", url("api.customer.test", "/shrunk-html"), null), true);
        assertThat(text(r)).isEqualTo("ok");
        assertThat(r.truncated()).isTrue();
        assertThat(com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100))
                .containsEntry("body", "ok").containsEntry("truncated", true).containsKey("note");
    }

    @Test
    void toolByteClippingMustNotCreateUtf8ReplacementSecret() {
        String token = "token-x-\uFFFD";
        keys(key(1, "crm", "TOKEN", token, null, null));
        props.setToolMaxBytes(10);
        routes.put("/unicode-tail", ex -> reply(ex, 200, "text/plain", "token-x-中" + "z".repeat(100)));
        HttpGatewayResult r = gateway().executeTool(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/unicode-tail"), null), false);
        Map<String, Object> payload = com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(r, false, 100);
        assertThat(payload).containsEntry("body", "token-x-").containsEntry("truncated", true);
        assertThat(payload.toString()).doesNotContain(token);
        // 转换缩短正文，截点由去掉 maxLength 尾部决定，而不是最终 cap 决定。
        props.setToolMaxBytes(80);
        routes.put("/unicode-tail-html", ex -> reply(ex, 200, "text/html", "<p>token-x-中" + "z".repeat(16) + "</p>" + " ".repeat(200)));
        HttpGatewayResult tail = gateway().executeTool(ctx(TOOL, CRM), req("GET", url("api.customer.test", "/unicode-tail-html"), null), true);
        assertThat(com.jimeng.dataserver.ai.http.tool.HttpToolResults.toModelPayload(tail, false, 100))
                .containsEntry("body", "token-x-").containsEntry("truncated", true);
    }

}

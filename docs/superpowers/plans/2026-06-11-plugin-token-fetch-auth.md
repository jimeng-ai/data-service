# 插件「服务端换 token + 缓存」鉴权 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `OAUTH2`（client_credentials）与 `TOKEN_FETCH`（通用登录换 token）两种插件鉴权，服务端自动换 token、缓存、过期/401 兜底，对 LLM 透明。

**Architecture:** 共享 `PluginTokenProvider`（取/缓存/分布式锁/抽取）+ 两个薄 applier（OAuth2 / Generic）+ `TokenCachingAuthApplier` 子接口；`PluginHttpInvoker` 用 `instanceof` 分派并在业务接口 401 时重取重试一次。现有 4 个 applier 不改。详见 `docs/superpowers/specs/2026-06-10-plugin-oauth-token-auth-design.md`。

**Tech Stack:** Java 17 / Spring Boot / OkHttp 4.9.3 / Redisson / Jackson / JUnit5 + Mockito + okhttp MockWebServer（后端）；React 18 + TS + AntD 5（前端 `jm-agent-front`）。

**测试命令约定（后端）：** 在 `/Users/jerry/Desktop/jm/data-service` 下
`mvn -q -pl modules/data-server test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`
（纯单元测试，不加载 Spring/DB）。
⚠️ 不要加 `-am`：会把 `test` 目标也跑到依赖模块（common-identifier 等），它们没有匹配的测试 →
reactor 在依赖模块就 `No tests matching pattern` 失败、根本到不了 data-server。依赖的 SNAPSHOT
已在 `.m2`（之前 `mvn clean install` 装过），无需 `-am`。下文各 Task 的 `-am` 命令同此修正。

---

## 文件结构

**后端（`data-service/modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/`）**
- Create `util/JsonPathUtil.java` — 从 `PluginResponseExtractor` 抽出的 JSONPath 子集静态工具
- Modify `service/PluginResponseExtractor.java` — 改用 `JsonPathUtil`
- Modify `dto/PluginError.java` — 加 `CODE_TOKEN_FETCH_FAILED`
- Create `dto/TokenFetchSpec.java` — token 获取规格 DTO
- Create `service/PluginTokenProvider.java` — token 取/缓存/锁/抽取，含 `TokenFetchException`
- Create `auth/TokenCachingAuthApplier.java` — 子接口
- Create `auth/OAuth2ClientCredentialsAuthApplier.java`
- Create `auth/GenericTokenAuthApplier.java`
- Modify `service/PluginHttpInvoker.java` — instanceof 分派 + 401 重试
- Modify `modules/data-server/pom.xml` — 加 test 依赖 mockwebserver

**后端测试（`.../src/test/java/com/jimeng/dataserver/ai/plugin/`）**
- Create `util/JsonPathUtilTest.java`
- Create `PluginTokenProviderTest.java`
- Create `OAuth2ClientCredentialsAuthApplierTest.java`
- Create `GenericTokenAuthApplierTest.java`
- Create `PluginHttpInvokerTokenRetryTest.java`

**前端（`jm-agent-front/src/`）**
- Modify `api/types.ts` — `PluginAuthType` 加两值
- Modify `pages/console/plugin/PluginEditorPage.tsx` — 下拉两项 + auth_config 条件
- Modify `features/plugin/components/CredentialPanel.tsx` — OAUTH2 固定字段 + TOKEN_FETCH 自由 key-value

---

## Task 1: 抽 `JsonPathUtil`（重构，行为不变）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/util/JsonPathUtil.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginResponseExtractor.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/util/JsonPathUtilTest.java`

- [ ] **Step 1: 写失败测试**

```java
// JsonPathUtilTest.java
package com.jimeng.dataserver.ai.plugin.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonPathUtilTest {
    private JsonNode parse(String s) throws Exception {
        return CommonUtil.getObjectMapper().readTree(s);
    }

    @Test
    void nestedObjectPath() throws Exception {
        JsonNode root = parse("{\"data\":{\"token\":\"abc\"}}");
        assertEquals("abc", JsonPathUtil.apply(root, "$.data.token").asText());
    }

    @Test
    void arrayIndexAndWildcard() throws Exception {
        JsonNode root = parse("{\"items\":[{\"name\":\"A\"},{\"name\":\"B\"}]}");
        assertEquals("A", JsonPathUtil.apply(root, "$.items[0].name").asText());
        assertEquals(2, JsonPathUtil.apply(root, "$.items[*].name").size());
    }

    @Test
    void dollarOrEmptyReturnsRoot() throws Exception {
        JsonNode root = parse("{\"a\":1}");
        assertTrue(JsonPathUtil.apply(root, "$").has("a"));
        assertTrue(JsonPathUtil.apply(root, "").has("a"));
    }

    @Test
    void missingReturnsNull() throws Exception {
        JsonNode root = parse("{\"a\":1}");
        assertNull(JsonPathUtil.apply(root, "$.b.c"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl modules/data-server -am -Dtest=JsonPathUtilTest test`
Expected: 编译失败 `cannot find symbol: JsonPathUtil`

- [ ] **Step 3: 实现 `JsonPathUtil`（搬运 `PluginResponseExtractor` 现有逻辑）**

把 `PluginResponseExtractor` 的 `applyPath` / `applySegments` / `splitSegments` 原样搬进静态工具，`applyPath` 改名 `apply` 公开：

```java
package com.jimeng.dataserver.ai.plugin.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jimeng.common.core.utils.CommonUtil;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONPath 子集求值工具（从 PluginResponseExtractor 抽出，供响应抽取与 token 抽取共用）。
 * 支持：$ / 留空 → 全文；$.a.b.c → 嵌套；$.a[0] → 下标；$.a[*] → 数组通配（剩余路径逐项映射）。
 * 复杂表达式（过滤/切片）不在范围。
 */
public final class JsonPathUtil {

    private JsonPathUtil() {}

    public static JsonNode apply(JsonNode root, String path) {
        if (!StringUtils.hasText(path) || "$".equals(path.trim())) {
            return root;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) normalized = normalized.substring(2);
        else if (normalized.startsWith("$")) normalized = normalized.substring(1);
        return applySegments(root, splitSegments(normalized), 0);
    }

    private static JsonNode applySegments(JsonNode node, List<String> segments, int idx) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (idx >= segments.size()) return node;

        String segment = segments.get(idx);
        String key = segment;
        Integer index = null;
        boolean wildcard = false;

        int bracket = segment.indexOf('[');
        if (bracket >= 0) {
            key = segment.substring(0, bracket);
            int end = segment.indexOf(']', bracket);
            if (end < 0) throw new IllegalArgumentException("非法 JSONPath: " + segment);
            String idxStr = segment.substring(bracket + 1, end).trim();
            if ("*".equals(idxStr)) wildcard = true;
            else {
                try { index = Integer.parseInt(idxStr); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("JSONPath 索引非法: " + idxStr); }
            }
        }

        JsonNode current = node;
        if (!key.isEmpty()) {
            current = current.get(key);
            if (current == null) return null;
        }

        if (wildcard) {
            if (current == null || !current.isArray()) return null;
            ArrayNode out = CommonUtil.getObjectMapper().createArrayNode();
            for (JsonNode el : current) {
                JsonNode v = applySegments(el, segments, idx + 1);
                if (v != null && !v.isMissingNode() && !v.isNull()) out.add(v);
            }
            return out;
        }
        if (index != null) current = current.isArray() ? current.get(index) : null;
        return applySegments(current, segments, idx + 1);
    }

    private static List<String> splitSegments(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) { segments.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) segments.add(cur.toString());
        return segments;
    }
}
```

- [ ] **Step 4: 改 `PluginResponseExtractor` 用 `JsonPathUtil.apply`**

删除其私有 `applyPath`/`applySegments`/`splitSegments`，把两处调用 `applyPath(root, x)` 改为 `JsonPathUtil.apply(root, x)`，加 `import ...util.JsonPathUtil;`。删掉因此不再用到的 `ArrayNode`/`ArrayList`/`List` import（若仅这些方法用到）。

- [ ] **Step 5: 跑测试确认通过 + 回归**

Run: `mvn -q -pl modules/data-server -am -Dtest=JsonPathUtilTest,PluginResponseExtractorTest test`
Expected: PASS（新工具 + 原抽取器回归都绿）

- [ ] **Step 6: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/util/JsonPathUtil.java \
        modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginResponseExtractor.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/util/JsonPathUtilTest.java
git commit -m "refactor(plugin): 抽 JsonPathUtil，响应抽取器复用，为 token 抽取铺路"
```

---

## Task 2: `PluginError` 新增 token 错误码

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/dto/PluginError.java`

- [ ] **Step 1: 加常量**

在 `CODE_AUTH_FAILED` 附近加：

```java
    public static final String CODE_TOKEN_FETCH_FAILED = "TOKEN_FETCH_FAILED";
```

- [ ] **Step 2: 编译确认**

Run: `mvn -q -pl modules/data-server -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/dto/PluginError.java
git commit -m "feat(plugin): 新增 TOKEN_FETCH_FAILED 错误码"
```

---

## Task 3: `TokenFetchSpec` DTO

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/dto/TokenFetchSpec.java`

- [ ] **Step 1: 实现 DTO（纯数据，Lombok）**

```java
package com.jimeng.dataserver.ai.plugin.dto;

import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次 token 获取的规格：OAuth2 applier 拼固定值，Generic applier 从 auth_config 解析。 */
@Data
public class TokenFetchSpec {
    /** token 请求 */
    private String method = "POST";
    private String url;
    private String contentType = "application/json";
    private Map<String, String> headers = new LinkedHashMap<>();
    /** body 模板（含 {{secrets.x}}）；form 模式下是 a=b&c=d 串，json 模式下是 JSON 串 */
    private String bodyTemplate;

    /** 取值 */
    private String tokenPath = "$.access_token";
    private String expirePath;            // 可空
    private String expireUnit = "sec";    // sec | ms
    private long defaultTtlSec = 3600;
    private long safetyMarginSec = 60;

    /** 注入 */
    private String injectLocation = "header"; // header | query
    private String injectName = "Authorization";
    private String injectPrefix = "Bearer ";
}
```

- [ ] **Step 2: 编译确认**

Run: `mvn -q -pl modules/data-server -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/dto/TokenFetchSpec.java
git commit -m "feat(plugin): 新增 TokenFetchSpec 描述 token 获取规格"
```

---

## Task 4: 加 mockwebserver 测试依赖

**Files:**
- Modify: `modules/data-server/pom.xml`

- [ ] **Step 1: 在 `<dependencies>` 加（紧挨 `spring-boot-starter-test`）**

```xml
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <version>4.9.3</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 确认能解析依赖**

Run: `mvn -q -pl modules/data-server -am -DskipTests test-compile`
Expected: BUILD SUCCESS（无依赖解析报错）

- [ ] **Step 3: 提交**

```bash
git add modules/data-server/pom.xml
git commit -m "test(plugin): 引入 okhttp mockwebserver 测试依赖"
```

---

## Task 5: `PluginTokenProvider`（核心：取/缓存/锁/抽取）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginTokenProvider.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/PluginTokenProviderTest.java`

**说明：** `resolveToken` 用 `RedissonClient` 的 `RBucket`（缓存）+ `RLock`（防并发）；测试用 Mockito mock `RedissonClient`/`RBucket`/`RLock`，用 MockWebServer 当 token 接口，`PluginTemplateRenderer` 与 `JsonPathUtil` 用真实对象。

- [ ] **Step 1: 写失败测试（缓存命中、fetch+写缓存+TTL、抽取失败、invalidate）**

```java
package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import com.jimeng.dataserver.ai.plugin.service.PluginTemplateRenderer;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginTokenProviderTest {

    private MockWebServer server;
    private PluginTokenProvider provider;
    private RedissonClient redisson;
    private RBucket<String> bucket;
    private RLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        lock = mock(RLock.class);
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        provider = new PluginTokenProvider(new OkHttpClient(), new PluginTemplateRenderer(), redisson);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    private PluginExecutionContext ctx(Map<String,Object> secrets) {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(), secrets,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private TokenFetchSpec genericSpec() {
        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(server.url("/login").toString());
        s.setContentType("application/json");
        s.getHeaders().put("Content-Type", "application/json");
        s.setBodyTemplate("{\"appKey\":\"{{secrets.appKey}}\"}");
        s.setTokenPath("$.data.token");
        s.setExpirePath("$.data.expire");
        return s;
    }

    @Test
    void cacheHit_returnsWithoutFetch() {
        when(bucket.get()).thenReturn("cached-tok");
        String tok = provider.resolveToken(genericSpec(), ctx(Map.of("appKey","k")), "ck");
        assertEquals("cached-tok", tok);
        assertEquals(0, server.getRequestCount()); // 没打 token 接口
    }

    @Test
    void miss_fetchesExtractsAndCachesWithTtl() throws Exception {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse()
                .setBody("{\"data\":{\"token\":\"fresh-tok\",\"expire\":7200}}")
                .addHeader("Content-Type","application/json"));

        String tok = provider.resolveToken(genericSpec(), ctx(Map.of("appKey","KK")), "ck");

        assertEquals("fresh-tok", tok);
        RecordedRequest rr = server.takeRequest();
        assertEquals("POST", rr.getMethod());
        assertTrue(rr.getBody().readUtf8().contains("\"appKey\":\"KK\"")); // 模板渲染了 secrets
        // TTL = 7200 - 60
        verify(bucket).set(eq("fresh-tok"), eq(7140L), eq(TimeUnit.SECONDS));
    }

    @Test
    void miss_noExpirePath_usesDefaultTtl() {
        when(bucket.get()).thenReturn(null);
        TokenFetchSpec s = genericSpec();
        s.setExpirePath(null);
        s.setDefaultTtlSec(1800);
        server.enqueue(new MockResponse().setBody("{\"data\":{\"token\":\"t\"}}"));
        provider.resolveToken(s, ctx(Map.of("appKey","k")), "ck");
        verify(bucket).set(eq("t"), eq(1800L), eq(TimeUnit.SECONDS));
    }

    @Test
    void tokenPathMissing_throws() {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse().setBody("{\"data\":{}}"));
        assertThrows(PluginTokenProvider.TokenFetchException.class,
                () -> provider.resolveToken(genericSpec(), ctx(Map.of("appKey","k")), "ck"));
    }

    @Test
    void tokenEndpoint4xx_throws() {
        when(bucket.get()).thenReturn(null);
        server.enqueue(new MockResponse().setResponseCode(401).setBody("nope"));
        assertThrows(PluginTokenProvider.TokenFetchException.class,
                () -> provider.resolveToken(genericSpec(), ctx(Map.of("appKey","k")), "ck"));
    }

    @Test
    void invalidate_deletesBucket() {
        provider.invalidate("ck");
        verify(bucket).delete();
    }

    @Test
    void cacheKey_changesWithAuthConfigOrSecrets() {
        String k1 = provider.cacheKey("t1", 1L, Map.of("u","a"), Map.of("s","x"));
        String k2 = provider.cacheKey("t1", 1L, Map.of("u","a"), Map.of("s","y"));
        String k3 = provider.cacheKey("t1", 1L, Map.of("u","b"), Map.of("s","x"));
        assertNotEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertTrue(k1.startsWith("plugin:auth:token:t1:1:"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl modules/data-server -am -Dtest=PluginTokenProviderTest test`
Expected: 编译失败 `cannot find symbol: PluginTokenProvider`

- [ ] **Step 3: 实现 `PluginTokenProvider`**

```java
package com.jimeng.dataserver.ai.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.util.JsonPathUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** token 获取 + 缓存（Redisson RBucket）+ 防并发（RLock）+ JSONPath 抽取。 */
@Slf4j
@Service
public class PluginTokenProvider {

    public static class TokenFetchException extends RuntimeException {
        public TokenFetchException(String message) { super(message); }
    }

    private static final long LOCK_WAIT_SEC = 10;
    private static final long LOCK_LEASE_SEC = 30;
    private static final long MIN_TTL_SEC = 5;

    private final OkHttpClient httpClient;
    private final PluginTemplateRenderer renderer;
    private final RedissonClient redisson;

    @Autowired
    public PluginTokenProvider(@Qualifier("pluginHttpClient") OkHttpClient httpClient,
                               PluginTemplateRenderer renderer,
                               RedissonClient redisson) {
        this.httpClient = httpClient;
        this.renderer = renderer;
        this.redisson = redisson;
    }

    public String cacheKey(String tenantId, Long pluginId,
                           Map<String, Object> authConfig, Map<String, Object> secrets) {
        String material = canonical(authConfig) + "|" + canonical(secrets);
        return "plugin:auth:token:" + tenantId + ":" + pluginId + ":" + sha8(material);
    }

    public void invalidate(String cacheKey) {
        redisson.getBucket(cacheKey).delete();
    }

    public String resolveToken(TokenFetchSpec spec, PluginExecutionContext ctx, String cacheKey) {
        RBucket<String> bucket = redisson.getBucket(cacheKey);
        String cached = bucket.get();
        if (StringUtils.hasText(cached)) return cached;

        RLock lock = redisson.getLock(cacheKey + ":lock");
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(LOCK_WAIT_SEC, LOCK_LEASE_SEC, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (locked) {
                String again = bucket.get();           // 双重检查
                if (StringUtils.hasText(again)) return again;
            }
            // locked=false（拥塞）也降级直接 fetch，宁可多打一次也不阻塞业务
            return fetchAndCache(spec, ctx, bucket);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private String fetchAndCache(TokenFetchSpec spec, PluginExecutionContext ctx, RBucket<String> bucket) {
        String url = renderer.renderString(spec.getUrl(), ctx);
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : spec.getHeaders().entrySet()) {
            rb.addHeader(e.getKey(), renderer.renderString(e.getValue(), ctx));
        }
        String method = spec.getMethod() == null ? "POST" : spec.getMethod().toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            rb.method(method, null);
        } else {
            String body = spec.getBodyTemplate() == null ? "" : renderer.renderString(spec.getBodyTemplate(), ctx);
            rb.method(method, RequestBody.create(body, MediaType.parse(spec.getContentType())));
        }

        String raw;
        int status;
        try (Response resp = httpClient.newCall(rb.build()).execute()) {
            status = resp.code();
            raw = resp.body() == null ? "" : resp.body().string();
        } catch (Exception e) {
            throw new TokenFetchException("token 接口请求失败: " + e.getMessage());
        }
        if (status >= 400) {
            throw new TokenFetchException("token 接口 HTTP " + status + ": " + truncate(raw, 300));
        }

        JsonNode root;
        try {
            root = CommonUtil.getObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new TokenFetchException("token 响应非 JSON: " + truncate(raw, 300));
        }
        JsonNode tokNode = JsonPathUtil.apply(root, spec.getTokenPath());
        if (tokNode == null || !tokNode.isValueNode() || !StringUtils.hasText(tokNode.asText())) {
            throw new TokenFetchException("token 提取失败, path=" + spec.getTokenPath());
        }
        String token = tokNode.asText();

        long ttl = computeTtl(root, spec);
        bucket.set(token, ttl, TimeUnit.SECONDS);
        return token;
    }

    private long computeTtl(JsonNode root, TokenFetchSpec spec) {
        if (StringUtils.hasText(spec.getExpirePath())) {
            JsonNode n = JsonPathUtil.apply(root, spec.getExpirePath());
            if (n != null && n.isValueNode() && n.canConvertToLong()) {
                long v = n.asLong();
                if ("ms".equalsIgnoreCase(spec.getExpireUnit())) v = v / 1000;
                long ttl = v - spec.getSafetyMarginSec();
                if (ttl >= MIN_TTL_SEC) return ttl;
            }
        }
        return spec.getDefaultTtlSec();
    }

    private String canonical(Map<String, Object> m) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(new TreeMap<>(m == null ? Map.of() : m));
        } catch (Exception e) {
            return String.valueOf(m);
        }
    }

    private String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 8);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl modules/data-server -am -Dtest=PluginTokenProviderTest test`
Expected: PASS（7 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginTokenProvider.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/PluginTokenProviderTest.java
git commit -m "feat(plugin): PluginTokenProvider 实现 token 取/缓存/锁/抽取"
```

---

## Task 6: `TokenCachingAuthApplier` 子接口

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/TokenCachingAuthApplier.java`

- [ ] **Step 1: 实现子接口**

```java
package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;

import java.util.Map;

/**
 * 需要服务端换 token + 缓存的鉴权 applier。
 * 因需要 tenantId/pluginId 算缓存键，不走基类 apply(...)，改走 applyWithContext。
 */
public interface TokenCachingAuthApplier extends PluginAuthApplier {

    /** 注入 token（命中缓存或加锁 fetch）。 */
    void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                          Long pluginId, Map<String, Object> authConfig);

    /** 业务接口 401 时作废该插件的 token 缓存。 */
    void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig);

    /** 基类入口对本类无效（缺 tenantId/pluginId 上下文）。 */
    @Override
    default void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        throw new UnsupportedOperationException("token-caching applier 必须走 applyWithContext");
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `mvn -q -pl modules/data-server -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/TokenCachingAuthApplier.java
git commit -m "feat(plugin): TokenCachingAuthApplier 子接口"
```

---

## Task 7: `GenericTokenAuthApplier`（TOKEN_FETCH）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/GenericTokenAuthApplier.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/GenericTokenAuthApplierTest.java`

**说明：** 从 auth_config 的 `token_request` + `token_path`/`expire_path`/`inject` 构造 `TokenFetchSpec`，调 provider，按 inject 注入。测试用 Mockito mock `PluginTokenProvider`，验证「构造的 spec 正确」+「注入到对的 header」。

- [ ] **Step 1: 写失败测试**

```java
package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.GenericTokenAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenericTokenAuthApplierTest {

    private PluginExecutionContext ctx() {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(),
                new LinkedHashMap<>(Map.of("appKey","KK")), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    @Test
    void authType_isTokenFetch() {
        assertEquals("TOKEN_FETCH", new GenericTokenAuthApplier(mock(PluginTokenProvider.class)).authType());
    }

    @Test
    void buildsSpecFromConfig_andInjectsDefaultBearer() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), eq("ck"))).thenReturn("TOK");

        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = new LinkedHashMap<>();
        Map<String, Object> tokenReq = new LinkedHashMap<>();
        tokenReq.put("method", "POST");
        tokenReq.put("url", "https://auth.x.com/login");
        tokenReq.put("content_type", "application/json");
        tokenReq.put("headers", Map.of("Content-Type", "application/json"));
        tokenReq.put("body", "{\"appKey\":\"{{secrets.appKey}}\"}");
        authConfig.put("token_request", tokenReq);
        authConfig.put("token_path", "$.data.token");
        authConfig.put("expire_path", "$.data.expire");

        applier.applyWithContext(req, ctx(), 9L, authConfig);

        // 注入默认 Authorization: Bearer TOK
        assertEquals("Bearer TOK", req.getHeaders().get("Authorization"));

        // 校验构造的 spec
        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), eq("ck"));
        TokenFetchSpec s = cap.getValue();
        assertEquals("https://auth.x.com/login", s.getUrl());
        assertEquals("$.data.token", s.getTokenPath());
        assertEquals("$.data.expire", s.getExpirePath());
        assertEquals("{\"appKey\":\"{{secrets.appKey}}\"}", s.getBodyTemplate());
    }

    @Test
    void customInjection_xTokenNoPrefix() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), any())).thenReturn("RAW");
        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = new LinkedHashMap<>();
        authConfig.put("token_request", Map.of("url", "https://a/login"));
        authConfig.put("token_path", "$.token");
        authConfig.put("inject", Map.of("location", "header", "name", "X-Token", "prefix", ""));

        applier.applyWithContext(req, ctx(), 9L, authConfig);
        assertEquals("RAW", req.getHeaders().get("X-Token"));
    }

    @Test
    void missingTokenRequestUrl_throwsIllegalArg() {
        GenericTokenAuthApplier applier = new GenericTokenAuthApplier(mock(PluginTokenProvider.class));
        RenderedRequest req = new RenderedRequest();
        Map<String, Object> authConfig = Map.of("token_path", "$.token"); // 缺 token_request
        assertThrows(IllegalArgumentException.class,
                () -> applier.applyWithContext(req, ctx(), 9L, authConfig));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl modules/data-server -am -Dtest=GenericTokenAuthApplierTest test`
Expected: 编译失败 `cannot find symbol: GenericTokenAuthApplier`

- [ ] **Step 3: 实现 `GenericTokenAuthApplier`**

```java
package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** 通用「登录换 token」：请求/取值/注入全部由 auth_config 配置驱动。 */
@Component
public class GenericTokenAuthApplier implements TokenCachingAuthApplier {

    private final PluginTokenProvider provider;

    public GenericTokenAuthApplier(PluginTokenProvider provider) {
        this.provider = provider;
    }

    @Override
    public String authType() { return "TOKEN_FETCH"; }

    @Override
    public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                                 Long pluginId, Map<String, Object> authConfig) {
        TokenFetchSpec spec = buildSpec(authConfig);
        String cacheKey = provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets());
        String token = provider.resolveToken(spec, ctx, cacheKey);
        inject(req, authConfig, token);
    }

    @Override
    public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig) {
        provider.invalidate(provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets()));
    }

    @SuppressWarnings("unchecked")
    private TokenFetchSpec buildSpec(Map<String, Object> authConfig) {
        Object tr = authConfig == null ? null : authConfig.get("token_request");
        if (!(tr instanceof Map)) {
            throw new IllegalArgumentException("TOKEN_FETCH 缺少 token_request 配置");
        }
        Map<String, Object> req = (Map<String, Object>) tr;
        String url = str(req.get("url"), null);
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("TOKEN_FETCH token_request.url 不能为空");
        }
        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(url);
        s.setMethod(str(req.get("method"), "POST"));
        s.setContentType(str(req.get("content_type"), "application/json"));
        if (req.get("headers") instanceof Map<?, ?> hm) {
            Map<String, String> headers = new LinkedHashMap<>();
            hm.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            s.setHeaders(headers);
        }
        s.setBodyTemplate(str(req.get("body"), null));
        s.setTokenPath(str(authConfig.get("token_path"), "$.access_token"));
        s.setExpirePath(str(authConfig.get("expire_path"), null));
        s.setExpireUnit(str(authConfig.get("expire_unit"), "sec"));
        s.setDefaultTtlSec(num(authConfig.get("default_ttl_sec"), 3600));
        s.setSafetyMarginSec(num(authConfig.get("safety_margin_sec"), 60));
        return s;
    }

    @SuppressWarnings("unchecked")
    private void inject(RenderedRequest req, Map<String, Object> authConfig, String token) {
        String location = "header", name = "Authorization", prefix = "Bearer ";
        Object inj = authConfig.get("inject");
        if (inj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) inj;
            location = str(m.get("location"), location);
            name = str(m.get("name"), name);
            if (m.containsKey("prefix")) prefix = str(m.get("prefix"), "");
        }
        String value = prefix + token;
        if ("query".equalsIgnoreCase(location)) req.addQuery(name, value);
        else req.addHeader(name, value);
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private long num(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? def : Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return def; }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl modules/data-server -am -Dtest=GenericTokenAuthApplierTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/GenericTokenAuthApplier.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/GenericTokenAuthApplierTest.java
git commit -m "feat(plugin): GenericTokenAuthApplier（TOKEN_FETCH 通用登录换 token）"
```

---

## Task 8: `OAuth2ClientCredentialsAuthApplier`（OAUTH2）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/OAuth2ClientCredentialsAuthApplier.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/OAuth2ClientCredentialsAuthApplierTest.java`

**说明：** 固定 client_credentials 形态。`client_auth=body`（默认）→ 凭证拼进 form body；`client_auth=basic` → 走 `Authorization: Basic base64(id:secret)` 头、body 仅 grant_type/scope。token 取 `$.access_token`，注入 `Bearer`。

- [ ] **Step 1: 写失败测试**

```java
package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.OAuth2ClientCredentialsAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OAuth2ClientCredentialsAuthApplierTest {

    private PluginExecutionContext ctx() {
        return new PluginExecutionContext("t1", new LinkedHashMap<>(),
                new LinkedHashMap<>(Map.of("client_id", "cid", "client_secret", "csec")),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    @Test
    void authType_isOauth2() {
        assertEquals("OAUTH2", new OAuth2ClientCredentialsAuthApplier(mock(PluginTokenProvider.class)).authType());
    }

    @Test
    void bodyMode_buildsFormSpec_injectsBearer() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), eq("ck"))).thenReturn("AT");
        OAuth2ClientCredentialsAuthApplier applier = new OAuth2ClientCredentialsAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = Map.of(
                "token_url", "https://auth.x.com/oauth/token", "scope", "read");
        applier.applyWithContext(req, ctx(), 5L, authConfig);

        assertEquals("Bearer AT", req.getHeaders().get("Authorization"));

        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), eq("ck"));
        TokenFetchSpec s = cap.getValue();
        assertEquals("https://auth.x.com/oauth/token", s.getUrl());
        assertEquals("$.access_token", s.getTokenPath());
        assertEquals("$.expires_in", s.getExpirePath());
        assertTrue(s.getContentType().contains("x-www-form-urlencoded"));
        // body 用 {{secrets.x}} 引用，含 grant_type / client_id / client_secret / scope
        assertTrue(s.getBodyTemplate().contains("grant_type=client_credentials"));
        assertTrue(s.getBodyTemplate().contains("client_id={{secrets.client_id}}"));
        assertTrue(s.getBodyTemplate().contains("client_secret={{secrets.client_secret}}"));
        assertTrue(s.getBodyTemplate().contains("scope=read"));
    }

    @Test
    void basicMode_putsBasicHeader_andOmitsSecretInBody() {
        PluginTokenProvider provider = mock(PluginTokenProvider.class);
        when(provider.cacheKey(any(), any(), any(), any())).thenReturn("ck");
        when(provider.resolveToken(any(), any(), any())).thenReturn("AT");
        OAuth2ClientCredentialsAuthApplier applier = new OAuth2ClientCredentialsAuthApplier(provider);
        RenderedRequest req = new RenderedRequest();

        Map<String, Object> authConfig = Map.of(
                "token_url", "https://auth.x.com/oauth/token", "client_auth", "basic");
        applier.applyWithContext(req, ctx(), 5L, authConfig);

        ArgumentCaptor<TokenFetchSpec> cap = ArgumentCaptor.forClass(TokenFetchSpec.class);
        verify(provider).resolveToken(cap.capture(), any(), any());
        TokenFetchSpec s = cap.getValue();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("cid:csec".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, s.getHeaders().get("Authorization"));
        assertTrue(s.getBodyTemplate().contains("grant_type=client_credentials"));
        assertFalse(s.getBodyTemplate().contains("client_secret"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl modules/data-server -am -Dtest=OAuth2ClientCredentialsAuthApplierTest test`
Expected: 编译失败 `cannot find symbol`

- [ ] **Step 3: 实现 `OAuth2ClientCredentialsAuthApplier`**

```java
package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 标准 OAuth2 client_credentials。凭证：client_id / client_secret。
 * auth_config：token_url（必填）、scope、client_auth(body|basic)、expire_path、default_ttl_sec、safety_margin_sec。
 */
@Component
public class OAuth2ClientCredentialsAuthApplier implements TokenCachingAuthApplier {

    private final PluginTokenProvider provider;

    public OAuth2ClientCredentialsAuthApplier(PluginTokenProvider provider) {
        this.provider = provider;
    }

    @Override
    public String authType() { return "OAUTH2"; }

    @Override
    public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                                 Long pluginId, Map<String, Object> authConfig) {
        TokenFetchSpec spec = buildSpec(authConfig, ctx.getSecrets());
        String cacheKey = provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets());
        String token = provider.resolveToken(spec, ctx, cacheKey);
        req.addHeader("Authorization", "Bearer " + token);
    }

    @Override
    public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig) {
        provider.invalidate(provider.cacheKey(ctx.getTenantId(), pluginId, authConfig, ctx.getSecrets()));
    }

    private TokenFetchSpec buildSpec(Map<String, Object> authConfig, Map<String, Object> secrets) {
        String tokenUrl = str(authConfig.get("token_url"));
        if (!StringUtils.hasText(tokenUrl)) {
            throw new IllegalArgumentException("OAUTH2 缺少 token_url 配置");
        }
        String scope = str(authConfig.get("scope"));
        String clientAuth = StringUtils.hasText(str(authConfig.get("client_auth")))
                ? str(authConfig.get("client_auth")) : "body";

        TokenFetchSpec s = new TokenFetchSpec();
        s.setUrl(tokenUrl);
        s.setMethod("POST");
        s.setContentType("application/x-www-form-urlencoded");
        s.setTokenPath("$.access_token");
        s.setExpirePath("$.expires_in");
        s.setDefaultTtlSec(num(authConfig.get("default_ttl_sec"), 3600));
        s.setSafetyMarginSec(num(authConfig.get("safety_margin_sec"), 60));

        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        if ("basic".equalsIgnoreCase(clientAuth)) {
            // client_id:client_secret 用真实凭证算 Basic（不进 body）
            String id = str(secrets.get("client_id"));
            String secret = str(secrets.get("client_secret"));
            String basic = Base64.getEncoder().encodeToString(
                    (id + ":" + secret).getBytes(StandardCharsets.UTF_8));
            s.getHeaders().put("Authorization", "Basic " + basic);
        } else {
            // 用 {{secrets.x}} 占位，交给 renderer 渲染（与系统其余部分一致）
            body.append("&client_id={{secrets.client_id}}")
                .append("&client_secret={{secrets.client_secret}}");
        }
        if (StringUtils.hasText(scope)) {
            body.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        s.setBodyTemplate(body.toString());
        return s;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private long num(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? def : Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return def; }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl modules/data-server -am -Dtest=OAuth2ClientCredentialsAuthApplierTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/auth/OAuth2ClientCredentialsAuthApplier.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/OAuth2ClientCredentialsAuthApplierTest.java
git commit -m "feat(plugin): OAuth2ClientCredentialsAuthApplier（OAUTH2 client_credentials）"
```

---

## Task 9: 接入 `PluginHttpInvoker`（instanceof 分派 + 401 重试）

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginHttpInvoker.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/PluginHttpInvokerTokenRetryTest.java`

**说明：** 把 `doInvoke` 第 4 步认证 + 第 5 步调用改造为：token-caching 走 `applyWithContext`；业务返 401 且 token-caching 且未重试 → invalidate + 重渲染 + 重注入 + 重打一次。测试用 MockWebServer 当业务接口，mock `PluginAuthApplier`（token-caching）验证「首次 401 触发一次 invalidate + 第二次成功」。

- [ ] **Step 1: 写失败测试**

```java
package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.auth.PluginAuthApplier;
import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.service.*;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginHttpInvokerTokenRetryTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    /** 构造一个最小可用的 PluginToolEntry：OAUTH2 鉴权、业务 URL 指向 MockWebServer。 */
    private PluginToolEntry entry() {
        Plugin plugin = new Plugin();
        plugin.setId(7L);
        plugin.setTenantId("t1");
        plugin.setAuthType("OAUTH2");
        plugin.setAuthConfig("{\"token_url\":\"https://auth/x\"}");
        plugin.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));

        PluginHttpMapping mapping = new PluginHttpMapping();
        mapping.setMethod("GET");
        mapping.setUrlTemplate("/biz");

        PluginTool tool = new PluginTool();
        tool.setName("test_tool");
        return new PluginToolEntry(plugin, tool, mapping); // @RequiredArgsConstructor(plugin, tool, mapping)
    }

    @Test
    void on401_invalidatesAndRetriesOnce_thenSucceeds() {
        // 业务接口：第一次 401，第二次 200
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        PluginCredentialService credService = mock(PluginCredentialService.class);
        when(credService.resolveSecrets(anyLong())).thenReturn(Map.of("client_id","c","client_secret","s"));

        AtomicInteger applyCount = new AtomicInteger();
        AtomicInteger invalidateCount = new AtomicInteger();

        // 一个假的 token-caching applier：每次注入一个 Authorization；记录调用次数
        TokenCachingAuthApplier applier = new TokenCachingAuthApplier() {
            public String authType() { return "OAUTH2"; }
            public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx, Long pluginId, Map<String,Object> ac) {
                applyCount.incrementAndGet();
                req.addHeader("Authorization", "Bearer TOK" + applyCount.get());
            }
            public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String,Object> ac) {
                invalidateCount.incrementAndGet();
            }
        };

        PluginHttpInvoker invoker = new PluginHttpInvoker(
                new OkHttpClient(), credService,
                new PluginTemplateRenderer(), new PluginResponseExtractor(),
                List.of((PluginAuthApplier) applier));

        Object result = invoker.invoke(entry(), Map.of());

        assertEquals(2, applyCount.get(), "应注入两次（首次 + 重试）");
        assertEquals(1, invalidateCount.get(), "401 后作废一次缓存");
        assertEquals(2, server.getRequestCount(), "业务接口被打两次");
        assertTrue(result.toString().contains("ok"));
    }

    @Test
    void second401_noInfiniteLoop_returnsHttp4xx() {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));

        PluginCredentialService credService = mock(PluginCredentialService.class);
        when(credService.resolveSecrets(anyLong())).thenReturn(Map.of("client_id","c","client_secret","s"));

        TokenCachingAuthApplier applier = new TokenCachingAuthApplier() {
            public String authType() { return "OAUTH2"; }
            public void applyWithContext(RenderedRequest req, PluginExecutionContext ctx, Long pluginId, Map<String,Object> ac) {
                req.addHeader("Authorization", "Bearer X");
            }
            public void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String,Object> ac) {}
        };

        PluginHttpInvoker invoker = new PluginHttpInvoker(
                new OkHttpClient(), credService,
                new PluginTemplateRenderer(), new PluginResponseExtractor(),
                List.of((PluginAuthApplier) applier));

        Object result = invoker.invoke(entry(), Map.of());
        assertEquals(2, server.getRequestCount(), "最多重试一次");
        assertTrue(result.toString().contains("HTTP_4XX"));
    }
}
```

> `PluginToolEntry` 是 `@RequiredArgsConstructor(Plugin, PluginTool, PluginHttpMapping)`、有 `tenantId()`/`toolName()`；上面用真实构造。`Plugin`/`PluginHttpMapping`/`PluginTool` 均为 Lombok `@Data`，set 方法可用。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl modules/data-server -am -Dtest=PluginHttpInvokerTokenRetryTest test`
Expected: FAIL（当前 invoker 不会重试，applyCount=1 / 业务只打 1 次）

- [ ] **Step 3: 改造 `PluginHttpInvoker.doInvoke`**

把第 4 步认证 + 第 5 步 HTTP 调用抽成可重复一次的逻辑。关键改动：

(a) 认证分派——把现有第 4 步：
```java
applier.apply(req, secrets, authConfig);
```
改为：
```java
if (applier instanceof TokenCachingAuthApplier tca) {
    tca.applyWithContext(req, ctx, entry.getPlugin().getId(), authConfig);
} else {
    ctx.getEnv().put("body_sha256", sha256Hex(req.getBody()));
    applier.apply(req, secrets, authConfig);
}
```
（注意：`body_sha256` 仅 HMAC 等非 token-caching 用，移到 else 分支内，保持原行为。）

(b) 在「第 5 步调 HTTP」拿到 `status` 后，紧接 `status >= 400` 处理前插入 401 重试：
```java
if (status == 401 && applier instanceof TokenCachingAuthApplier tca2 && !retried) {
    retried = true;
    resp.close();                                   // 关掉首个响应
    tca2.invalidate(ctx, entry.getPlugin().getId(), authConfig);
    // 重渲染整条业务请求（旧 req body 已被消费、header 挂旧 token）
    req = templateRenderer.render(entry.getMapping(), ctx);
    req.setUrl(resolveUrl(entry.getPlugin().getBaseUrl(), req.getUrl()));
    out.request = req;
    tca2.applyWithContext(req, ctx, entry.getPlugin().getId(), authConfig);
    okRequest = buildOkRequest(req);
    resp = client.newCall(okRequest).execute();
    status = resp.code();
    body = resp.body() == null ? null : resp.body().string();
    out.status = status;
    out.rawBody = body;
}
```
为支持上面的重赋值，需把 `Request okRequest`、`RenderedRequest req`、`int status`、`String body` 声明为可变局部变量（去掉 final 语义），并在方法顶部 `boolean retried = false;`。`client` 已在外层 `clientFor(...)` 取得，复用同一个。

(c) 顶部 import：`import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;`

> 完整改造以现有 `doInvoke`（§PluginHttpInvoker.java 第 5 步 try 块）为基准做最小侵入修改；不动 try/catch 的异常分类与 finally 关流。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl modules/data-server -am -Dtest=PluginHttpInvokerTokenRetryTest test`
Expected: PASS（两个用例：成功重试 / 不死循环）

- [ ] **Step 5: 全量插件测试回归**

Run: `mvn -q -pl modules/data-server -am -Dtest='com.jimeng.dataserver.ai.plugin.**' test`
Expected: PASS（含原有 HmacAuthApplierTest / PluginTemplateRendererTest / PluginResponseExtractorTest）

- [ ] **Step 6: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/plugin/service/PluginHttpInvoker.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/plugin/PluginHttpInvokerTokenRetryTest.java
git commit -m "feat(plugin): invoker 接入 token-caching applier + 401 兜底重试"
```

---

## Task 10: 前端 — 认证方式两项 + auth_config 编辑

**Files:**
- Modify: `jm-agent-front/src/api/types.ts:97`
- Modify: `jm-agent-front/src/pages/console/plugin/PluginEditorPage.tsx:38-44, 155-181`

- [ ] **Step 1: 扩 `PluginAuthType` 联合类型**

`src/api/types.ts` 第 97 行：
```ts
export type PluginAuthType = 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY' | 'HMAC' | 'OAUTH2' | 'TOKEN_FETCH';
```

- [ ] **Step 2: 下拉加两项**

`PluginEditorPage.tsx` 的 `AUTH_TYPE_OPTIONS`（第 38-44 行）末尾加：
```ts
  { label: 'OAuth2 (client_credentials)', value: 'OAUTH2' },
  { label: '通用 Token 获取', value: 'TOKEN_FETCH' },
```

- [ ] **Step 3: auth_config 文本框覆盖新类型**

把第 158 行 `if (at !== 'API_KEY' && at !== 'HMAC') return null;` 改为：
```ts
                      if (!['API_KEY', 'HMAC', 'OAUTH2', 'TOKEN_FETCH'].includes(at ?? '')) return null;
```
并扩展 `extra` 示例（第 163-167 行的三元改成按类型给示例）：
```tsx
                          extra={
                            at === 'API_KEY'
                              ? '示例：{ "location": "header", "key_name": "X-API-Key" }'
                              : at === 'HMAC'
                              ? '示例：{ "algorithm": "HMAC_SHA256", "sign_template": "...", "placement": { "type": "header", "name": "X-Sign" } }'
                              : at === 'OAUTH2'
                              ? '示例：{ "token_url": "https://auth.x.com/oauth/token", "scope": "read", "client_auth": "body" }'
                              : '示例：{ "token_request": { "method":"POST", "url":"https://auth.x.com/login", "content_type":"application/json", "headers":{"Content-Type":"application/json"}, "body":"{\\"appKey\\":\\"{{secrets.appKey}}\\"}" }, "token_path":"$.data.token", "expire_path":"$.data.expire", "inject":{"location":"header","name":"Authorization","prefix":"Bearer "} }'
                          }
```
并把第 161 行 label 改成通用：`label={\`auth_config (${at} 非密配置 JSON)\`}`（已是此写法则不动）。

- [ ] **Step 4: 校验编译 + lint**

Run（在 `jm-agent-front`）：`npm run typecheck && npm run lint`
Expected: 无错（lint `--max-warnings 0`）

- [ ] **Step 5: 提交**

```bash
cd /Users/jerry/Desktop/jm/jm-agent-front
git add src/api/types.ts src/pages/console/plugin/PluginEditorPage.tsx
git commit -m "feat(plugin): 认证方式新增 OAUTH2 / TOKEN_FETCH + auth_config 编辑"
```
> 注：`jm-agent-front` 是独立 git repo，提交在该 repo。

---

## Task 11: 前端 — `CredentialPanel` 凭证字段

**Files:**
- Modify: `jm-agent-front/src/features/plugin/components/CredentialPanel.tsx`

- [ ] **Step 1: OAUTH2 固定字段**

在 `FIELDS_BY_TYPE` 加：
```ts
  OAUTH2: [
    { name: 'client_id', label: 'Client ID', placeholder: 'OAuth2 client_id' },
    { name: 'client_secret', label: 'Client Secret', secret: true, placeholder: 'OAuth2 client_secret' },
  ],
```

- [ ] **Step 2: TOKEN_FETCH 走自由 key-value 编辑器**

`TOKEN_FETCH` 的凭证键名由用户自定义（body 模板里 `{{secrets.x}}` 引用），不能用固定 `FIELDS_BY_TYPE`。在 `CredentialPanel` 渲染分支里：当 `authType === 'TOKEN_FETCH'` 时，渲染一个动态行编辑器（每行 key + 密码值 + 删除，底部「+ 添加字段」），保存时收成 `{key:value}` 走 `pluginCredApi.save(pluginId, { credentialJson })`（与现有保存一致）。用 AntD `Form.List`：

```tsx
// 在 return 里，当 authType === 'TOKEN_FETCH' 时替换 <Form>...fields.map... 的渲染：
// 初值：useEffect 里把 data.credentialJson 铺成 [{key,value}, ...]
// 提交：把行数组折回 { [key]: value }
```

实现要点（替换/新增渲染逻辑，保留顶部「凭证 + 保存按钮」与说明）：
```tsx
import { Form, Input, Button, Space } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';

// authType === 'TOKEN_FETCH' 分支用独立 Form：
// <Form form={form} layout="vertical" onFinish={onFinishKv}>
//   <Form.List name="rows"> 渲染 {key 输入 + Input.Password 值 + 删除按钮}
//   底部 <Button icon={<PlusOutlined/>} onClick={() => add()}>添加字段</Button>
// 初值回填：const rows = Object.entries(data?.credentialJson ?? {}).map(([k,v]) => ({ key:k, value:String(v) }));
//          form.setFieldsValue({ rows });
// onFinishKv: const credentialJson = {}; rows.forEach(r => { if (r.key) credentialJson[r.key] = r.value; });
//             pluginCredApi.save(pluginId, { credentialJson })
```
（完整实现：在该文件加一个 `if (authType === 'TOKEN_FETCH')` 渲染分支，复用现有 `saveMut` 的成功/失败回调与查询，仅 form 形态不同；其余 authType 保持现状。）

- [ ] **Step 3: 校验编译 + lint**

Run（在 `jm-agent-front`）：`npm run typecheck && npm run lint`
Expected: 无错

- [ ] **Step 4: 提交**

```bash
cd /Users/jerry/Desktop/jm/jm-agent-front
git add src/features/plugin/components/CredentialPanel.tsx
git commit -m "feat(plugin): 凭证面板支持 OAUTH2 固定字段 + TOKEN_FETCH 自由 key-value"
```

---

## Task 12: 全量验证 + 收尾

- [ ] **Step 1: 后端全量插件测试**

Run: `cd /Users/jerry/Desktop/jm/data-service && mvn -q -pl modules/data-server -am -Dtest='com.jimeng.dataserver.ai.plugin.**' test`
Expected: PASS（新 5 个测试类 + 原有插件测试全绿）

- [ ] **Step 2: 后端编译完整模块**

Run: `cd /Users/jerry/Desktop/jm/data-service && mvn -q -pl modules/data-server -am -DskipTests package`
Expected: BUILD SUCCESS

- [ ] **Step 3: 前端构建**

Run: `cd /Users/jerry/Desktop/jm/jm-agent-front && npm run build`
Expected: tsc + vite build 成功

- [ ] **Step 4: 前端容器重建（让 8082 生效，沿用既有方式）**

Run:
```bash
cd /Users/jerry/Desktop/jm/jm-agent-front
docker build --build-arg NGINX_CONF=nginx.local.conf -t jm-agent-front:local .
docker rm -f jm-agent-front && docker run -d --name jm-agent-front -p 8082:80 jm-agent-front:local
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8082/
```
Expected: HTTP 200

- [ ] **Step 5: 后端重启（让新 jar 生效）**

Run: `cd /Users/jerry/Desktop/jm/data-service && mvn -q -pl modules/data-server -am -DskipTests install && bash /Users/jerry/Desktop/jm/start-local.sh status`
然后重启 data-server 进程（kill 旧 :8020 → `bash start-local.sh`）。
Expected: data-server/gateway/agent-sandbox 全 UP

---

## 自检记录（spec 覆盖）

| spec 节 | 对应 Task |
|---|---|
| §3 数据模型（OAUTH2/TOKEN_FETCH 字段） | Task 8/7（applier 解析）+ Task 10/11（前端字段） |
| §4.1 JsonPathUtil | Task 1 |
| §4.2 TokenFetchSpec | Task 3 |
| §4.3 PluginTokenProvider | Task 5 |
| §4.4 TokenCachingAuthApplier | Task 6 |
| §4.5 两个 applier | Task 7/8 |
| §4.6 invoker 改动 | Task 9 |
| §5 数据流 + 401 重试 | Task 9 |
| §6 缓存键/TTL/并发 | Task 5（cacheKey/computeTtl/lock） |
| §7 错误处理（TOKEN_FETCH_FAILED） | Task 2 + Task 5（抛点） |
| §8 前端 | Task 10/11 |
| §9 测试 | 各 Task 的测试步骤 + Task 12 |

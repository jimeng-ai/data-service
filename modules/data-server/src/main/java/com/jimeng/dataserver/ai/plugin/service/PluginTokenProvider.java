package com.jimeng.dataserver.ai.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.dataserver.ai.plugin.util.JsonPathUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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

/**
 * token 获取 + 缓存（Redisson RBucket）+ 防并发（RLock）+ JSONPath 抽取。
 *
 * <p>两个 token-caching applier（OAuth2 / Generic）把 {@link TokenFetchSpec} 交给这里执行：
 * 命中缓存直接返回；未命中则加锁、渲染 token 请求模板、打 token 接口、抽取 token + 过期、写缓存。
 */
@Slf4j
@Service
public class PluginTokenProvider {

    /** token 获取/抽取失败 */
    public static class TokenFetchException extends RuntimeException {
        public TokenFetchException(String message) {
            super(message);
        }
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

    /** 缓存键：tenant + plugin + (authConfig|凭证) 短哈希——改配置/换密钥即时失效。 */
    public String cacheKey(String tenantId, Long pluginId,
                           Map<String, Object> authConfig, Map<String, Object> secrets) {
        String material = canonical(authConfig) + "|" + canonical(secrets);
        return "plugin:auth:token:" + tenantId + ":" + pluginId + ":" + sha8(material);
    }

    /** 业务接口 401 时作废缓存。 */
    public void invalidate(String cacheKey) {
        redisson.getBucket(cacheKey).delete();
    }

    public String resolveToken(TokenFetchSpec spec, PluginExecutionContext ctx, String cacheKey) {
        RBucket<String> bucket = redisson.getBucket(cacheKey);
        String cached = bucket.get();
        if (StringUtils.hasText(cached)) {
            return cached;
        }

        RLock lock = redisson.getLock(cacheKey + ":lock");
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(LOCK_WAIT_SEC, LOCK_LEASE_SEC, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (locked) {
                String again = bucket.get();   // 双重检查：别的线程可能刚填好
                if (StringUtils.hasText(again)) {
                    return again;
                }
            }
            // locked=false（拥塞）也降级直接 fetch，宁可多打一次也不阻塞业务
            return fetchAndCache(spec, ctx, bucket);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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
                if ("ms".equalsIgnoreCase(spec.getExpireUnit())) {
                    v = v / 1000;
                }
                long ttl = v - spec.getSafetyMarginSec();
                if (ttl >= MIN_TTL_SEC) {
                    return ttl;
                }
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

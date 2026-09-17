package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * sandbox 语义层能力探测。结果（成功与失败都包括）缓存 15 秒，避免同时建连时反复打边车。
 */
@Slf4j
@Component
public class SandboxHealthProbe {

    static final long CACHE_MILLIS = 15_000L;
    private static final int TIMEOUT_MIN_MS = 200;
    private static final int TIMEOUT_MAX_MS = 10_000;

    private final SidecarClient sidecarClient;
    private final ConnectorProperties properties;
    private final LongSupplier nowMillis;
    private volatile CacheEntry cache;

    @Autowired
    public SandboxHealthProbe(SidecarClient sidecarClient, ConnectorProperties properties) {
        this(sidecarClient, properties, System::currentTimeMillis);
    }

    /** 可控时钟只给同包单测用，生产始终走上面的 Spring 构造器。 */
    SandboxHealthProbe(SidecarClient sidecarClient, ConnectorProperties properties, LongSupplier nowMillis) {
        this.sidecarClient = sidecarClient;
        this.properties = properties;
        this.nowMillis = nowMillis;
    }

    public ProbeResult probe() {
        long now = nowMillis.getAsLong();
        CacheEntry seen = cache;
        if (fresh(seen, now)) {
            return seen.result();
        }
        synchronized (this) {
            seen = cache;
            if (fresh(seen, now)) {
                return seen.result();
            }
            ProbeResult result = probeFresh(timeout());
            cache = new CacheEntry(now, result);
            return result;
        }
    }

    private static boolean fresh(CacheEntry entry, long now) {
        if (entry == null) {
            return false;
        }
        long age = now - entry.atMillis();
        return age >= 0 && age < CACHE_MILLIS;
    }

    private Duration timeout() {
        int configured = properties.getSemantic().getAgent().getHealthTimeoutMs();
        int millis = Math.max(TIMEOUT_MIN_MS, Math.min(TIMEOUT_MAX_MS, configured));
        return Duration.ofMillis(millis);
    }

    private ProbeResult probeFresh(Duration timeout) {
        try {
            RequestService.HttpResp health = sidecarClient.healthz(timeout);
            ProbeResult healthFailure = healthFailure(health);
            if (healthFailure != null) {
                return healthFailure;
            }

            RequestService.HttpResp capabilities = sidecarClient.capabilities(timeout);
            return capabilitiesResult(capabilities);
        } catch (Exception e) {
            String summary = hasCause(e, SocketTimeoutException.class) ? "探测超时" : "探测请求异常";
            log.warn("sandbox 健康探测失败: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return ProbeResult.down("sandbox 健康检查失败：" + summary);
        }
    }

    private static ProbeResult healthFailure(RequestService.HttpResp response) {
        if (response == null || response.getStatusCode() == null) {
            return ProbeResult.down("sandbox 健康检查失败：healthz 无响应");
        }
        if (response.getStatusCode() != 200) {
            return ProbeResult.down("sandbox 健康检查失败：healthz 返回 HTTP "
                    + response.getStatusCode());
        }
        String body = response.getBody();
        if (body != null && body.startsWith("ok (AUTH DISABLED")) {
            return ProbeResult.down("sandbox 未启用入站鉴权");
        }
        if (!"ok".equals(body)) {
            return ProbeResult.down("sandbox 健康检查失败：healthz 正文不是 ok");
        }
        return null;
    }

    private static ProbeResult capabilitiesResult(RequestService.HttpResp response) {
        if (response == null || response.getStatusCode() == null) {
            return ProbeResult.down("sandbox 健康检查失败：capabilities 无响应");
        }
        int status = response.getStatusCode();
        if (status == 401) {
            return ProbeResult.down("sandbox 鉴权失败");
        }
        if (status == 404) {
            return ProbeResult.down("sandbox 版本不支持语义层");
        }
        if (status != 200) {
            return ProbeResult.down("sandbox 健康检查失败：capabilities 返回 HTTP " + status);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = CommonUtil.getObjectMapper().readValue(response.getBody(), Map.class);
            Object rawProfiles = parsed.get("runProfiles");
            if (rawProfiles == null) {
                return ProbeResult.down("sandbox 版本不支持语义层");
            }
            if (!(rawProfiles instanceof Collection<?> profiles)) {
                return ProbeResult.down("sandbox 健康检查失败：capabilities 正文异常");
            }
            boolean supported = profiles.stream().anyMatch("semantic-layer"::equals);
            return supported
                    ? ProbeResult.up()
                    : ProbeResult.down("sandbox 版本不支持语义层");
        } catch (Exception e) {
            return ProbeResult.down("sandbox 健康检查失败：capabilities 正文异常");
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable cursor = error;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public record ProbeResult(boolean healthy, String reason) {
        static ProbeResult up() {
            return new ProbeResult(true, null);
        }

        static ProbeResult down(String reason) {
            return new ProbeResult(false, reason);
        }
    }

    private record CacheEntry(long atMillis, ProbeResult result) {
    }
}

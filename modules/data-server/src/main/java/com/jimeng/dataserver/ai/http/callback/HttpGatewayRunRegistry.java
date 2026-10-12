package com.jimeng.dataserver.ai.http.callback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.http.SkillGrant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 运行凭证的登记：在跑的这一次运行 → 它的 Agent 与「本次可用的 skill」快照（设计文档 §4.1）。
 *
 * <p>两个作用：一是吊销（运行结束即删，在途回调随即进不来）；二是授权——网关只认这份快照里的 skill，
 * 请求里自带的任何身份字段一概不用。快照在派发时冻结，运行中途有人发布新版本也不影响这次运行。
 *
 * <p>fail-closed：没登记、Agent 对不上、Redis 出错，一律当作不在跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpGatewayRunRegistry {

    private static final String KEY_PREFIX = "http-gw:run:";
    private static final ObjectMapper JSON = new ObjectMapper();

    // data-server 的 Jackson 是 2.11.1，不认 record：组件写明 JSON 名、构造器标 @JsonCreator（漏了 begin() 会静默失败）
    private record Entry(@JsonProperty("agentId") String agentId, @JsonProperty("grants") List<SkillGrant> grants) {
        @JsonCreator
        Entry {}
    }

    private final StringRedisTemplate redis;

    /** @return 登记成功才为 true；调用方据此决定这次运行带不带网关能力（登记不上就不签 token）。 */
    public boolean begin(String tenantId, String runId, Long agentId, List<SkillGrant> grants, long ttlMs) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null || ttlMs <= 0) return false;
        try {
            redis.opsForValue().set(key(tenantId, runId), JSON.writeValueAsString(new Entry(String.valueOf(agentId), grants)),
                    Duration.ofMillis(ttlMs));
            return true;
        } catch (Exception e) {
            log.warn("http 网关运行登记失败 tenantId={} runId={}", tenantId, runId);
            return false;
        }
    }

    /** 在跑且 Agent 对得上 → 本次可用的 skill；否则 null。 */
    public List<SkillGrant> activeGrants(String tenantId, String runId, Long agentId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null) return null;
        try {
            String raw = redis.opsForValue().get(key(tenantId, runId));
            if (raw == null) {
                log.warn("http 网关回调被拒：运行未登记或已结束 tenantId={} runId={}", tenantId, runId);
                return null;
            }
            Entry e = JSON.readValue(raw, new TypeReference<Entry>() {});
            if (!Objects.equals(e.agentId(), String.valueOf(agentId))) {
                log.warn("http 网关回调被拒：runId 绑定的 agent 不符 tenantId={} runId={}", tenantId, runId);
                return null;
            }
            return e.grants() == null ? List.of() : e.grants();
        } catch (Exception ex) {
            log.warn("http 网关运行登记查询失败，按未在跑处理 tenantId={} runId={}", tenantId, runId);
            return null;
        }
    }

    public void end(String tenantId, String runId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId)) return;
        try {
            redis.delete(key(tenantId, runId));
        } catch (RuntimeException e) {
            log.warn("http 网关运行撤销失败 tenantId={} runId={}", tenantId, runId);
        }
    }

    private static String key(String tenantId, String runId) {
        return KEY_PREFIX + tenantId + ":" + runId;
    }
}

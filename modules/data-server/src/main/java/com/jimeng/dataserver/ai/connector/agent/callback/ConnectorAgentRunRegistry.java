package com.jimeng.dataserver.ai.connector.agent.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 对话 agent 沙箱运行的<b>在跑登记</b>，回调 token 的吊销开关。
 *
 * <h3>★ 为什么必须有这张表（Redis key）</h3>
 * 语义层那套用 {@code connector_semantic_generation.current_run_id} 做吊销：派发前写、运行一结束置空，
 * 过滤器每次查库，旧尝试的在途回调立刻进不来。对话 run <b>没有这样一张表</b>——
 * {@code RunRegistry} 是进程内的 Map，重启即空、多副本各看各的，做不了凭据吊销。
 *
 * <p>少了这一步，一枚泄漏的 token 在整个 TTL 内都是一枚<b>可用的、该 Agent 全部连接器的读写凭据</b>，
 * 而且无从吊销：运行早就结束了，我们却没有任何地方能说「这枚作废了」。
 *
 * <h3>fail-closed</h3>
 * key 不存在（运行已结束 / 已过期）、值对不上（同一 runId 被另一个 agent 复用）、Redis 抖动，
 * 一律返回 false 并 log.warn。宁可让一次在途回调失败（模型会收到明确错误、可以重试），
 * 也不能因为「查不到就放行」把吊销变成一句空话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorAgentRunRegistry {

    private static final String KEY_PREFIX = "conn-agent:run:";

    private final StringRedisTemplate redis;

    /**
     * 登记一次运行。TTL 与回调 token 的有效期同口径（见 {@code AdminAuthService.connectorAgentTokenTtlMs}）：
     * 登记先于 token 签发失效，会让运行末尾的回调被自己的吊销开关拒掉。
     */
    public void begin(String tenantId, String runId, Long agentId, long ttlMs) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null || ttlMs <= 0) {
            log.warn("连接器 agent 运行登记参数不完整，跳过 tenantId={} runId={} agentId={} ttlMs={}",
                    tenantId, runId, agentId, ttlMs);
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, runId), String.valueOf(agentId), Duration.ofMillis(ttlMs));
        } catch (RuntimeException e) {
            // 登记失败不抛：抛了会把一次本可以降级（沙箱平面没有 conn_ 工具）的运行变成整轮 500。
            // 但后续的 isActive 必然返回 false，能力自然不可用，方向仍是 fail-closed。
            log.warn("连接器 agent 运行登记失败 tenantId={} runId={} agentId={}", tenantId, runId, agentId, e);
        }
    }

    /** 回调放行判据：这个租户下这个 runId 确实在跑，且跑的就是 token 里那个 agent。 */
    public boolean isActive(String tenantId, String runId, Long agentId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null) {
            return false;
        }
        try {
            String current = redis.opsForValue().get(key(tenantId, runId));
            if (current == null) {
                log.warn("连接器 agent 回调被拒：运行未登记或已结束 tenantId={} runId={}", tenantId, runId);
                return false;
            }
            if (!current.equals(String.valueOf(agentId))) {
                // 同一个 runId 对应着另一个 agent：不是过期，是 token 与运行对不上，值得单独喊一声。
                log.warn("连接器 agent 回调被拒：runId 绑定的 agent 不符 tenantId={} runId={} tokenAgentId={}",
                        tenantId, runId, agentId);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("连接器 agent 运行登记查询失败，按未在跑处理 tenantId={} runId={}", tenantId, runId, e);
            return false;
        }
    }

    /** 运行结束即撤销。幂等：重复调用、或 key 早已因 TTL 消失，都不报错。 */
    public void end(String tenantId, String runId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId)) {
            return;
        }
        try {
            redis.delete(key(tenantId, runId));
        } catch (RuntimeException e) {
            // 删不掉只是让 token 活到 TTL 自然过期，不是安全事故，但要留痕。
            log.warn("连接器 agent 运行撤销失败 tenantId={} runId={}", tenantId, runId, e);
        }
    }

    private static String key(String tenantId, String runId) {
        return KEY_PREFIX + tenantId + ":" + runId;
    }
}

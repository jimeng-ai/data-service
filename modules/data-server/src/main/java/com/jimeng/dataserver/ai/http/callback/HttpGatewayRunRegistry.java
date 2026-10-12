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
 * <p>两个作用：一是吊销（先确认执行终态，再清理 grant；清理失败可重试）；二是授权——网关只认这份快照里的 skill，
 * 请求里自带的任何身份字段一概不用。快照在派发时冻结，运行中途有人发布新版本也不影响这次运行。
 *
 * <p>fail-closed：权威执行记录不是绑定的 RUNNING，或没登记、Agent 对不上、存储读取出错，一律当作不在跑。
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
    private final com.jimeng.persistence.mapper.AgentExecRunMapper runs;
    private record RunKey(String tenant, String run) {}
    private record Pending(com.jimeng.persistence.entity.AgentExecRun terminal) {}
    private final java.util.concurrent.ConcurrentMap<RunKey, Long> expiries = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<RunKey, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();

    /** @return 登记成功才为 true；调用方据此决定这次运行带不带网关能力（登记不上就不签 token）。 */
    public boolean begin(String tenantId, String runId, Long agentId, List<SkillGrant> grants, long ttlMs) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null || ttlMs <= 0) return false;
        if (expiries.size() >= 10000) return false;
        try {
            redis.opsForValue().set(key(tenantId, runId), JSON.writeValueAsString(new Entry(String.valueOf(agentId), grants)),
                    Duration.ofMillis(ttlMs));
            expiries.put(new RunKey(tenantId, runId), System.currentTimeMillis() + ttlMs);
            return true;
        } catch (Exception e) {
            log.warn("http 网关运行登记失败 tenantId={} runId={}", tenantId, runId);
            return false;
        }
    }

    /** 在跑且 Agent 对得上 → 本次可用的 skill；否则 null。 */
    public List<SkillGrant> activeGrants(String tenantId, String runId, Long agentId, Long userId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId) || agentId == null || userId == null) return null;
        if (pending.containsKey(new RunKey(tenantId, runId))) return null;
        try {
            // rid 是执行记录雪花 ID，不是聊天 UUID；跨实例每次都读权威状态，不缓存 RUNNING。
            Long id = Long.valueOf(runId);
            if (!id.toString().equals(runId)) return null;
            com.jimeng.persistence.entity.AgentExecRun run = runs.selectById(id);
            if (run == null || !"RUNNING".equals(run.getStatus()) || Boolean.TRUE.equals(run.getDeleted())
                    || !Objects.equals(run.getTenantId(), tenantId) || !Objects.equals(run.getAgentId(), agentId.toString())
                    || !Objects.equals(run.getUserId(), userId.toString()) || !Objects.equals(run.getId(), id)) return null;
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

    /** 先确认权威终态。失败保留原终态以便重试；不把未经确认的落库当作结束成功。 */
    public boolean confirmTerminal(com.jimeng.persistence.entity.AgentExecRun terminal) {
        RunKey key = new RunKey(terminal.getTenantId(), String.valueOf(terminal.getId()));
        if (!expiries.containsKey(key) || !("SUCCESS".equals(terminal.getStatus()) || "FAILED".equals(terminal.getStatus()))) return false;
        if (writeTerminal(terminal)) return true;
        pending.put(key, new Pending(terminal));
        return false;
    }

    private boolean writeTerminal(com.jimeng.persistence.entity.AgentExecRun terminal) {
        String previous = com.jimeng.common.core.tenant.TenantContext.get();
        try {
            com.jimeng.common.core.tenant.TenantContext.set(terminal.getTenantId());
            return runs.updateById(terminal) == 1;
        } catch (RuntimeException failure) {
            log.warn("http 网关执行终态尚未确认 tenantId={} runId={}", terminal.getTenantId(), terminal.getId());
            return false;
        } finally {
            if (previous == null) com.jimeng.common.core.tenant.TenantContext.clear();
            else com.jimeng.common.core.tenant.TenantContext.set(previous);
        }
    }

    public boolean end(String tenantId, String runId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runId)) return false;
        RunKey scope = new RunKey(tenantId, runId);
        // 仅登记过的本进程运行保留重试状态；另一实例不靠这张进程内表作授权。
        if (expiries.containsKey(scope)) pending.putIfAbsent(scope, new Pending(null));
        return cleanup(scope);
    }

    private boolean cleanup(RunKey scope) {
        Pending work = pending.get(scope);
        boolean terminal = work == null || work.terminal() == null || writeTerminal(work.terminal());
        if (terminal && work != null && work.terminal() != null) pending.replace(scope, work, new Pending(null));
        boolean deleted = false;
        try {
            deleted = redis.delete(key(scope.tenant(), scope.run())) != null; // false=已不存在，也确认成功；null=未确认
        } catch (RuntimeException failure) {
            log.warn("http 网关运行撤销尚未确认 tenantId={} runId={}", scope.tenant(), scope.run());
        }
        if (terminal && deleted) { pending.remove(scope); expiries.remove(scope); return true; }
        return false;
    }

    /** 每秒最多处理 50 项；保留到确认清理或原凭证 TTL 到期，不创建每运行一个线程。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000L, initialDelay = 1000L)
    public void retryPending() {
        long now = System.currentTimeMillis();
        expiries.forEach((scope, expiry) -> {
            if (expiry <= now) { expiries.remove(scope, expiry); pending.remove(scope); }
        });
        pending.keySet().stream().limit(50).forEach(this::cleanup);
    }

    private static String key(String tenantId, String runId) {
        return KEY_PREFIX + tenantId + ":" + runId;
    }
}

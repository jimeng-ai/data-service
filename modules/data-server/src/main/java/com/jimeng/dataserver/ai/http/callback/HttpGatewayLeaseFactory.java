package com.jimeng.dataserver.ai.http.callback;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AiSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次沙箱运行的网关租约：判定 → 登记「本次可用的 skill」→ 签运行凭证（设计文档 §4.1）。与 ConnectorAgentContextFactory 同一个形状：
 * 租约必须在 finally 里 close，close 幂等、对没授予的租约是空操作。
 *
 * <p>S1 里「本次可用的 skill」= 本轮下发进沙箱的企业 skill（仍只有 DOER，S2a 起带上全部可用 skill）里有 HTTP 声明的那些。
 */
@Component
@RequiredArgsConstructor
public class HttpGatewayLeaseFactory {

    private final HttpGatewayProperties props;
    private final HttpGatewayRunRegistry registry;
    private final AdminAuthService adminAuthService;

    public static final class Lease implements AutoCloseable {
        private final SidecarRunPayload.HttpContext context;
        private final String declineReason;
        private final HttpGatewayRunRegistry registry;
        private final String tenantId;
        private final String runId;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(SidecarRunPayload.HttpContext context, String declineReason, HttpGatewayRunRegistry registry,
                      String tenantId, String runId) {
            this.context = context;
            this.declineReason = declineReason;
            this.registry = registry;
            this.tenantId = tenantId;
            this.runId = runId;
        }

        static Lease declined(String reason) {
            return new Lease(null, reason, null, null, null);
        }

        public boolean granted() {
            return context != null;
        }

        public SidecarRunPayload.HttpContext context() {
            return context;
        }

        public String declineReason() {
            return declineReason;
        }

        @Override
        public void close() {
            if (registry != null && closed.compareAndSet(false, true)) registry.end(tenantId, runId);
        }
    }

    public Lease open(String tenantId, Long userId, Long agentId, String runId, int wallClockSec, List<AiSkill> shippedSkills) {
        List<SkillGrant> grants = shippedSkills == null ? List.of() : shippedSkills.stream()
                .map(s -> new SkillGrant(s.getId(), s.getVersion() == null ? 1 : s.getVersion(), s.getName(),
                        SkillManifest.fromJson(s.getManifest())))
                .filter(g -> g.manifest().hasHttp())
                .toList();
        if (grants.isEmpty()) return Lease.declined("本轮下发的技能都没有访问声明");
        if (StrUtil.isBlank(props.getCallbackBaseUrl())) return Lease.declined("没配 http-gateway.callback-base-url");
        if (StrUtil.isBlank(tenantId) || userId == null || agentId == null || StrUtil.isBlank(runId)) {
            return Lease.declined("缺租户 / 用户 / Agent / runId：token 的 claims 与登记都以它们为准");
        }
        long ttlMs = AdminAuthService.httpGatewayTokenTtlMs(wallClockSec);
        // 先登记再签发：登记是这枚 token 的吊销开关，也是网关唯一认的「本次可用的 skill」。
        if (!registry.begin(tenantId, runId, agentId, grants, ttlMs)) return Lease.declined("运行登记失败");

        SidecarRunPayload.HttpContext ctx = new SidecarRunPayload.HttpContext();
        ctx.setCallbackBaseUrl(props.getCallbackBaseUrl());
        try {
            String token = adminAuthService.mintHttpGatewayToken(userId, tenantId, agentId, runId, ttlMs);
            if (StrUtil.isBlank(token)) throw new IllegalStateException();
            ctx.setAccessToken(token);
        } catch (RuntimeException e) {
            registry.end(tenantId, runId);
            return Lease.declined("运行凭据签发失败");
        }
        ctx.setTimeoutSeconds(grants.stream().mapToInt(g -> g.manifest().timeoutSeconds()).max().orElse(60));
        List<SidecarRunPayload.HttpKeyDecl> keys = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        boolean web = false;
        for (SkillGrant g : grants) {
            for (SkillManifest.KeyDecl k : g.manifest().keys()) {
                SidecarRunPayload.HttpKeyDecl d = new SidecarRunPayload.HttpKeyDecl();
                d.setSkill(g.skillName());
                d.setName(k.name());
                d.setHosts(k.hosts());
                d.setMethods(k.methods());
                d.setPaths(k.paths());
                keys.add(d);
            }
            hosts.addAll(g.manifest().hosts());
            web |= Boolean.TRUE.equals(g.manifest().web());
        }
        ctx.setKeys(keys);
        ctx.setHosts(List.copyOf(hosts));
        ctx.setWeb(web);
        return new Lease(ctx, null, registry, tenantId, runId);
    }
}

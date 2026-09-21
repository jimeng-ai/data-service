package com.jimeng.dataserver.ai.connector.agent;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.connector.agent.callback.ConnectorAgentRunRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 一次沙箱运行的<b>连接器工具代理凭据</b>：前置判断 → 登记 → 签发 token → 组 {@code connectorContext}，
 * 运行结束由租约 {@link Lease#close()} 撤销登记。
 *
 * <h3>为什么要抽出来</h3>
 * 这套动作原本只长在 {@code AgentExecService.streamExec} 里。评测（{@code SkillEvalService}）也要派
 * 真沙箱跑 connector 技能，如果各写一份，两边迟早会分叉——而分叉的方向几乎注定是
 * 「评测这一份忘了 begin 登记 / 忘了 finally end」：前者让回调被自己的吊销开关拒掉（表现为
 * 模型手里的 conn_* 全部失败），后者让一枚泄漏的 token 在整个 TTL 内仍是该 Agent 全部连接器的
 * 读写凭据。两种都不会在编译期、也不会在启动期被发现。
 *
 * <h3>★ 五（七）个前置条件全真才下发，任一不真整段不下发</h3>
 * 半截下发的表现是边车 400 拒掉整轮，或者更糟：工具静默不装，而派发方以为下发成功了。
 * 判断不通过时<b>返回一句人话的理由</b>而不是 {@code false}：对话平面据此静默降级（日志里说得清），
 * 评测平面据此<b>直接报错</b>——评测最怕的就是跑出一个「模型调不到工具」的假红灯，
 * 那会让人去改 SKILL.md 的文字，而真正的原因是本轮压根没有工具。
 *
 * <h3>不下发 SKILL.md</h3>
 * 本类只管凭据与工具定义。SKILL.md 由调用方自己决定：对话平面追加
 * {@link ConnectorSkillMaterializer} 物化的那份，评测平面测的就是被评测的那份 skill 包本身，
 * 再追加一份会变成同一个技能在沙箱里出现两次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorAgentContextFactory {

    /** 平台技能 connector 的名字（{@code skills/connector/SKILL.md} 的 frontmatter name）。 */
    public static final String CONNECTOR_SKILL_NAME = "connector";

    /**
     * 派发前问一次边车 {@code /sandbox/capabilities} 的超时。刻意很短：这一问只用来决定
     * 「本轮要不要带连接器工具」，探不到就按不带处理，没有理由为它把用户的这一轮拖住。
     */
    private static final Duration CAPS_PROBE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 边车对 {@code connectorContext.tools[].name} 的校验式（{@code defaultProfile.ts} 的
     * {@code CONNECTOR_TOOL_NAME_RE}）。这里重复一遍不是冗余：名字不合式时边车是
     * <b>400 拒掉整轮 run</b>，也就是说 tools.json 里一个改错的名字会让所有绑了连接的 Agent
     * 在带附件时整轮报错。宁可本轮不带连接器工具（退回现状），也不要炸掉整轮对话。
     */
    private static final Pattern CONNECTOR_TOOL_NAME = Pattern.compile("^conn_[a-z_]{1,32}$");

    private final ConnectorProperties connectorProperties;
    private final ConnectorOverviewService connectorOverviewService;
    private final ConnectorAgentRunRegistry connectorAgentRunRegistry;
    private final AdminAuthService adminAuthService;
    private final SidecarClient sidecarClient;
    private final SkillPackageLoaderService skillPackageLoaderService;

    /**
     * 一次运行的连接器租约。{@link #granted()} 为假时 {@link #context()} 为 null、
     * {@link #declineReason()} 是一句人话的理由。
     *
     * <p>{@link #close()} <b>幂等且对未授予的租约是空操作</b>，所以调用方可以无条件
     * {@code try (Lease l = factory.open(...))} 或在 finally 里直接 close，不必再自己记一个
     * {@code connectorRegistered} 布尔——那个布尔正是过去最容易漏掉的一行。
     */
    public static final class Lease implements AutoCloseable {

        private final SidecarRunPayload.ConnectorContext context;
        private final String declineReason;
        private final ConnectorAgentRunRegistry registry;
        private final String tenantId;
        private final String runId;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(SidecarRunPayload.ConnectorContext context, String declineReason,
                      ConnectorAgentRunRegistry registry, String tenantId, String runId) {
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

        /** 直接塞进 {@code payload.setConnectorContext(...)}；未授予时为 null。 */
        public SidecarRunPayload.ConnectorContext context() {
            return context;
        }

        /** 未授予的理由；已授予时为 null。 */
        public String declineReason() {
            return declineReason;
        }

        /**
         * 撤销回调凭据。不调只是退化成靠 TTL 自然过期，而那个窗口正是这张登记要消灭的东西：
         * 运行早就结束了，泄漏的 token 却仍是该 Agent 全部连接器的读写凭据。
         */
        @Override
        public void close() {
            if (registry != null && closed.compareAndSet(false, true)) {
                registry.end(tenantId, runId);
            }
        }
    }

    /**
     * 只做前置判断，<b>不登记、不签发任何凭据</b>。用于「提交时快速失败」这类场景
     * （评测在接受请求的那一刻就要知道这轮能不能带上 conn_*，而不是烧完 token 才发现）。
     *
     * @return null 表示可以下发；否则是一句人话的不可用理由
     */
    public String preflight(String tenantId, Long userId, Long agentId) {
        ConnectorProperties.Agent cfg = connectorProperties.getAgent();
        if (cfg == null || !cfg.isEnabled()) {
            return "连接器工具代理未启用（connector.agent.enabled=false）";
        }
        // 空 = 这项能力不可用，【绝不回落到任何默认地址】：:8088 是 dev 与 prod 共用的单进程，
        // 给默认值就会让 dev 平面的运行回调到生产网关，在另一套数据上读写且一个字都不报错。
        if (StrUtil.isBlank(cfg.getCallbackBaseUrl())) {
            return "未配置 connector.agent.callback-base-url（空 = 这项能力不可用，不回落默认地址）";
        }
        if (StrUtil.isBlank(tenantId)) {
            return "本轮没有租户上下文";
        }
        if (agentId == null) {
            return "本轮没有 agentId：回调 token 的 aid claim 与 ConnectorGateway 的实时授权都要一个真实 Agent";
        }
        // ★ userId 为 null 必须不下发：AccountStatusFilter 对非数字 user-id 直接放行，
        //   用「无人触发」的系统身份做 id claim 会静默绕过账号 / 企业停用检查——
        //   一个已停用的企业仍能经沙箱查客户库。
        if (userId == null) {
            return "本轮没有 userId：用系统身份做 id claim 会静默绕过账号/企业停用检查";
        }
        if (!hasGrantedConnections(agentId)) {
            return "Agent " + agentId + " 没有被授权任何连接（agent_connection 无行）";
        }
        if (toolSchemas().isEmpty()) {
            return "connector 技能包没给出可用的工具定义（skills/connector/tools.json）";
        }
        // 放在最后：前面几条都是本地判断，只有这一条要打一次网络往返。
        // 版本对不上（含老边车 / 探测失败，一律 0）就不下发——老边车静默忽略 connectorContext，
        // 照常跑完回 success，模型手里一个 conn_* 都没有却照样答。
        int version = sidecarClient.connectorToolsVersion(CAPS_PROBE_TIMEOUT);
        if (version < cfg.getMinSandboxToolsVersion()) {
            return "边车的 connectorToolsVersion=" + version + "，低于要求的 " + cfg.getMinSandboxToolsVersion()
                    + "（老边车会静默忽略 connectorContext）";
        }
        return null;
    }

    /**
     * 判定 + 登记 + 签发。返回的租约<b>必须在 finally 里 close</b>。
     *
     * @param runId        本次运行 id，token 的 {@code rid} claim 与登记键都用它
     * @param wallClockSec 本轮墙钟上限，TTL 按 {@link AdminAuthService#connectorAgentTokenTtlMs(int)} 算
     */
    public Lease open(String tenantId, Long userId, Long agentId, String runId, int wallClockSec) {
        if (StrUtil.isBlank(runId)) {
            return Lease.declined("本轮没有 runId：token 的 rid claim 与吊销登记都以它为键");
        }
        String decline = preflight(tenantId, userId, agentId);
        if (decline != null) {
            return Lease.declined(decline);
        }
        List<SidecarRunPayload.ToolSchema> tools = toolSchemas();
        if (tools.isEmpty()) {
            // preflight 与这里之间技能包被重载过才可能走到，仍要兜住：宁可不下发，不要半截下发。
            return Lease.declined("connector 技能包没给出可用的工具定义（skills/connector/tools.json）");
        }
        ConnectorProperties.Agent cfg = connectorProperties.getAgent();
        // TTL 口径只有 AdminAuthService 那一处：登记与 token 各自重算早晚会算出
        // 「登记先于 token 过期」，表现是运行末尾的回调莫名其妙被拒。
        long ttlMs = AdminAuthService.connectorAgentTokenTtlMs(wallClockSec);
        // 先登记再签发：登记是这枚 token 的吊销开关，反过来会出现「token 已发、还没法吊销」的窗口。
        connectorAgentRunRegistry.begin(tenantId, runId, agentId, ttlMs);

        SidecarRunPayload.ConnectorContext ctx = new SidecarRunPayload.ConnectorContext();
        ctx.setCallbackBaseUrl(cfg.getCallbackBaseUrl());
        ctx.setAccessToken(adminAuthService.mintConnectorAgentToken(userId, tenantId, agentId, runId, ttlMs));
        // 十进制字符串：雪花 id 超出 JS 安全整数，发数字会被边车的 JSON.parse 静默改值。
        ctx.setAgentId(String.valueOf(agentId));
        ctx.setTools(tools);
        return new Lease(ctx, null, connectorAgentRunRegistry, tenantId, runId);
    }

    /**
     * 这个 Agent 有没有被授予连接。与 {@code ConnectorGateway} / {@code SkillRuntimeService} 同一个判据：
     * 授权只认 {@code agent_connection} 的实时行（超管撤销要立刻生效，不能读发布快照）。
     *
     * <p>任何异常按「没绑」处理：判错成「没绑」的代价是本轮少一组工具（对话里看得见、用户会问），
     * 判错成「有」的代价是把一堆必然失败的 conn_* 塞给模型（安静，只表现为答得莫名其妙）。
     */
    private boolean hasGrantedConnections(Long agentId) {
        try {
            return connectorOverviewService.hasGrantedConnections(agentId);
        } catch (Exception e) {
            log.warn("[sandbox] 判定 Agent 连接授权失败，本轮按未授权处理 agentId={}", agentId, e);
            return false;
        }
    }

    /**
     * 下发给边车的七个连接器工具的 name/description/inputSchema，直接取自 connector 技能包
     * <b>已缓存</b>的工具定义（{@code skills/connector/tools.json}），零额外 IO。
     *
     * <p>不在边车那边抄一份：那些 description 里全是行为契约（截断语义、verified 三态、
     * pending_approval 与 forbidden 的区别）。抄一份的结果是两个平面的模型行为半年后静默分叉。
     *
     * <p><b>有一条不合式就整体返回空</b>（调用方据此整段不下发）：边车对 tools[] 是逐字段校验、
     * 不合式直接 400 拒掉整轮 run，挑着下发既救不了那条坏定义，又会让「少了哪个工具」无人察觉。
     */
    private List<SidecarRunPayload.ToolSchema> toolSchemas() {
        SkillPackage pkg = skillPackageLoaderService.findByName(
                skillPackageLoaderService.loadSkillPackages(), CONNECTOR_SKILL_NAME);
        if (pkg == null || pkg.getTools().isEmpty()) {
            return List.of();
        }
        List<SidecarRunPayload.ToolSchema> out = new ArrayList<>(pkg.getTools().size());
        for (SkillToolDefinition def : pkg.getTools()) {
            if (def.getName() == null || !CONNECTOR_TOOL_NAME.matcher(def.getName()).matches()
                    || StrUtil.isBlank(def.getDescription()) || def.getInputSchema() == null) {
                log.warn("[sandbox] connector 工具定义不合边车契约，本轮整组不下发：name={}", def.getName());
                return List.of();
            }
            SidecarRunPayload.ToolSchema schema = new SidecarRunPayload.ToolSchema();
            schema.setName(def.getName());
            schema.setDescription(def.getDescription());
            schema.setInputSchema(def.getInputSchema());
            out.add(schema);
        }
        return out;
    }
}

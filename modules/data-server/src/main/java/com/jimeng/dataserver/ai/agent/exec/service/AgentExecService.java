package com.jimeng.dataserver.ai.agent.exec.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentExecRequest;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.connector.agent.ConnectorAgentContextFactory;
import com.jimeng.dataserver.ai.connector.agent.ConnectorSkillMaterializer;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.skill.service.SkillBundleResolver;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AgentArtifact;
import com.jimeng.persistence.entity.AgentExecRun;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentArtifactMapper;
import com.jimeng.persistence.mapper.AgentExecRunMapper;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 编排一次代码执行 Agent 运行：落运行记录 -> 组 dispatch payload -> 调边车并把 SSE 桥接给前端
 * -> 产物落库 + 回填运行记录。
 *
 * <p>跑在 streamExecutor 线程（{@code MdcAsyncSupport.wrap} 已把 TenantContext / userId 带过来），
 * 因此本方法体内的 DB 写带租户上下文。边车回调（onEvent）跑在 OkHttp 线程，无租户上下文，故产物落库时
 * 临时设置 TenantContext。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecService {

    /**
     * 一次 run 允许铺进沙箱的输入文件条数上限，必须 <= 边车的 LIMIT_MAX_INPUT_FILES（默认 20）。
     * 边车超限的处理是【400 拒掉整个 run】，不是截断 —— 表现为「聊着聊着这轮突然全废」。
     * 自从产物也会登记成会话输入文件（registerArtifactAsInput）后，长会话必然会累积到这个量级，
     * 所以派发前必须自己先裁剪。两边的数字要一起改。
     */
    private static final int MAX_SANDBOX_INPUT_FILES = 20;

    private final SidecarClient sidecarClient;
    private final RunEventTee tee;
    private final RunFinalizer runFinalizer;
    private final RunRegistry runRegistry;
    private final AgentSandboxProperties props;
    private final AgentExecRunMapper runMapper;
    private final AgentInputFileMapper inputFileMapper;
    private final AgentArtifactMapper artifactMapper;
    private final RagMinioStorageService storage;
    private final AgentRuntimeService agentRuntimeService;
    private final com.jimeng.dataserver.ai.connection.ConnectionResolver connectionResolver;
    private final AdminAuthService adminAuthService;
    private final AiModelCallRecordService recordService;
    private final UsageExtractor usageExtractor;
    private final ProviderRegistry providerRegistry;
    private final PermissionResolver permissionResolver;
    private final SkillTenantService skillTenantService;
    private final SkillBundleResolver skillBundleResolver;
    private final ConnectorSkillMaterializer connectorSkillMaterializer;
    private final ConnectorAgentContextFactory connectorAgentContextFactory;

    public void streamExec(AgentExecRequest req, String connectionId, String traceId) {
        String tenantId = TenantContext.get();
        Long userIdL = AdminRequestContext.findUserIdOrNull();
        String userId = userIdL == null ? null : String.valueOf(userIdL);
        long startMs = System.currentTimeMillis();

        // 0. 校验 Agent 可用性：对话端(preview=false)未发布 / 已下架直接拒绝；调试台(preview=true)读实时草稿。
        //    在落运行记录前校验，避免留下永远 RUNNING 的孤儿记录。
        Long agentIdForCheck = parseAgentId(req.getAgentId());
        if (agentIdForCheck != null) {
            try {
                agentRuntimeService.byId(agentIdForCheck, req.isPreview());
            } catch (Exception e) {
                tee.tee(connectionId, "error", new JSONObject().set("message", String.valueOf(e.getMessage())).toString());
                runFinalizer.complete(connectionId);
                return;
            }
        }

        // 1. 落运行记录（RUNNING）
        AgentExecRun run = new AgentExecRun();
        run.setTenantId(tenantId);
        run.setAgentId(req.getAgentId());
        run.setConversationId(req.getConversationId());
        run.setUserId(userId);
        // 显式落属主审计列：本方法跑在 streamExecutor 异步线程，MyMetaObjectHandler 的 create_user 自动填充
        // 只读 RequestContextHolder（请求线程）→ 异步线程拿不到会落空。与 artifact 同款显式 set（userId 来自
        // findUserIdOrNull，已含 MdcAsyncSupport 捎带的 async userId）。归属判定仍以 user_id 为准，这里仅补审计列。
        run.setCreateUser(userId);
        run.setStatus("RUNNING");
        runMapper.insert(run);
        final Long runId = run.getId();

        // 2. 解析输入文件（租户过滤自动生效，跨租户引用拿不到）。
        //    多轮记忆：除本轮显式 fileIds 外，还把【本会话此前上传过的文件】一并铺进 /work，
        //    否则后续轮次（前端不再带 fileIds）沙箱看不到早先发的图片。按文件 id 去重、保持上传顺序。
        Long conversationId = req.getConversationId();
        // 输入文件「按人私有」：成员只挂载自己上传的文件，超管不限。
        // 历史遗留：仅靠租户过滤 -> 成员可引用/扒到他人 fileId 挂进自己沙箱，甚至把他人文件改写归属到本会话。
        boolean canSeeAllFiles = userId != null && permissionResolver.isSuperAdmin();
        Map<Long, AgentInputFile> fileById = new LinkedHashMap<>();
        // 2a. 本轮显式带的文件；顺手把它们登记到当前会话，便于后续轮次按会话回捞。
        if (req.getFileIds() != null) {
            for (Long fid : req.getFileIds()) {
                AgentInputFile f = inputFileMapper.selectById(fid);
                // 跳过非本人上传的文件（超管放行）：不挂载、也不把他人文件改写归属到本会话。
                if (f != null && (canSeeAllFiles || Objects.equals(userId, f.getCreateUser()))) {
                    if (conversationId != null && f.getConversationId() == null) {
                        f.setConversationId(conversationId);
                        inputFileMapper.updateById(f);
                    }
                    fileById.put(f.getId(), f);
                }
            }
        }
        // 2b. 本会话历史上传过的文件（多轮记忆的关键）。成员只回捞自己上传的。
        if (conversationId != null) {
            List<AgentInputFile> prior = inputFileMapper.selectList(
                    new LambdaQueryWrapper<AgentInputFile>()
                            .eq(AgentInputFile::getConversationId, conversationId)
                            .eq(!canSeeAllFiles, AgentInputFile::getCreateUser, userId)
                            .orderByAsc(AgentInputFile::getId));
            for (AgentInputFile f : prior) {
                fileById.putIfAbsent(f.getId(), f);
            }
        }
        // 2c. 本会话【历史产物】也铺回 /work，否则"在你刚生成的那份报表上再加一列"永远做不到：
        //     边车每轮结束会硬删工作区，产物不会自己留下。产物存在 agent_artifact（不与上传混在
        //     agent_input_file 里），所以这里单独查——好处是"上传 vs 产物"天然可区分，下面的
        //     去重与裁剪优先级才有依据，也不用给 agent_input_file 加来源列。
        List<AgentArtifact> priorArtifacts = conversationId == null
                ? List.of()
                : recentConversationArtifacts(conversationId, userId, canSeeAllFiles);

        // 2d. 条数封顶 + 优先级。边车对超过 LIMIT_MAX_INPUT_FILES 的处理是【400 拒掉整轮】而不是
        //     截断，所以必须在派发前自己裁到位。
        //     优先级：本轮显式带的 > 历史上传 > 历史产物；同层内新的优先。
        //     产物排在最后被裁是刻意的：源数据几乎总比中间产物重要 —— 若反过来，一个长会话会把
        //     用户最早上传的源文件挤掉、只留一堆中间图表，"用原始数据重算一遍"就做不到了。
        Set<Long> explicitIds = req.getFileIds() == null ? Set.of() : new HashSet<>(req.getFileIds());
        List<AgentInputFile> allUploads = new ArrayList<>(fileById.values());
        InputSelection selection = selectInputs(allUploads, explicitIds, priorArtifacts, MAX_SANDBOX_INPUT_FILES);
        List<AgentInputFile> keptUploads = selection.uploads();
        List<AgentArtifact> keptArtifacts = selection.artifacts();
        if (keptUploads.size() < allUploads.size() || keptArtifacts.size() < priorArtifacts.size()) {
            // 显式文件本身就超限时裁不下来，此时仍会被边车拒掉；日志要能看出是哪种情况。
            log.info("会话 {} 输入超上限 {}：上传 {}->{}（显式 {}），产物 {}->{}",
                    conversationId, MAX_SANDBOX_INPUT_FILES, allUploads.size(), keptUploads.size(),
                    explicitIds.size(), priorArtifacts.size(), keptArtifacts.size());
        }

        List<SidecarRunPayload.InputFile> inputs = new ArrayList<>();
        for (AgentInputFile f : keptUploads) {
            SidecarRunPayload.InputFile in = new SidecarRunPayload.InputFile();
            in.setObjectName(f.getObjectName());
            in.setFilename(f.getFilename());
            in.setBucket(f.getBucket());
            in.setSizeBytes(f.getSizeBytes());
            inputs.add(in);
        }
        for (AgentArtifact a : keptArtifacts) {
            SidecarRunPayload.InputFile in = new SidecarRunPayload.InputFile();
            in.setObjectName(a.getObjectName());
            in.setFilename(a.getFilename());
            in.setBucket(a.getBucket());
            in.setSizeBytes(a.getSizeBytes());
            inputs.add(in);
        }

        // 3. 组 dispatch payload
        SidecarRunPayload payload = new SidecarRunPayload();
        payload.setRunId(String.valueOf(runId));
        payload.setTenantId(tenantId);
        payload.setUserId(userId);
        payload.setTraceId(traceId);
        payload.setAgentId(req.getAgentId());
        payload.setPrompt(req.getQuery());
        if (req.getHistory() != null) {
            List<SidecarRunPayload.History> hs = new ArrayList<>();
            for (AgentExecRequest.History h : req.getHistory()) {
                SidecarRunPayload.History sh = new SidecarRunPayload.History();
                sh.setRole(h.getRole());
                sh.setContent(h.getContent());
                hs.add(sh);
            }
            payload.setHistory(hs);
        }
        payload.setInputFiles(inputs);
        payload.setArtifactBucket(storage.getBucket());
        // Agent 运行视图只解析一次，下面的人格 / 模型 / RAG 都用它。原先只有 buildRagContext 里
        // 取过一次，人格和模型压根没读——这正是「传了附件就换人格、还换模型」的成因。
        AgentRuntimeView view = resolveViewOrNull(req.getAgentId(), req.isPreview());
        // A+B 统一：若 agent 绑定了知识库，给边车一个短时效 token + ragContext，让它能查知识库
        payload.setRagContext(buildRagContext(view, req.getAgentId(), userId, tenantId));
        // 人格：不传则边车只有平台契约，agent 的定义在这条通道上等于不存在。
        if (view != null && StrUtil.isNotBlank(view.getSystemPrompt())) {
            payload.setSystemPrompt(view.getSystemPrompt());
        }
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(props.getLlm().getBaseUrl());
        llm.setAuthToken(props.getLlm().getAuthToken());
        // 模型优先用 agent 自己的，未指定才回落全局 Nacos 配置。
        // 原先无条件用全局值，于是同一个 agent 在两个平面上跑的是两个模型。
        String agentModel = view == null ? null : view.getDefaultModel();
        llm.setModel(StrUtil.isNotBlank(agentModel) ? agentModel : props.getLlm().getModel());
        llm.setAuthScheme(props.getLlm().getAuthScheme());
        payload.setLlm(llm);
        // 两处【确实传不过去】的能力，显式告警而不是静默丢——静默丢是这套系统反复踩的坑。
        if (view != null && view.getDefaultModelParams() != null && !view.getDefaultModelParams().isEmpty()) {
            log.warn("[sandbox] agent={} 的 modelParams {} 在沙箱平面【无法生效】：Claude Agent SDK 的 Options "
                            + "不接受 temperature/max_tokens 等字段。要按 agent 调参需在 /data/claude/messages 出口侧实现。",
                    req.getAgentId(), view.getDefaultModelParams().keySet());
        }
        if (view != null && view.getKbIds() != null && view.getKbIds().size() > 1) {
            log.warn("[sandbox] agent={} 绑定了 {} 个知识库，但边车 RagContext.kbId 是标量，本次只用第一个（{}）。",
                    req.getAgentId(), view.getKbIds().size(), view.getKbIds().iterator().next());
        }
        // 生图：仅当配置齐全时下发，边车据此注册 generate_image 工具（缺任一项则不启用，沿用原"无生图"行为）。
        AgentSandboxProperties.ImageGen igCfg = props.getImageGen();
        if (igCfg != null && StrUtil.isAllNotBlank(igCfg.getBaseUrl(), igCfg.getAuthToken(), igCfg.getModel())) {
            SidecarRunPayload.ImageGen ig = new SidecarRunPayload.ImageGen();
            ig.setBaseUrl(igCfg.getBaseUrl());
            ig.setAuthToken(igCfg.getAuthToken());
            ig.setModel(igCfg.getModel());
            ig.setAuthScheme(igCfg.getAuthScheme());
            ig.setProvider(igCfg.getProvider());
            ig.setBatchConcurrency(igCfg.getBatchConcurrency());
            payload.setImageGen(ig);
        }
        // 联网检索：仅当配置齐全时下发，边车据此注册 search/fetch 工具（缺则沿用"无联网"行为）。
        AgentSandboxProperties.WebSearch wsCfg = props.getWebSearch();
        if (wsCfg != null && StrUtil.isAllNotBlank(wsCfg.getBaseUrl(), wsCfg.getAuthToken())) {
            SidecarRunPayload.WebSearch ws = new SidecarRunPayload.WebSearch();
            ws.setBaseUrl(wsCfg.getBaseUrl());
            ws.setAuthToken(wsCfg.getAuthToken());
            ws.setProvider(wsCfg.getProvider());
            ws.setMaxResults(wsCfg.getMaxResults());
            ws.setAuthScheme(wsCfg.getAuthScheme());
            payload.setWebSearch(ws);
        }
        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(props.getWallClockSec());
        limits.setMaxTurns(props.getMaxTurns());
        limits.setMaxBudgetUsd(props.getMaxBudgetUsd());
        payload.setLimits(limits);
        // DOER skills：把租户可见的活跃 DOER skill bundle 列出并下发给边车（边车物化到 .claude/skills）。
        // 外部连接：按 Agent 授权下发。技能脚本用 $JM_CONN_BASE/<name>/... 调用，
        // 真实地址与凭据只到边车+egress 代理为止，不进容器。
        List<SidecarRunPayload.Conn> conns =
                connectionResolver.resolveForAgent(view == null ? null : view.getAgentId());
        if (!conns.isEmpty()) {
            payload.setConnections(conns);
        }
        // 按 Agent 绑定收窄：view 为空（无 agentId）时传 null，保持旧的"租户内全量"行为。
        List<AiSkill> doerSkills = skillTenantService.listActiveDoerForRun(
                tenantId, AdminRequestContext.findUserIdOrNull(),
                view == null ? null : view.getAllowedSkillIds());
        if (!doerSkills.isEmpty()) {
            payload.setSkills(skillBundleResolver.resolve(doerSkills));
        }

        // 3b. 连接器工具代理：让沙箱平面也有 conn_*（数据库类连接器只有这一条路，egress 代理那条
        //     只管 HTTP）。工具定义在沙箱、执行留在宿主，经 /data/internal/connector-agent/** 回调。
        //
        //     判定 + 登记 + 签发全在 ConnectorAgentContextFactory 里（评测平面共用同一份，见该类注释）。
        //     租约未授予时 close() 是空操作，所以这里不再自己记 connectorRegistered 布尔——
        //     那个布尔正是过去最容易漏掉的一行。
        final ConnectorAgentContextFactory.Lease connectorLease = connectorAgentContextFactory.open(
                tenantId, userIdL, view == null ? null : view.getAgentId(),
                String.valueOf(runId), props.getWallClockSec());
        if (connectorLease.granted()) {
            payload.setConnectorContext(connectorLease.context());
            // SKILL.md：工具描述之外还有一整套用法（取数顺序、写操作必须先查清影响行）。
            // 物化失败返回 null，此时只是少了这份说明，连接器工具照常下发。
            appendSkill(payload, connectorSkillMaterializer.materialize());
            // 只记 runId / agentId / 工具数——token 绝不进任何日志。
            log.info("[sandbox] 本轮下发连接器工具代理 runId={} agentId={} 工具数={}",
                    runId, connectorLease.context().getAgentId(),
                    connectorLease.context().getTools().size());
        } else {
            // debug 而不是 info：绝大多数运行（没绑连接、总开关没开）都会走到这里，info 会把日志刷爆。
            log.debug("[sandbox] 本轮不下发连接器工具代理 runId={}：{}", runId, connectorLease.declineReason());
        }

        // 4. 桥接边车 SSE
        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> artifactEvents = Collections.synchronizedList(new ArrayList<>());
        final String[] summaryHolder = new String[1];
        final String[] streamError = new String[1];

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (type == null) {
                    return;
                }
                if ("artifact".equals(type)) {
                    // 产物落库（临时设置租户上下文）并改写 downloadUrl 后再转发给前端
                    String enriched = registerArtifactAndEnrich(tenantId, runId, userId, data);
                    tee.tee(connectionId, "artifact", enriched);
                    artifactEvents.add(data);
                    return;
                }
                if ("summary".equals(type)) {
                    summaryHolder[0] = data;
                }
                tee.tee(connectionId, type, data);
            }

            @Override
            public void onClosed(EventSource es) {
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                String msg = t != null ? t.getMessage()
                        : ("sidecar http " + (response != null ? response.code() : "?"));
                streamError[0] = msg;
                log.error("sidecar 流式失败 runId={} err={}", runId, msg);
                tee.tee(connectionId, "error", new JSONObject().set("message", msg).toString());
                latch.countDown();
            }
        };

        try {
            EventSource upstream = sidecarClient.run(payload, listener);
            // 发布上游句柄，使「停止」能关闭到边车的上游请求（边车据此 docker-kill）。无 run 句柄=直连 /agent/exec，忽略。
            RunHandle handle = runRegistry.get(connectionId);
            if (handle != null) handle.setUpstream(upstream);
            boolean done = latch.await(props.getWallClockSec() + 30L, TimeUnit.SECONDS);
            if (!done) {
                streamError[0] = "timeout";
                tee.tee(connectionId, "error", new JSONObject().set("message", "timeout").toString());
            }
        } catch (Exception e) {
            streamError[0] = e.getMessage();
            log.error("调用边车异常 runId={}", runId, e);
            tee.tee(connectionId, "error", new JSONObject().set("message", String.valueOf(e.getMessage())).toString());
        } finally {
            // ★ 撤销连接器回调凭据。正常结束、超时被杀、异常中断三条路都会走到这里；
            //   排在回填之前，避免回填抛出时把撤销一起跳过。
            //   不调只是退化成靠 TTL 自然过期，而那个窗口正是这张登记要消灭的东西：
            //   运行早就结束了，泄漏的 token 却仍是该 Agent 全部连接器的读写凭据。
            connectorLease.close();
            // 仍在 streamExecutor 线程，TenantContext 还在，可安全回填
            persistRunResult(run, summaryHolder[0], streamError[0], artifactEvents.size(),
                    System.currentTimeMillis() - startMs);
            runFinalizer.complete(connectionId);
        }
    }

    /** 把一个 SkillRef 追加进 payload.skills；ref 为 null 则原样不动（doerSkills 为空时也要设上）。 */
    private static void appendSkill(SidecarRunPayload payload, SidecarRunPayload.SkillRef ref) {
        if (ref == null) {
            return;
        }
        List<SidecarRunPayload.SkillRef> skills = payload.getSkills() == null
                ? new ArrayList<>()
                : new ArrayList<>(payload.getSkills());
        skills.add(ref);
        payload.setSkills(skills);
    }

    /** 解析 "123" 形式的 agentId，非法 / 空返回 null。 */
    private Long parseAgentId(String agentIdStr) {
        if (StrUtil.isBlank(agentIdStr)) {
            return null;
        }
        try {
            return Long.parseLong(agentIdStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析 agent 运行视图；agentId 缺失 / 解析失败一律返回 null，调用方各自回落。 */
    private AgentRuntimeView resolveViewOrNull(String agentIdStr, boolean preview) {
        Long agentId = parseAgentId(agentIdStr);
        if (agentId == null) {
            return null;
        }
        try {
            return agentRuntimeService.byId(agentId, preview);
        } catch (Exception e) {
            log.warn("[sandbox] 解析 agent 运行视图失败 agentId={}: {}", agentIdStr, e.getMessage());
            return null;
        }
    }

    /** 解析 agent 绑定的知识库 + 铸短时效回调 token；无绑定 / 无用户 / 解析失败则返回 null（边车不带 RAG 工具）。 */
    private SidecarRunPayload.RagContext buildRagContext(AgentRuntimeView view, String agentIdStr, String userId, String tenantId) {
        if (userId == null) {
            return null;
        }
        try {
            if (view == null || view.getKbIds() == null || view.getKbIds().isEmpty()) {
                return null;
            }
            SidecarRunPayload.RagContext ctx = new SidecarRunPayload.RagContext();
            ctx.setKbId(String.valueOf(view.getKbIds().iterator().next()));
            ctx.setTopK(view.getKbTopK());
            ctx.setRerank(view.getKbRerank());
            long ttlMs = (props.getWallClockSec() + 120L) * 1000L;
            ctx.setAccessToken(adminAuthService.mintInternalToken(userId, tenantId, ttlMs));
            return ctx;
        } catch (Exception e) {
            log.warn("解析 agent 知识库失败 agentId={}: {}", agentIdStr, e.getMessage());
            return null;
        }
    }

    /** 在 OkHttp 回调线程把产物落库（临时设置租户上下文），返回改写了 downloadUrl 的事件 JSON。 */
    private String registerArtifactAndEnrich(String tenantId, Long runId, String userId, String data) {
        try {
            JSONObject j = JSONUtil.parseObj(data);
            AgentArtifact a = new AgentArtifact();
            a.setTenantId(tenantId);
            a.setRunId(runId);
            // 回调线程无 user-id 头，MyMetaObjectHandler 填不到 create_user，这里显式补上发起人，
            // 与下载端「按人私有」判属主对齐（下载端以父 run.user_id 为准，这里是双保险）。
            a.setCreateUser(userId);
            a.setBucket(j.getStr("bucket"));
            a.setObjectName(j.getStr("objectName"));
            a.setFilename(j.getStr("filename"));
            a.setContentType(j.getStr("contentType"));
            a.setSizeBytes(j.getLong("sizeBytes", null));
            String prev = TenantContext.get();
            try {
                TenantContext.set(tenantId);
                artifactMapper.insert(a);
            } finally {
                if (prev != null) {
                    TenantContext.set(prev);
                } else {
                    TenantContext.clear();
                }
            }
            JSONObject out = new JSONObject();
            // 雪花 Long 超出 JS 安全整数范围，必须以字符串下发，否则前端 JSON.parse 丢精度 -> 下载 id 对不上。
            out.set("artifactId", String.valueOf(a.getId()));
            out.set("filename", a.getFilename());
            out.set("contentType", a.getContentType());
            out.set("sizeBytes", a.getSizeBytes() == null ? null : String.valueOf(a.getSizeBytes()));
            out.set("downloadUrl", "/data/agent/artifacts/" + a.getId() + "/download");
            return out.toString();
        } catch (Exception e) {
            log.warn("产物落库失败 runId={} err={}", runId, e.getMessage());
            return data;
        }
    }

    /** 选中的输入：上传与产物分开，调用方各自转成 payload。 */
    record InputSelection(List<AgentInputFile> uploads, List<AgentArtifact> artifacts) {
    }

    /**
     * 在总数上限内挑选本轮要铺进沙箱的输入，纯函数、可单测（AgentExecInputSelectionTest）。
     *
     * 边车对超过 LIMIT_MAX_INPUT_FILES 的处理是【400 拒掉整轮】而不是截断，所以必须在派发前裁到位。
     * 优先级：本轮显式带的上传 > 其它历史上传（新->旧）> 历史产物（新->旧）。
     *
     * 产物排在最后被裁是刻意的：源数据几乎总比中间产物重要。若按 id 一刀切"丢最旧的"，产物 id 更大
     * 会导致用户最早上传的源文件先被挤掉、只剩一堆中间图表，"用原始数据重算一遍"就做不到了。
     *
     * @param allUploads    上传候选，按 id 升序（旧->新），显式项已在其中
     * @param explicitIds   本轮显式带的 fileId，必留
     * @param artifactsNewestFirst 历史产物，按新->旧且已按文件名去重
     * @return 两份名单，均已回到"先后顺序"（旧->新），便于模型按时间理解
     */
    static InputSelection selectInputs(List<AgentInputFile> allUploads, Set<Long> explicitIds,
                                       List<AgentArtifact> artifactsNewestFirst, int max) {
        List<AgentInputFile> keptUploads = new ArrayList<>();
        List<AgentInputFile> others = new ArrayList<>();
        for (AgentInputFile f : allUploads) {
            if (explicitIds.contains(f.getId())) {
                keptUploads.add(f); // 用户刚点的，必留（哪怕它本身就超限）
            } else {
                others.add(f);
            }
        }
        Collections.reverse(others); // 新->旧，填不下时丢的是最旧的
        for (AgentInputFile f : others) {
            if (keptUploads.size() >= max) {
                break;
            }
            keptUploads.add(f);
        }
        keptUploads.sort(Comparator.comparing(AgentInputFile::getId)); // 回到上传先后顺序
        int budget = Math.max(0, max - keptUploads.size());
        List<AgentArtifact> keptArtifacts = new ArrayList<>(
                artifactsNewestFirst.subList(0, Math.min(budget, artifactsNewestFirst.size())));
        Collections.reverse(keptArtifacts); // 回到生成先后顺序
        return new InputSelection(keptUploads, keptArtifacts);
    }

    /**
     * 取本会话历史产物，按【新 -> 旧】返回，并按文件名去重只留最新的那份。
     *
     * 为什么要查这张表：边车每轮结束会硬删工作区，产物不会自己留下；不铺回去，"在你刚生成的那份
     * 报表上再加一列"就永远做不到（用户上传的文件有 2b 回捞，产物此前没有对应链路）。
     *
     * 为什么按文件名去重：agent 每轮往往生成同名文件（report.xlsx 改一版再存一次）。全带上的话，
     * 第 5 轮工作区里会同时躺着 report.xlsx / report-2.xlsx / report-3.xlsx（边车侧同名会消歧），
     * 全是同一份东西的不同版本，模型很可能读到旧的那份并据此作答。只留最新的才符合"那份报表"的语义。
     *
     * 「按人私有」与 2b 对齐：产物归属跟随其父 run 的 user_id，成员只回捞自己那些 run 产出的。
     */
    private List<AgentArtifact> recentConversationArtifacts(Long conversationId, String userId,
                                                            boolean canSeeAll) {
        try {
            List<AgentExecRun> runs = runMapper.selectList(
                    new LambdaQueryWrapper<AgentExecRun>()
                            .select(AgentExecRun::getId)
                            .eq(AgentExecRun::getConversationId, conversationId)
                            .eq(!canSeeAll, AgentExecRun::getUserId, userId)
                            // 只看最近若干轮：长会话里 run 数会很多，IN 列表不能无界增长。
                            // 取值远大于 MAX_SANDBOX_INPUT_FILES，保证候选集充足。
                            .orderByDesc(AgentExecRun::getId)
                            .last("limit " + (MAX_SANDBOX_INPUT_FILES * 3)));
            if (runs.isEmpty()) {
                return List.of();
            }
            List<Long> runIds = new ArrayList<>();
            for (AgentExecRun r : runs) {
                runIds.add(r.getId());
            }
            List<AgentArtifact> all = artifactMapper.selectList(
                    new LambdaQueryWrapper<AgentArtifact>()
                            .in(AgentArtifact::getRunId, runIds)
                            .orderByDesc(AgentArtifact::getId));
            // 已是新->旧，putIfAbsent 天然保留每个文件名最新的那条。
            Map<String, AgentArtifact> newestByName = new LinkedHashMap<>();
            for (AgentArtifact a : all) {
                if (a.getFilename() != null) {
                    newestByName.putIfAbsent(a.getFilename(), a);
                }
            }
            return new ArrayList<>(newestByName.values());
        } catch (Exception e) {
            // 回捞产物是增强能力，查询出问题不该让整轮 run 起不来 —— 退化成"只有上传文件"。
            log.warn("回捞会话产物失败 conversationId={} err={}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private void persistRunResult(AgentExecRun run, String summaryJson, String streamError,
                                  int artifactCount, long latencyMs) {
        NormalizedUsage usage = null;
        try {
            if (summaryJson != null) {
                JSONObject s = JSONUtil.parseObj(summaryJson);
                JSONObject usageJson = s.getJSONObject("usage");
                if (usageJson != null) {
                    // 与对话/RAG 链路同一套归一化（UsageExtractor 兼容 OpenAI / Anthropic 两套 usage 命名）。
                    usage = usageExtractor.extract(usageJson);
                    run.setInputTokens(toLong(usage.getInputTokens()));
                    run.setOutputTokens(toLong(usage.getOutputTokens()));
                    // Anthropic usage 无 total，回退为 input+output，保持与 ai_model_call_log 同口径。
                    if (usage.getTotalTokens() != null) {
                        run.setTotalTokens(usage.getTotalTokens().longValue());
                    } else if (usage.getInputTokens() != null || usage.getOutputTokens() != null) {
                        int in = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
                        int out = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
                        run.setTotalTokens((long) (in + out));
                    }
                }
                run.setToolRounds(s.getInt("toolRounds", null));
                if (s.getStr("error") != null) {
                    run.setErrorMsg(s.getStr("error"));
                }
                String status = s.getStr("status");
                run.setStatus("success".equalsIgnoreCase(status) ? "SUCCESS" : "FAILED");
            }
            if (streamError != null) {
                run.setStatus("FAILED");
                run.setErrorMsg(streamError);
            }
            if (run.getStatus() == null || "RUNNING".equals(run.getStatus())) {
                run.setStatus(streamError == null ? "SUCCESS" : "FAILED");
            }
            run.setArtifactCount(artifactCount);
            run.setElapsedMs(latencyMs);
            runMapper.updateById(run);
        } catch (Exception e) {
            log.warn("回填运行记录失败 runId={} err={}", run.getId(), e.getMessage());
        }

        // 用量记账：写进 ai_model_call_log（与 RAG rerank 等同一套，接入用量统计）。
        // tenant/user 由 recordComputedCall 从 TenantContext / 异步 userId 自动带出。
        if (usage != null && (usage.getInputTokens() != null || usage.getOutputTokens() != null)) {
            try {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("biz_type", "agent_exec");
                note.put("run_id", String.valueOf(run.getId()));
                note.put("artifact_count", artifactCount);
                recordService.recordComputedCall(
                        providerRegistry.activeProvider(),
                        "sandbox:agent-exec",
                        props.getLlm().getModel(),
                        "agent_exec",
                        usage,
                        streamError == null ? 200 : 500,
                        (int) Math.min(latencyMs, Integer.MAX_VALUE),
                        note,
                        parseAgentId(run.getAgentId()));
            } catch (Exception e) {
                log.warn("用量记账失败 runId={} err={}", run.getId(), e.getMessage());
            }
        }
    }

    private static Long toLong(Integer v) {
        return v == null ? null : v.longValue();
    }
}

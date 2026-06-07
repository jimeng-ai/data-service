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
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.run.RunHandle;
import com.jimeng.dataserver.ai.run.RunRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AdminAuthService adminAuthService;
    private final AiModelCallRecordService recordService;
    private final UsageExtractor usageExtractor;
    private final ProviderRegistry providerRegistry;

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
        run.setStatus("RUNNING");
        runMapper.insert(run);
        final Long runId = run.getId();

        // 2. 解析输入文件（租户过滤自动生效，跨租户引用拿不到）。
        //    多轮记忆：除本轮显式 fileIds 外，还把【本会话此前上传过的文件】一并铺进 /work，
        //    否则后续轮次（前端不再带 fileIds）沙箱看不到早先发的图片。按文件 id 去重、保持上传顺序。
        Long conversationId = req.getConversationId();
        Map<Long, AgentInputFile> fileById = new LinkedHashMap<>();
        // 2a. 本轮显式带的文件；顺手把它们登记到当前会话，便于后续轮次按会话回捞。
        if (req.getFileIds() != null) {
            for (Long fid : req.getFileIds()) {
                AgentInputFile f = inputFileMapper.selectById(fid);
                if (f != null) {
                    if (conversationId != null && f.getConversationId() == null) {
                        f.setConversationId(conversationId);
                        inputFileMapper.updateById(f);
                    }
                    fileById.put(f.getId(), f);
                }
            }
        }
        // 2b. 本会话历史上传过的文件（多轮记忆的关键）。
        if (conversationId != null) {
            List<AgentInputFile> prior = inputFileMapper.selectList(
                    new LambdaQueryWrapper<AgentInputFile>()
                            .eq(AgentInputFile::getConversationId, conversationId)
                            .orderByAsc(AgentInputFile::getId));
            for (AgentInputFile f : prior) {
                fileById.putIfAbsent(f.getId(), f);
            }
        }
        List<SidecarRunPayload.InputFile> inputs = new ArrayList<>();
        for (AgentInputFile f : fileById.values()) {
            SidecarRunPayload.InputFile in = new SidecarRunPayload.InputFile();
            in.setObjectName(f.getObjectName());
            in.setFilename(f.getFilename());
            in.setBucket(f.getBucket());
            in.setSizeBytes(f.getSizeBytes());
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
        // A+B 统一：若 agent 绑定了知识库，给边车一个短时效 token + ragContext，让它能查知识库
        payload.setRagContext(buildRagContext(req.getAgentId(), userId, tenantId, req.isPreview()));
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(props.getLlm().getBaseUrl());
        llm.setAuthToken(props.getLlm().getAuthToken());
        llm.setModel(props.getLlm().getModel());
        llm.setAuthScheme(props.getLlm().getAuthScheme());
        payload.setLlm(llm);
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
        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(props.getWallClockSec());
        limits.setMaxTurns(props.getMaxTurns());
        limits.setMaxBudgetUsd(props.getMaxBudgetUsd());
        payload.setLimits(limits);

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
                    String enriched = registerArtifactAndEnrich(tenantId, runId, data);
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
            // 仍在 streamExecutor 线程，TenantContext 还在，可安全回填
            persistRunResult(run, summaryHolder[0], streamError[0], artifactEvents.size(),
                    System.currentTimeMillis() - startMs);
            runFinalizer.complete(connectionId);
        }
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

    /** 解析 agent 绑定的知识库 + 铸短时效回调 token；无绑定 / 无用户 / 解析失败则返回 null（边车不带 RAG 工具）。 */
    private SidecarRunPayload.RagContext buildRagContext(String agentIdStr, String userId, String tenantId, boolean preview) {
        if (userId == null) {
            return null;
        }
        Long agentId = parseAgentId(agentIdStr);
        if (agentId == null) {
            return null;
        }
        try {
            AgentRuntimeView view = agentRuntimeService.byId(agentId, preview);
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
    private String registerArtifactAndEnrich(String tenantId, Long runId, String data) {
        try {
            JSONObject j = JSONUtil.parseObj(data);
            AgentArtifact a = new AgentArtifact();
            a.setTenantId(tenantId);
            a.setRunId(runId);
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

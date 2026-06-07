package com.jimeng.dataserver.ai.billing;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.billing.support.RequestContextUtil;
import com.jimeng.dataserver.web.MdcContextFilter;
import com.jimeng.persistence.entity.AiTrace;
import com.jimeng.persistence.entity.AiTraceStep;
import com.jimeng.persistence.mapper.AiTraceMapper;
import com.jimeng.persistence.mapper.AiTraceStepMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全链路调用日志 Trace 埋点入口。
 *
 * <p>每记录一个步骤（{@link #recordStep}）：插入一行 {@code ai_trace_step}，并把同一条
 * {@code trace_id} 的头表 {@code ai_trace} 增量累计（step_count / 耗时 / token / 成本，
 * 推进 end_time，状态升级 SUCCESS→WARN→ERROR）。头表是列表与概览统计的数据源，无需"收尾"钩子。
 *
 * <p>trace_id 取自 MDC（{@link MdcContextFilter#MDC_TRACE_ID}，流式线程由 MdcAsyncSupport 透传），
 * tenant / user / agent 取自 ThreadLocal 上下文。整体<b>尽力而为</b>：任何异常只记日志、不影响主流程。
 *
 * <p>步骤类型见 {@link StepType}；步骤状态见 {@link Status}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TraceRecorder {

    /** 步骤类型常量（与 ai_trace_step.step_type 一致）。 */
    public static final class StepType {
        public static final String LLM = "LLM";
        public static final String KB_SEARCH = "KB_SEARCH";
        public static final String RERANK = "RERANK";
        public static final String TOOL_CALL = "TOOL_CALL";
        public static final String PLUGIN_TRIGGER = "PLUGIN_TRIGGER";
        private StepType() {}
    }

    /** 状态常量。 */
    public static final class Status {
        public static final String SUCCESS = "SUCCESS";
        public static final String WARN = "WARN";
        public static final String ERROR = "ERROR";
        private Status() {}
    }

    private static final int MAX_ERROR_MSG_LEN = 1000;
    private static final int MAX_USER_MSG_LEN = 2000;

    private final AiTraceMapper aiTraceMapper;
    private final AiTraceStepMapper aiTraceStepMapper;

    // ============================ 用户消息（trace 头表，仅记录一次） ============================

    /**
     * 记录本次 trace 的用户输入消息。应在首个步骤、且对话循环追加 tool_result 之前调用，
     * 以拿到真正的用户问题；已写入后不再覆盖。尽力而为：任何异常只记 warn、不影响主流程。
     */
    public void recordUserMessage(String message) {
        try {
            if (StrUtil.isBlank(message)) {
                return;
            }
            String traceId = currentTraceId();
            if (StrUtil.isBlank(traceId)) {
                return;
            }
            Date now = new Date();
            AiTrace header = loadOrInitHeader(traceId, currentTenantId(), currentUserId(), now);
            if (StrUtil.isNotBlank(header.getUserMessage())) {
                return; // 已有，不覆盖
            }
            AiTrace update = new AiTrace();
            update.setId(header.getId());
            update.setUserMessage(truncate(message, MAX_USER_MSG_LEN));
            aiTraceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("记录 trace 用户消息失败（忽略，不影响主流程）: {}", e.getMessage());
        }
    }

    // ============================ 类型化埋点方法 ============================

    /** LLM 推理步骤。title 由调用方按阶段给（如「推理·决定调用工具」/「推理·生成回答」/「推理·总结输出」）。 */
    public void recordLlm(Long refLogId, String title, String model,
                          Integer inputTokens, Integer outputTokens, BigDecimal costUsd,
                          long durationMs, boolean ok, String errorMsg) {
        AiTraceStep step = newStep(StepType.LLM, title);
        step.setSubTitle(model);
        step.setModel(model);
        step.setRefLogId(refLogId);
        step.setInputTokens(inputTokens);
        step.setOutputTokens(outputTokens);
        step.setTotalTokens(sum(inputTokens, outputTokens));
        step.setCostUsd(costUsd);
        applyResult(step, durationMs, ok, errorMsg);
        recordStep(step);
    }

    /** 知识库检索步骤（ES 混合检索）。 */
    public void recordKbSearch(String kbName, int topK, int hits, long durationMs) {
        AiTraceStep step = newStep(StepType.KB_SEARCH, "知识库检索" + (StrUtil.isBlank(kbName) ? "" : "·" + kbName));
        // topK 是 rerank 前的 RRF 召回候选窗口（非工具入参 top_k）；措辞与之区分，避免混淆。
        step.setSubTitle("召回候选 " + topK + " · 命中 " + hits + " 个分片");
        step.setMetadata(json("kbName", kbName, "topK", topK, "hits", hits));
        applyResult(step, durationMs, true, null);
        recordStep(step);
    }

    /** Re-rank 精排步骤。 */
    public void recordRerank(String model, int kept, int candidates, long durationMs) {
        AiTraceStep step = newStep(StepType.RERANK, "Re-rank" + (StrUtil.isBlank(model) ? "" : "·" + model));
        step.setSubTitle("保留 " + kept + " / " + candidates + " 个分片");
        step.setModel(model);
        step.setMetadata(json("model", model, "kept", kept, "candidates", candidates));
        applyResult(step, durationMs, true, null);
        recordStep(step);
    }

    /** 工具调用步骤（非插件类工具，如内置 skill / SQL 查询）。 */
    public void recordTool(String toolName, String target, String summary,
                           long durationMs, boolean ok, String errorMsg) {
        AiTraceStep step = newStep(StepType.TOOL_CALL, "工具调用" + (StrUtil.isBlank(toolName) ? "" : "·" + toolName));
        step.setSubTitle(StrUtil.isBlank(target) ? summary : (StrUtil.isBlank(summary) ? target : target + " · " + summary));
        step.setMetadata(json("toolName", toolName, "target", target, "summary", summary));
        applyResult(step, durationMs, ok, errorMsg);
        recordStep(step);
    }

    /** 插件触发步骤（DB 配置的 HTTP 插件）。 */
    public void recordPlugin(String pluginName, String detail,
                             long durationMs, boolean ok, String errorMsg) {
        AiTraceStep step = newStep(StepType.PLUGIN_TRIGGER, "插件触发" + (StrUtil.isBlank(pluginName) ? "" : "·" + pluginName));
        step.setSubTitle(detail);
        step.setMetadata(json("pluginName", pluginName, "detail", detail));
        applyResult(step, durationMs, ok, errorMsg);
        recordStep(step);
    }

    // ============================ 核心：写步骤 + 累计头表 ============================

    /**
     * 写一行步骤并把头表增量累计。尽力而为：任何异常只记 warn，不向上抛。
     */
    private void recordStep(AiTraceStep step) {
        try {
            String traceId = currentTraceId();
            if (StrUtil.isBlank(traceId)) {
                log.debug("无 trace_id，跳过 trace 步骤记录: {}", step.getTitle());
                return;
            }
            String tenantId = currentTenantId();
            String userId = currentUserId();
            Date now = new Date();

            AiTrace header = loadOrInitHeader(traceId, tenantId, userId, now);

            step.setTraceId(traceId);
            step.setTenantId(tenantId);
            step.setUserId(userId);
            step.setStepIndex(header.getStepCount() == null ? 0 : header.getStepCount());
            if (step.getStepTime() == null) {
                step.setStepTime(now);
            }
            aiTraceStepMapper.insert(step);

            accumulate(header, step, now);
        } catch (Exception e) {
            log.warn("记录 trace 步骤失败（忽略，不影响主流程）: {}", e.getMessage());
        }
    }

    private AiTrace loadOrInitHeader(String traceId, String tenantId, String userId, Date now) {
        AiTrace header = aiTraceMapper.selectOne(
                new LambdaQueryWrapper<AiTrace>().eq(AiTrace::getTraceId, traceId).last("limit 1"));
        if (header != null) {
            return header;
        }
        header = new AiTrace();
        header.setTraceId(traceId);
        header.setTenantId(tenantId);
        header.setUserId(userId);
        header.setStatus(Status.SUCCESS);
        header.setStepCount(0);
        header.setTotalLatencyMs(0L);
        header.setTotalInputTokens(0L);
        header.setTotalOutputTokens(0L);
        header.setTotalTokens(0L);
        header.setTotalCostUsd(BigDecimal.ZERO);
        header.setStartTime(now);
        header.setEndTime(now);
        // Agent 维度：优先取运行时视图（含名称），回退 AgentIdContext
        AgentRuntimeView view = AgentContext.isSet() ? AgentContext.get() : null;
        if (view != null) {
            header.setAgentId(view.getAgentId());
            header.setAgentName(view.getName());
        } else {
            String agentIdStr = AgentIdContext.get();
            if (StrUtil.isNotBlank(agentIdStr)) {
                try {
                    header.setAgentId(Long.valueOf(agentIdStr.trim()));
                } catch (NumberFormatException ignored) {
                    // ignore non-numeric agent id
                }
            }
        }
        aiTraceMapper.insert(header);
        return header;
    }

    private void accumulate(AiTrace header, AiTraceStep step, Date now) {
        AiTrace update = new AiTrace();
        update.setId(header.getId());
        update.setStepCount((header.getStepCount() == null ? 0 : header.getStepCount()) + 1);
        update.setTotalLatencyMs(nz(header.getTotalLatencyMs()) + (step.getDurationMs() == null ? 0 : step.getDurationMs()));
        update.setTotalInputTokens(nz(header.getTotalInputTokens()) + nz(step.getInputTokens()));
        update.setTotalOutputTokens(nz(header.getTotalOutputTokens()) + nz(step.getOutputTokens()));
        update.setTotalTokens(nz(header.getTotalTokens()) + nz(step.getTotalTokens()));
        BigDecimal cost = header.getTotalCostUsd() == null ? BigDecimal.ZERO : header.getTotalCostUsd();
        if (step.getCostUsd() != null) {
            cost = cost.add(step.getCostUsd());
        }
        update.setTotalCostUsd(cost);
        update.setEndTime(now);
        update.setStatus(escalate(header.getStatus(), step.getStatus()));
        if (Status.ERROR.equals(step.getStatus()) && StrUtil.isNotBlank(step.getErrorMsg())) {
            update.setErrorMsg(step.getErrorMsg());
        }
        // 若头表 Agent 维度初始为空但此刻可解析，则补上（首步无 AgentContext、后续步骤才有的情况）
        if (header.getAgentId() == null) {
            AgentRuntimeView view = AgentContext.isSet() ? AgentContext.get() : null;
            if (view != null) {
                update.setAgentId(view.getAgentId());
                update.setAgentName(view.getName());
            }
        }
        aiTraceMapper.updateById(update);
    }

    // ============================ 上下文与工具 ============================

    private String currentTraceId() {
        String fromMdc = MDC.get(MdcContextFilter.MDC_TRACE_ID);
        if (StrUtil.isNotBlank(fromMdc)) {
            return fromMdc;
        }
        HttpServletRequest request = RequestContextUtil.currentRequest();
        String h = RequestContextUtil.getHeader(request, "trace-id");
        return StrUtil.isNotBlank(h) ? h : RequestContextUtil.getHeader(request, "x-trace-id");
    }

    private String currentTenantId() {
        String t = TenantContext.get();
        if (StrUtil.isNotBlank(t)) {
            return t;
        }
        HttpServletRequest request = RequestContextUtil.currentRequest();
        String h = RequestContextUtil.getHeader(request, "tenant-id");
        return StrUtil.isNotBlank(h) ? h : RequestContextUtil.getHeader(request, "x-tenant-id");
    }

    private String currentUserId() {
        String u = MDC.get(MdcContextFilter.MDC_USER_ID);
        if (StrUtil.isNotBlank(u)) {
            return u;
        }
        HttpServletRequest request = RequestContextUtil.currentRequest();
        String h = RequestContextUtil.getHeader(request, "user-id");
        return StrUtil.isNotBlank(h) ? h : RequestContextUtil.getHeader(request, "x-user-id");
    }

    private AiTraceStep newStep(String type, String title) {
        AiTraceStep step = new AiTraceStep();
        step.setStepType(type);
        step.setTitle(title);
        step.setStatus(Status.SUCCESS);
        return step;
    }

    private void applyResult(AiTraceStep step, long durationMs, boolean ok, String errorMsg) {
        step.setDurationMs((int) Math.max(0, Math.min(durationMs, Integer.MAX_VALUE)));
        if (!ok) {
            step.setStatus(Status.ERROR);
            step.setErrorMsg(truncate(errorMsg));
        }
    }

    /** 状态升级：ERROR > WARN > SUCCESS。 */
    private String escalate(String current, String incoming) {
        int c = rank(current);
        int i = rank(incoming);
        return i > c ? incoming : (current == null ? Status.SUCCESS : current);
    }

    private int rank(String status) {
        if (Status.ERROR.equals(status)) {
            return 2;
        }
        if (Status.WARN.equals(status)) {
            return 1;
        }
        return 0;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v;
    }

    private static Integer sum(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    private String truncate(String s) {
        return truncate(s, MAX_ERROR_MSG_LEN);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 把若干 key-value 拼成紧凑 JSON 字符串，跳过 null 值。 */
    private String json(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null && !(v instanceof String s && s.isBlank())) {
                m.put(String.valueOf(kv[i]), v);
            }
        }
        return m.isEmpty() ? null : JSONUtil.toJsonStr(m);
    }
}

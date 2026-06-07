package com.jimeng.dataserver.ai.trace.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.dataserver.ai.trace.dto.TraceReplay;
import com.jimeng.dataserver.ai.trace.dto.TraceReplayStep;
import com.jimeng.dataserver.ai.trace.dto.TraceReplayTurn;
import com.jimeng.persistence.entity.AiModelCallContent;
import com.jimeng.persistence.entity.AiTrace;
import com.jimeng.persistence.entity.AiTraceStep;
import com.jimeng.persistence.mapper.AiModelCallContentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 调用日志「可视化回放」：只读重现一条 trace 的执行过程，不重新调用模型。
 *
 * <p>核心：最后一次 LLM 调用的 {@code req_body} 已含整段对话历史（所有工具轮次的 tool_use /
 * tool_result），其 {@code stream_events}（流式拼好的 message，回退 resp_body）是最终回答。
 * 据此还原一条线性执行叙事：用户提问 → 模型输出 → 工具调用(按 tool_use_id 把 input/output 配对) →
 * 最终回答。{@code rag_search} 特殊标为知识库检索（查询词 + 召回片段）。
 *
 * <p>步骤来自 {@link TraceQueryService#detail}（租户已隔离）。兼容 Claude(content blocks) /
 * OpenAI(choices)。全部尽力而为，解析异常不影响整体可用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TraceReplayService {

    private static final int MAX_TEXT = 20_000;

    private final TraceQueryService traceQueryService;
    private final AiModelCallContentMapper contentMapper;

    /** 租户侧回放：按 trace_id 取（租户隔离）后还原。 */
    public TraceReplay replay(String traceId) {
        return buildReplay(traceQueryService.detail(traceId)); // detail 内含租户隔离 + 抛 NOT_FOUND
    }

    /**
     * 从一条已加载好的 trace（含有序 steps）还原回放载荷。租户侧与运营侧（跨租户已 runAsSystem
     * 加载）共用：仅依赖传入的 trace/steps，不再自行做租户相关查询。
     */
    public TraceReplay buildReplay(AiTrace trace) {
        List<AiTraceStep> steps = trace.getSteps() == null ? List.of() : trace.getSteps();
        Map<Long, AiModelCallContent> contentByLogId = loadContents(steps);

        TraceReplay r = new TraceReplay();
        r.setTraceId(trace.getTraceId());
        r.setAgentName(trace.getAgentName());
        r.setEnterpriseName(trace.getEnterpriseName());
        r.setUserMessage(trace.getUserMessage());
        r.setStatus(trace.getStatus());
        r.setStartTime(trace.getStartTime());
        r.setEndTime(trace.getEndTime());
        r.setStepCount(trace.getStepCount());
        r.setTotalLatencyMs(trace.getTotalLatencyMs());
        r.setTotalTokens(trace.getTotalTokens());
        r.setSteps(steps.stream().map(this::toLiteStep).collect(Collectors.toList()));

        buildConversation(r, steps, contentByLogId);
        return r;
    }

    /** 批量按 ref_log_id 取模型调用内容，避免 N+1。 */
    private Map<Long, AiModelCallContent> loadContents(List<AiTraceStep> steps) {
        List<Long> logIds = steps.stream()
                .map(AiTraceStep::getRefLogId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (logIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AiModelCallContent> map = new HashMap<>();
        for (AiModelCallContent c : contentMapper.selectList(
                new LambdaQueryWrapper<AiModelCallContent>().in(AiModelCallContent::getLogId, logIds))) {
            map.put(c.getLogId(), c);
        }
        return map;
    }

    private TraceReplayStep toLiteStep(AiTraceStep s) {
        TraceReplayStep r = new TraceReplayStep();
        r.setStepIndex(s.getStepIndex());
        r.setStepType(s.getStepType());
        r.setTitle(s.getTitle());
        r.setSubTitle(s.getSubTitle());
        r.setModel(s.getModel());
        r.setDurationMs(s.getDurationMs());
        r.setTotalTokens(s.getTotalTokens());
        r.setStatus(s.getStatus());
        r.setErrorMsg(s.getErrorMsg());
        r.setStepTime(s.getStepTime());
        r.setMetadata(parseLoose(s.getMetadata()));
        return r;
    }

    // ============================ 执行叙事还原 ============================

    private void buildConversation(TraceReplay r, List<AiTraceStep> steps,
                                   Map<Long, AiModelCallContent> contentByLogId) {
        // 取「最后一次有内容的 LLM 步骤」：其 req_body 含整段对话历史，stream_events 是最终回答。
        AiTraceStep lastLlm = null;
        for (AiTraceStep s : steps) {
            if ("LLM".equals(s.getStepType()) && s.getRefLogId() != null
                    && contentByLogId.containsKey(s.getRefLogId())) {
                if (lastLlm == null || nz(s.getStepIndex()) >= nz(lastLlm.getStepIndex())) {
                    lastLlm = s;
                }
            }
        }
        if (lastLlm == null) {
            r.setHistory(List.of());
            r.setConversation(List.of());
            return;
        }

        AiModelCallContent content = contentByLogId.get(lastLlm.getRefLogId());
        JSONObject req = safeParseObj(content.getReqBody());
        if (req == null) {
            r.setHistory(List.of());
            r.setConversation(List.of());
            return;
        }
        r.setSystem(truncate(stringifySystem(req.get("system"))));
        r.setParams(extractParams(req));

        List<TraceReplayTurn> history = new ArrayList<>();
        List<TraceReplayTurn> current = new ArrayList<>();
        JSONArray messages = req.getJSONArray("messages");
        if (messages != null) {
            Map<String, Object> toolOutputs = collectToolOutputs(messages);
            // 本轮执行的步骤游标：LLM 步按顺序归属给每条 assistant 响应 / 最终回答；
            // KB / 工具步按顺序归属给对应工具卡（同类按出现顺序，归属可靠）。
            Cursors cur = new Cursors(steps);
            // 以「最后一条真实用户提问」为界：之前进历史气泡，之后（含该问题）进本轮执行叙事。
            int boundary = lastUserQuestionIndex(messages);
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof JSONObject m)) {
                    continue;
                }
                if (i < boundary) {
                    appendHistoryBubble(history, m);
                } else {
                    appendMessageTurns(current, m, toolOutputs, cur);
                }
            }
            // 追加最终回答（最后一次 LLM 调用的响应；它不在 req_body.messages 里）。
            String respRaw = StrUtil.isNotBlank(content.getStreamEvents())
                    ? content.getStreamEvents() : content.getRespBody();
            appendFinalAnswer(current, safeParseObj(respRaw), toolOutputs, cur);
        }

        r.setHistory(history);
        r.setConversation(current);
    }

    /** 步骤游标：按类型分桶、按 step_index 排序，供执行单元顺序归属耗时/token。 */
    private static final class Cursors {
        private final List<AiTraceStep> llm = new ArrayList<>();
        private final java.util.Deque<AiTraceStep> kb = new java.util.ArrayDeque<>();
        private final java.util.Deque<AiTraceStep> rerank = new java.util.ArrayDeque<>();
        private final java.util.Deque<AiTraceStep> tool = new java.util.ArrayDeque<>();
        private int llmIdx = 0;

        Cursors(List<AiTraceStep> steps) {
            List<AiTraceStep> sorted = new ArrayList<>(steps);
            sorted.sort(java.util.Comparator.comparingInt(s -> s.getStepIndex() == null ? 0 : s.getStepIndex()));
            for (AiTraceStep s : sorted) {
                switch (s.getStepType() == null ? "" : s.getStepType()) {
                    case "LLM" -> llm.add(s);
                    case "KB_SEARCH" -> kb.add(s);
                    case "RERANK" -> rerank.add(s);
                    case "TOOL_CALL", "PLUGIN_TRIGGER" -> tool.add(s);
                    default -> { /* 其它类型不单独归属 */ }
                }
            }
        }

        /** 取下一个 LLM 步（每条 assistant 响应 / 最终回答消费一个）。 */
        AiTraceStep nextLlm() {
            return llmIdx < llm.size() ? llm.get(llmIdx++) : null;
        }

        AiTraceStep nextKb() {
            return kb.poll();
        }

        AiTraceStep nextRerank() {
            return rerank.poll();
        }

        AiTraceStep nextTool() {
            return tool.poll();
        }
    }

    /** 最后一条「真实用户提问」（字符串内容或含 text 块；排除纯 tool_result 的 user 轮）的下标。 */
    private int lastUserQuestionIndex(JSONArray messages) {
        int idx = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof JSONObject m
                    && "user".equals(m.getStr("role")) && isRealUserText(m)) {
                idx = i;
            }
        }
        return idx;
    }

    private boolean isRealUserText(JSONObject m) {
        Object content = m.get("content");
        if (content instanceof String s) {
            return StrUtil.isNotBlank(s);
        }
        if (content instanceof JSONArray blocks) {
            for (Object b : blocks) {
                if (b instanceof JSONObject blk && "text".equals(blk.getStr("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 历史气泡：只保留 user / assistant 的文本（跳过纯 tool_result / 纯 tool_use 的轮）。 */
    private void appendHistoryBubble(List<TraceReplayTurn> out, JSONObject m) {
        String role = m.getStr("role");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return;
        }
        String text = textOf(m);
        if (StrUtil.isNotBlank(text)) {
            out.add(textTurn(role, text));
        }
    }

    /** 取一条消息的纯文本（字符串内容直接用；块数组只取 text 块）。 */
    private String textOf(JSONObject m) {
        Object content = m.get("content");
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof JSONArray blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof JSONObject blk && "text".equals(blk.getStr("type"))) {
                    appendText(sb, blk.getStr("text"));
                }
            }
            return sb.toString();
        }
        return null;
    }

    /** 扫描所有 tool_result 块，建 tool_use_id -> 输出 的映射。 */
    private Map<String, Object> collectToolOutputs(JSONArray messages) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Object o : messages) {
            if (!(o instanceof JSONObject m)) {
                continue;
            }
            Object content = m.get("content");
            if (!(content instanceof JSONArray blocks)) {
                continue;
            }
            for (Object b : blocks) {
                if (b instanceof JSONObject blk && "tool_result".equals(blk.getStr("type"))) {
                    String id = blk.getStr("tool_use_id");
                    if (StrUtil.isNotBlank(id)) {
                        map.put(id, parseLoose(contentToText(blk.get("content"))));
                    }
                }
            }
        }
        return map;
    }

    /** 把一条消息拆成叙事单元：user 文本、assistant 文本、tool_use(配对 output)。 */
    private void appendMessageTurns(List<TraceReplayTurn> turns, JSONObject m,
                                   Map<String, Object> toolOutputs, Cursors cur) {
        String role = m.getStr("role");
        Object content = m.get("content");

        if (content instanceof String s) {
            if ("user".equals(role) && StrUtil.isNotBlank(s)) {
                turns.add(textTurn("user", s));
            } else if ("assistant".equals(role) && StrUtil.isNotBlank(s)) {
                turns.add(applyLlm(textTurn("assistant", s), cur.nextLlm()));
            }
            return;
        }
        if (!(content instanceof JSONArray blocks)) {
            return;
        }
        // 一条 assistant 消息 = 一次 LLM 调用：把该 LLM 步的耗时/token 归到本消息产生的第一张卡。
        boolean assistant = "assistant".equals(role);
        AiTraceStep llmStep = assistant ? cur.nextLlm() : null;
        boolean llmApplied = false;
        for (Object b : blocks) {
            if (!(b instanceof JSONObject blk)) {
                continue;
            }
            String type = blk.getStr("type");
            if ("text".equals(type)) {
                String t = blk.getStr("text");
                if (StrUtil.isNotBlank(t)) {
                    TraceReplayTurn turn = textTurn(assistant ? "assistant" : "user", t);
                    if (assistant && !llmApplied) {
                        applyLlm(turn, llmStep);
                        llmApplied = true;
                    }
                    turns.add(turn);
                }
            } else if ("tool_use".equals(type)) {
                TraceReplayTurn turn = toolTurn(blk.getStr("id"), blk.getStr("name"), blk.get("input"), toolOutputs, cur);
                if (assistant && !llmApplied) {
                    applyLlm(turn, llmStep);
                    llmApplied = true;
                }
                turns.add(turn);
            }
            // tool_result 块不单独成项：已配对到对应 tool_use 卡片的 output。
        }
    }

    /** 最终回答：解析最后一次 LLM 响应的 content/choices，文本作为 answer，残留 tool_use 也补上。 */
    private void appendFinalAnswer(List<TraceReplayTurn> turns, JSONObject resp,
                                  Map<String, Object> toolOutputs, Cursors cur) {
        if (resp == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        List<TraceReplayTurn> toolTurns = new ArrayList<>();
        JSONArray contentBlocks = resp.getJSONArray("content");
        if (contentBlocks != null) {
            for (Object o : contentBlocks) {
                if (!(o instanceof JSONObject b)) {
                    continue;
                }
                String type = b.getStr("type");
                if ("text".equals(type)) {
                    appendText(text, b.getStr("text"));
                } else if ("tool_use".equals(type)) {
                    toolTurns.add(toolTurn(b.getStr("id"), b.getStr("name"), b.get("input"), toolOutputs, cur));
                }
            }
        } else {
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty() && choices.get(0) instanceof JSONObject c0) {
                JSONObject msg = c0.getJSONObject("message");
                if (msg != null) {
                    appendText(text, msg.getStr("content"));
                }
            }
        }
        // 最终回答 = 最后一次 LLM 调用：归属其 LLM 步的耗时/token。
        AiTraceStep llmStep = cur.nextLlm();
        if (text.length() > 0) {
            turns.add(applyLlm(textTurn("answer", text.toString()), llmStep));
        } else if (!toolTurns.isEmpty()) {
            applyLlm(toolTurns.get(0), llmStep);
        }
        turns.addAll(toolTurns);
    }

    private TraceReplayTurn textTurn(String kind, String text) {
        TraceReplayTurn t = new TraceReplayTurn();
        t.setKind(kind);
        t.setText(truncate(text));
        return t;
    }

    private TraceReplayTurn toolTurn(String id, String name, Object input,
                                     Map<String, Object> toolOutputs, Cursors cur) {
        TraceReplayTurn t = new TraceReplayTurn();
        t.setKind("tool");
        t.setToolName(name);
        String type = classifyTool(name);
        t.setToolType(type);
        t.setInput(input);
        if (id != null) {
            t.setOutput(toolOutputs.get(id));
        }
        // 工具卡耗时：知识库取 KB_SEARCH 步、插件/工具取 TOOL_CALL/PLUGIN_TRIGGER 步（同类按顺序）。
        AiTraceStep step = "kb".equals(type) ? cur.nextKb()
                : "plugin".equals(type) ? cur.nextTool() : null;
        if (step != null) {
            t.setDurationMs(step.getDurationMs());
        }
        // 知识库检索：把同一次检索的 rerank 步信息（model/kept/candidates）挂到本卡。
        if ("kb".equals(type)) {
            AiTraceStep rr = cur.nextRerank();
            if (rr != null) {
                t.setRerank(parseLoose(rr.getMetadata()));
            }
        }
        return t;
    }

    /**
     * 模型调用参数：model / temperature / top_p（不含 max_tokens）。
     * 只取「这次调用」实际下发的 req_body 值——即用户发送那一刻真实用到的参数；
     * 模型不支持 / 未设置则该项不存在，前端不显示（不回退 Agent 配置、不填「默认」）。
     */
    private Map<String, Object> extractParams(JSONObject req) {
        if (req == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "model", req.get("model"));
        putIfPresent(m, "temperature", req.get("temperature"));
        putIfPresent(m, "top_p", req.get("top_p"));
        return m.isEmpty() ? null : m;
    }

    private void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null && !(v instanceof String s && s.isBlank())) {
            m.put(key, v);
        }
    }

    /** 把 LLM 步的耗时/token 归到该卡。 */
    private TraceReplayTurn applyLlm(TraceReplayTurn turn, AiTraceStep step) {
        if (step != null) {
            turn.setDurationMs(step.getDurationMs());
            turn.setTokens(step.getTotalTokens());
        }
        return turn;
    }

    /** 工具子类型：知识库 / 技能激活 / 插件(工具)。 */
    private String classifyTool(String name) {
        if (name == null) {
            return "tool";
        }
        String n = name.toLowerCase();
        if (n.contains("rag") || n.contains("kb_") || n.contains("knowledge")) {
            return "kb";
        }
        if (n.contains("activate_skills") || n.contains("skill")) {
            return "skill";
        }
        return "plugin";
    }

    // ============================ 解析工具 ============================

    private void appendText(StringBuilder sb, String s) {
        if (StrUtil.isNotBlank(s)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
    }

    private String stringifySystem(Object system) {
        if (system == null) {
            return null;
        }
        if (system instanceof String s) {
            return s;
        }
        if (system instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (Object o : arr) {
                if (o instanceof JSONObject b && b.get("text") != null) {
                    appendText(sb, b.getStr("text"));
                } else if (o instanceof String str) {
                    appendText(sb, str);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return String.valueOf(system);
    }

    /** 把消息 content 归一成文本：字符串直接用；块数组按 text / tool_result / tool_use 展开。 */
    private String contentToText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (!(content instanceof JSONArray blocks)) {
            return String.valueOf(content);
        }
        StringBuilder sb = new StringBuilder();
        for (Object b : blocks) {
            if (!(b instanceof JSONObject blk)) {
                continue;
            }
            String type = blk.getStr("type");
            if ("text".equals(type)) {
                appendText(sb, blk.getStr("text"));
            } else if ("tool_result".equals(type)) {
                appendText(sb, contentToText(blk.get("content")));
            } else {
                appendText(sb, blk.toString());
            }
        }
        return sb.toString();
    }

    private Object parseLoose(String s) {
        if (StrUtil.isBlank(s)) {
            return null;
        }
        return JSONUtil.isTypeJSON(s) ? JSONUtil.parse(s) : s;
    }

    private JSONObject safeParseObj(String s) {
        try {
            return StrUtil.isNotBlank(s) && JSONUtil.isTypeJSON(s) ? JSONUtil.parseObj(s) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…(已截断)";
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}

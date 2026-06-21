package com.jimeng.dataserver.ai.run;

import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次生成运行的服务端累积态：把下发的 SSE 事件折叠成 content / segments / citations，
 * 与前端 {@code useSSE.ts} 的累积逻辑逐位对齐——这样断连/重连/多窗口时，服务端持久化的内容与
 * 「一直在看」时前端渲染的完全一致。
 *
 * <p>由产生它的单个生成线程顺序消费，无需并发保护。
 */
public class RunState {

    private final long startedAtMs;
    private final StringBuilder text = new StringBuilder();
    /** 有序片段，每个元素形如 {type:text,text} / {type:tool,call:{...}} / {type:artifact,artifact:{...}}。 */
    private final List<Map<String, Object>> segments = new ArrayList<>();
    /** 按去重键保留首次插入顺序的引用（值为引用对象）。 */
    private final LinkedHashMap<String, Object> citations = new LinkedHashMap<>();
    private int inputTokens;
    private int outputTokens;
    private Long elapsedMs;
    private String errorMessage;
    /** COMPLETED / FAILED；null 表示尚未收到终止事件。 */
    private String terminalStatus;

    public RunState(long startedAtMs) {
        this.startedAtMs = startedAtMs;
    }

    // ------------------------------------------------------------------ 文本

    public void appendText(String delta) {
        if (delta == null || delta.isEmpty()) return;
        text.append(delta);
        Map<String, Object> last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (last != null && "text".equals(last.get("type"))) {
            last.put("text", String.valueOf(last.get("text")) + delta);
        } else {
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("type", "text");
            seg.put("text", delta);
            segments.add(seg);
        }
    }

    // ------------------------------------------------------------------ 工具调用

    @SuppressWarnings("unchecked")
    private Map<String, Object> findToolCall(String id) {
        if (id == null) return null;
        for (Map<String, Object> seg : segments) {
            if ("tool".equals(seg.get("type")) && seg.get("call") instanceof Map<?, ?> m
                    && id.equals(String.valueOf(m.get("id")))) {
                return (Map<String, Object>) seg.get("call");
            }
        }
        return null;
    }

    private Map<String, Object> ensureToolCall(String id, String name) {
        Map<String, Object> call = findToolCall(id);
        if (call == null) {
            call = new LinkedHashMap<>();
            call.put("id", id);
            if (name != null) call.put("name", name);
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("type", "tool");
            seg.put("call", call);
            segments.add(seg);
        }
        return call;
    }

    public void upsertToolRunning(String id, String name, String desc, Object input) {
        Map<String, Object> call = ensureToolCall(id, name);
        if (name != null) call.put("name", name);
        if (desc != null) call.put("desc", desc);
        if (input != null) call.put("input", input);
        call.put("status", "running");
    }

    public void setToolStatus(String id, String name, String status) {
        Map<String, Object> call = ensureToolCall(id, name);
        call.put("status", status);
    }

    public void setToolOutput(String id, String tool, Object output) {
        Map<String, Object> call = findToolCall(id);
        if (call == null) {
            call = ensureToolCall(id, tool);
            call.put("status", "running");
        }
        call.put("output", output);
    }

    // ------------------------------------------------------------------ 产物 / 引用

    public void addArtifact(Map<String, Object> artifact) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("type", "artifact");
        seg.put("artifact", artifact);
        segments.add(seg);
    }

    public void addCitation(String key, Object citation) {
        citations.put(key, citation);
    }

    // ------------------------------------------------------------------ 用量 / 终止

    public void setUsage(int in, int out) {
        this.inputTokens = in;
        this.outputTokens = out;
    }

    public void setElapsedMs(Long ms) {
        this.elapsedMs = ms;
    }

    public void setError(String msg) {
        this.errorMessage = msg;
    }

    public void markTerminal(String status) {
        this.terminalStatus = status;
    }

    // ------------------------------------------------------------------ 收尾投影

    /** 流结束时把仍 running 的工具段规整为 error（与 useSSE.finalize 对齐），否则刷新后会看到永远转圈的工具。 */
    @SuppressWarnings("unchecked")
    public void normalizeRunningTools() {
        for (Map<String, Object> seg : segments) {
            if ("tool".equals(seg.get("type")) && seg.get("call") instanceof Map<?, ?> m
                    && "running".equals(m.get("status"))) {
                ((Map<String, Object>) seg.get("call")).put("status", "error");
            }
        }
    }

    public String getContent() {
        return text.toString();
    }

    /** 仅当含 tool/artifact 段才返回 segments JSON（纯文本回退渲染 content），与 ChatPanel 的 rich 规则一致。 */
    public String getSegmentsJsonOrNull() {
        boolean rich = segments.stream()
                .anyMatch(s -> "tool".equals(s.get("type")) || "artifact".equals(s.get("type")));
        return rich ? JSONUtil.toJsonStr(segments) : null;
    }

    public String getCitationsJsonOrNull() {
        return citations.isEmpty() ? null : JSONUtil.toJsonStr(new ArrayList<>(citations.values()));
    }

    public Long getElapsedMs() {
        return elapsedMs != null ? elapsedMs : (System.currentTimeMillis() - startedAtMs);
    }

    public String getTerminalStatus() {
        return terminalStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}

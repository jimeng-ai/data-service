package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把服务端下发的一帧 SSE 事件折叠进 {@link RunState}，与前端 {@code useSSE.ts} 的累积逻辑逐位对齐。
 *
 * <p>事件集（对话 RAG + 代码执行 Agent 的超集）：
 * {@code citations / claude-delta / message / progress / tool_result / code_output / artifact / summary / error}。
 * {@code file_status} 等瞬态事件只下发给实时观众、不进持久化态。
 */
@Slf4j
@Component
public class RunSegmentAssembler {

    public void fold(RunState state, String event, String data) {
        if (state == null || event == null) return;
        try {
            switch (event) {
                case "citations" -> foldCitations(state, data);
                case "claude-delta" -> foldClaudeDelta(state, data);
                case "message" -> foldMessage(state, data);
                case "progress" -> foldProgress(state, data);
                case "tool_result" -> foldToolResult(state, data);
                case "code_output" -> foldCodeOutput(state, data);
                case "artifact" -> foldArtifact(state, data);
                case "summary" -> foldSummary(state, data);
                case "error" -> foldError(state, data);
                default -> { /* file_status / ping 等瞬态帧不进持久化态 */ }
            }
        } catch (Exception e) {
            log.warn("RunState 折叠事件失败 event={} err={}", event, e.getMessage());
        }
    }

    private void foldCitations(RunState s, String data) {
        if (StrUtil.isBlank(data)) return;
        JSONArray arr = JSONUtil.parseArray(data);
        for (Object o : arr) {
            if (!(o instanceof JSONObject c)) continue;
            String chunkId = c.getStr("chunkId");
            String key = StrUtil.isNotBlank(chunkId)
                    ? chunkId
                    : c.getStr("docId") + "#" + StrUtil.nullToEmpty(c.getStr("content"));
            s.addCitation(key, c);
        }
    }

    private void foldClaudeDelta(RunState s, String data) {
        JSONObject p = JSONUtil.parseObj(data);
        if ("content_block_delta".equals(p.getStr("type"))) {
            JSONObject delta = p.getJSONObject("delta");
            if (delta != null && "text_delta".equals(delta.getStr("type"))) {
                s.appendText(delta.getStr("text"));
            }
        }
    }

    private void foldMessage(RunState s, String data) {
        try {
            JSONObject p = JSONUtil.parseObj(data);
            s.appendText(p.getStr("delta", p.getStr("text")));
        } catch (Exception e) {
            s.appendText(data);
        }
    }

    private void foldProgress(RunState s, String data) {
        JSONArray calls = JSONUtil.parseObj(data).getJSONArray("calls");
        if (calls == null) return;
        for (Object o : calls) {
            if (!(o instanceof JSONObject c)) continue;
            s.upsertToolRunning(c.getStr("id"), c.getStr("name"), c.getStr("desc"), c.get("input"));
        }
    }

    private void foldToolResult(RunState s, String data) {
        JSONArray results = JSONUtil.parseObj(data).getJSONArray("results");
        if (results == null) return;
        for (Object o : results) {
            if (!(o instanceof JSONObject r)) continue;
            s.setToolStatus(r.getStr("id"), r.getStr("name"), r.getStr("status"));
        }
    }

    private void foldCodeOutput(RunState s, String data) {
        JSONObject c = JSONUtil.parseObj(data);
        s.setToolOutput(c.getStr("id"), c.getStr("tool"), c.getStr("output"));
    }

    private void foldArtifact(RunState s, String data) {
        s.addArtifact(JSONUtil.parseObj(data));
    }

    private void foldSummary(RunState s, String data) {
        JSONObject p = JSONUtil.parseObj(data);
        s.setUsage(p.getInt("input_tokens", 0), p.getInt("output_tokens", 0));
        Double secs = p.getDouble("elapsed_seconds");
        if (secs != null) s.setElapsedMs(Math.round(secs * 1000));
        s.markTerminal("COMPLETED");
    }

    private void foldError(RunState s, String data) {
        try {
            JSONObject p = JSONUtil.parseObj(data);
            s.setError(p.getStr("message", p.getStr("error")));
        } catch (Exception ignore) {
            s.setError(data);
        }
        s.markTerminal("FAILED");
    }
}

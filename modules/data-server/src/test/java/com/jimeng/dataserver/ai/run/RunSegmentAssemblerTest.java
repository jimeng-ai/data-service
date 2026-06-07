package com.jimeng.dataserver.ai.run;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 服务端段拼装与前端 useSSE.ts 的折叠逻辑必须逐位对齐——否则断连/刷新后历史渲染会与「一直在看」时不一致。
 * 这里喂录制的帧序列，断言落库的 content / segments / citations 形状。
 */
class RunSegmentAssemblerTest {

    private final RunSegmentAssembler assembler = new RunSegmentAssembler();

    private RunState fold(String... eventsAndData) {
        RunState s = new RunState(System.currentTimeMillis());
        for (int i = 0; i < eventsAndData.length; i += 2) {
            assembler.fold(s, eventsAndData[i], eventsAndData[i + 1]);
        }
        return s;
    }

    private static String delta(String text) {
        return "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}";
    }

    @Test
    void ragFlow_interleavedSegments_contentCitationsTerminal() {
        RunState s = fold(
                "citations", "[{\"index\":0,\"docId\":\"d1\",\"chunkId\":\"c1\",\"content\":\"foo\"}]",
                "claude-delta", delta("正在"),
                "claude-delta", delta("检索"),
                "progress", "{\"round\":1,\"calls\":[{\"id\":\"t1\",\"name\":\"search\",\"desc\":\"搜索\",\"input\":{\"q\":\"x\"},\"status\":\"running\"}]}",
                "tool_result", "{\"round\":1,\"results\":[{\"id\":\"t1\",\"name\":\"search\",\"status\":\"success\"}]}",
                "claude-delta", delta("答案"),
                "summary", "{\"input_tokens\":10,\"output_tokens\":20,\"elapsed_seconds\":1.5}");
        s.normalizeRunningTools();

        assertEquals("正在检索答案", s.getContent());
        assertEquals("COMPLETED", s.getTerminalStatus());
        assertEquals(1500L, s.getElapsedMs());

        JSONArray segs = JSONUtil.parseArray(s.getSegmentsJsonOrNull());
        assertEquals(3, segs.size());
        assertEquals("text", segs.getJSONObject(0).getStr("type"));
        assertEquals("正在检索", segs.getJSONObject(0).getStr("text"));
        assertEquals("tool", segs.getJSONObject(1).getStr("type"));
        assertEquals("success", segs.getJSONObject(1).getJSONObject("call").getStr("status"));
        assertEquals("text", segs.getJSONObject(2).getStr("type"));
        assertEquals("答案", segs.getJSONObject(2).getStr("text"));

        JSONArray cites = JSONUtil.parseArray(s.getCitationsJsonOrNull());
        assertEquals(1, cites.size());
        assertEquals("c1", cites.getJSONObject(0).getStr("chunkId"));
    }

    @Test
    void pureText_storesContentButNullSegments() {
        RunState s = fold(
                "claude-delta", delta("你好"),
                "claude-delta", delta("世界"),
                "summary", "{\"input_tokens\":1,\"output_tokens\":2,\"elapsed_seconds\":0.2}");
        s.normalizeRunningTools();
        assertEquals("你好世界", s.getContent());
        assertNull(s.getSegmentsJsonOrNull(), "纯文本回复不落 segments，刷新后回退渲染 content");
        assertNull(s.getCitationsJsonOrNull());
    }

    @Test
    void execFlow_codeOutputAndArtifactSegments() {
        RunState s = fold(
                "progress", "{\"round\":1,\"calls\":[{\"id\":\"t1\",\"name\":\"Bash\",\"status\":\"running\"}]}",
                "code_output", "{\"id\":\"t1\",\"tool\":\"Bash\",\"output\":\"hello\\n\"}",
                "tool_result", "{\"round\":1,\"results\":[{\"id\":\"t1\",\"name\":\"Bash\",\"status\":\"success\"}]}",
                "artifact", "{\"artifactId\":\"a1\",\"filename\":\"chart.png\",\"contentType\":\"image/png\",\"downloadUrl\":\"/data/agent/artifacts/a1/download\"}",
                "summary", "{\"input_tokens\":5,\"output_tokens\":7,\"elapsed_seconds\":2}");
        s.normalizeRunningTools();
        JSONArray segs = JSONUtil.parseArray(s.getSegmentsJsonOrNull());
        assertEquals(2, segs.size());
        JSONObject tool = segs.getJSONObject(0);
        assertEquals("tool", tool.getStr("type"));
        assertEquals("hello\n", tool.getJSONObject("call").getStr("output"));
        assertEquals("success", tool.getJSONObject("call").getStr("status"));
        JSONObject art = segs.getJSONObject(1);
        assertEquals("artifact", art.getStr("type"));
        assertEquals("chart.png", art.getJSONObject("artifact").getStr("filename"));
        assertEquals("/data/agent/artifacts/a1/download", art.getJSONObject("artifact").getStr("downloadUrl"));
    }

    @Test
    void runningToolNormalizedToError_whenStreamFails() {
        RunState s = fold(
                "progress", "{\"round\":1,\"calls\":[{\"id\":\"t1\",\"name\":\"search\",\"status\":\"running\"}]}",
                "error", "{\"error\":\"stream_failure\",\"message\":\"上游断开\"}");
        s.normalizeRunningTools();
        assertEquals("FAILED", s.getTerminalStatus());
        assertEquals("上游断开", s.getErrorMessage());
        JSONArray segs = JSONUtil.parseArray(s.getSegmentsJsonOrNull());
        assertEquals("error", segs.getJSONObject(0).getJSONObject("call").getStr("status"));
    }

    @Test
    void citationsMergedAndDedupedByChunkId() {
        RunState s = fold(
                "citations", "[{\"docId\":\"d1\",\"chunkId\":\"c1\",\"content\":\"a\"}]",
                "citations", "[{\"docId\":\"d1\",\"chunkId\":\"c1\",\"content\":\"a\"},{\"docId\":\"d2\",\"chunkId\":\"c2\",\"content\":\"b\"}]");
        JSONArray cites = JSONUtil.parseArray(s.getCitationsJsonOrNull());
        assertEquals(2, cites.size());
        assertEquals("c1", cites.getJSONObject(0).getStr("chunkId"));
        assertEquals("c2", cites.getJSONObject(1).getStr("chunkId"));
    }
}

package com.jimeng.dataserver.ai.rag.service.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.AnswerRequest;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.search.HybridSearchService;
import com.jimeng.dataserver.ai.rag.service.search.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答：检索 → 拼上下文 → 复用 ClaudeService 流式输出。
 *
 * <p>系统提示要求模型每条断言后追加 [chunk_id=...] 引用标签，便于前端定位回原文。
 *
 * <p>关键观测点（INFO 日志，可在 Kibana 中按 MDC.connectionId 关联同一次请求）：
 * <ul>
 *   <li>RAG/retrieve.start — 查询入参（kb / query / topK / docIds / rerank）</li>
 *   <li>RAG/retrieve.rrf    — RRF 融合后候选（chunkId + score + 内容截断 80 字）</li>
 *   <li>RAG/retrieve.rerank — Rerank 精排后命中（同上 + rerankScore）</li>
 *   <li>RAG/answer.prompt   — 真正喂给 LLM 的 user 消息长度</li>
 *   <li>LLM 答复文本由 AiConversationLoop 在流结束时打印</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private static final int CHUNK_PREVIEW_CHARS = 80;

    private static final String SYSTEM_PROMPT =
            "你是一个基于企业知识库的问答助手。请严格遵守：\n"
                    + "1) 仅根据下方提供的【知识片段】回答问题，不要编造未在片段中出现的信息；\n"
                    + "2) 每条关键论断后用方括号附引用标签，例如 [chunk_id=12345_3]；\n"
                    + "3) 如果片段不足以回答，明确告知\"未在知识库中找到相关信息\"；\n"
                    + "4) 回答中文且简洁，必要时分点。";

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final ClaudeService claudeService;
    private final RagProperties ragProperties;
    private final SseServiceUtil sseServiceUtil;

    public List<SearchResultItem> retrieve(AnswerRequest req) throws Exception {
        int topK = req.getTopK() != null ? req.getTopK() : ragProperties.getRetrieval().getRerankTopK();
        int window = Math.max(ragProperties.getRetrieval().getBm25TopK(), ragProperties.getRetrieval().getVectorTopK());
        boolean doRerank = req.getRerank() == null || req.getRerank();

        log.info("RAG/retrieve.start kbId={} topK={} rerank={} docIds={} query={}",
                req.getKbId(), topK, doRerank, req.getDocIds(), req.getQuery());

        List<SearchResultItem> rrf = hybridSearchService.search(
                req.getKbId(), req.getQuery(), req.getDocIds(), window);
        logCandidates("RAG/retrieve.rrf", rrf, /*rerankPhase*/ false);
        if (rrf.isEmpty()) return rrf;

        if (!doRerank) {
            return rrf.subList(0, Math.min(topK, rrf.size()));
        }
        List<SearchResultItem> rerankHits = rerankService.rerank(req.getQuery(), rrf, topK);
        logCandidates("RAG/retrieve.rerank", rerankHits, /*rerankPhase*/ true);
        return rerankHits;
    }

    public void streamAnswer(AnswerRequest req, String connectionId, String traceId) {
        try {
            List<SearchResultItem> citations = retrieve(req);
            // 先把检索到的 chunks 通过 SSE 事件前置发回客户端
            sseServiceUtil.sendEvent(connectionId, "citations", JSONUtil.toJsonStr(citations));

            Map<String, Object> body = buildClaudeBody(req, citations);
            log.info("RAG/answer.prompt citations={} totalChunkChars={} historyTurns={}",
                    citations.size(), totalContentChars(citations),
                    req.getHistory() == null ? 0 : req.getHistory().size());

            claudeService.messagesStream(body, connectionId, traceId);
        } catch (Exception e) {
            log.error("RAG 回答失败 connectionId={}", connectionId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage());
            try {
                sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(err));
                sseServiceUtil.complete(connectionId);
            } catch (Exception ignore) {}
        }
    }

    private Map<String, Object> buildClaudeBody(AnswerRequest req, List<SearchResultItem> citations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stream", true);
        body.put("system", SYSTEM_PROMPT);

        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.getHistory() != null) messages.addAll(req.getHistory());

        StringBuilder ctx = new StringBuilder();
        ctx.append("【知识片段】\n");
        for (SearchResultItem c : citations) {
            ctx.append("\n[chunk_id=").append(c.getChunkId()).append(']');
            if (c.getHeadingPath() != null) ctx.append(" 章节: ").append(c.getHeadingPath());
            if (c.getPageNum() != null) ctx.append(" 页码: ").append(c.getPageNum());
            ctx.append('\n').append(c.getContent() == null ? "" : c.getContent()).append('\n');
        }
        ctx.append("\n【问题】\n").append(req.getQuery());

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", ctx.toString());
        messages.add(userMsg);

        body.put("messages", messages);
        return body;
    }

    private void logCandidates(String tag, List<SearchResultItem> items, boolean rerankPhase) {
        if (items == null || items.isEmpty()) {
            log.info("{} 命中=0", tag);
            return;
        }
        StringBuilder sb = new StringBuilder(items.size() * 120);
        sb.append(tag).append(" 命中=").append(items.size()).append('\n');
        for (int i = 0; i < items.size(); i++) {
            SearchResultItem c = items.get(i);
            sb.append("  ").append(i + 1).append(". chunk_id=").append(c.getChunkId());
            sb.append(" doc_id=").append(c.getDocId());
            if (rerankPhase && c.getRerankScore() != null) {
                sb.append(String.format(" rerank=%.4f", c.getRerankScore()));
            }
            if (c.getRrfScore() != null) {
                sb.append(String.format(" rrf=%.4f", c.getRrfScore()));
            }
            if (StrUtil.isNotBlank(c.getHeadingPath())) {
                sb.append(" 章节=").append(c.getHeadingPath());
            }
            if (c.getPageNum() != null) sb.append(" 页=").append(c.getPageNum());
            sb.append(" content=").append(preview(c.getContent()));
            sb.append('\n');
        }
        log.info(sb.toString());
    }

    private static String preview(String content) {
        if (content == null) return "<null>";
        String trimmed = content.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= CHUNK_PREVIEW_CHARS) return trimmed;
        return trimmed.substring(0, CHUNK_PREVIEW_CHARS) + "…";
    }

    private static int totalContentChars(List<SearchResultItem> items) {
        int n = 0;
        for (SearchResultItem c : items) if (c.getContent() != null) n += c.getContent().length();
        return n;
    }
}

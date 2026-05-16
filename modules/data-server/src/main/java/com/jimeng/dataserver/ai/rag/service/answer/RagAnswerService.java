package com.jimeng.dataserver.ai.rag.service.answer;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerService {

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
        List<SearchResultItem> rrf = hybridSearchService.search(
                req.getKbId(), req.getQuery(), req.getDocIds(), window);
        if (rrf.isEmpty()) return rrf;
        boolean doRerank = req.getRerank() == null || req.getRerank();
        return doRerank ? rerankService.rerank(req.getQuery(), rrf, topK) : rrf.subList(0, Math.min(topK, rrf.size()));
    }

    public void streamAnswer(AnswerRequest req, String connectionId, String traceId) {
        try {
            List<SearchResultItem> citations = retrieve(req);
            // 先把检索到的 chunks 通过 SSE 事件前置发回客户端
            sseServiceUtil.sendEvent(connectionId, "citations", JSONUtil.toJsonStr(citations));

            Map<String, Object> body = buildClaudeBody(req, citations);
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
}

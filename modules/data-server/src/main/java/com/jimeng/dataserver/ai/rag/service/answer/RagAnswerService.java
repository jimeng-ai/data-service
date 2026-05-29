package com.jimeng.dataserver.ai.rag.service.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
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
import java.util.Comparator;
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
    private final AgentRuntimeService agentRuntimeService;

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

    /** 解析本次检索的知识库范围：显式 kbId 优先，否则用 Agent 绑定的知识库；都没有返回空（=不走 RAG）。 */
    private List<Long> resolveKbIds(AnswerRequest req) {
        if (req.getKbId() != null) {
            return List.of(req.getKbId());
        }
        if (StrUtil.isNotBlank(req.getAgentId())) {
            Long agentId = parseAgentId(req.getAgentId());
            if (agentId != null) {
                try {
                    AgentRuntimeView view = agentRuntimeService.byId(agentId);
                    if (view.getKbIds() != null && !view.getKbIds().isEmpty()) {
                        return new ArrayList<>(view.getKbIds());
                    }
                } catch (Exception e) {
                    log.warn("加载 Agent 知识库绑定失败 agentId={}: {}", req.getAgentId(), e.getMessage());
                }
            }
        }
        return List.of();
    }

    private Long parseAgentId(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 跨一个或多个知识库检索：逐库召回后合并，再统一精排取 topK。 */
    private List<SearchResultItem> retrieveForKbs(AnswerRequest req, List<Long> kbIds) throws Exception {
        int topK = req.getTopK() != null ? req.getTopK() : ragProperties.getRetrieval().getRerankTopK();
        int window = Math.max(ragProperties.getRetrieval().getBm25TopK(), ragProperties.getRetrieval().getVectorTopK());
        boolean doRerank = req.getRerank() == null || req.getRerank();

        log.info("RAG/retrieve.start kbIds={} topK={} rerank={} docIds={} query={}",
                kbIds, topK, doRerank, req.getDocIds(), req.getQuery());

        List<SearchResultItem> merged = new ArrayList<>();
        for (Long kbId : kbIds) {
            merged.addAll(hybridSearchService.search(kbId, req.getQuery(), req.getDocIds(), window));
        }
        logCandidates("RAG/retrieve.rrf", merged, /*rerankPhase*/ false);
        if (merged.isEmpty()) return merged;

        if (!doRerank) {
            merged.sort(Comparator.comparingDouble(
                    (SearchResultItem c) -> c.getRrfScore() == null ? 0d : c.getRrfScore()).reversed());
            return merged.subList(0, Math.min(topK, merged.size()));
        }
        List<SearchResultItem> rerankHits = rerankService.rerank(req.getQuery(), merged, topK);
        logCandidates("RAG/retrieve.rerank", rerankHits, /*rerankPhase*/ true);
        return rerankHits;
    }

    public void streamAnswer(AnswerRequest req, String connectionId, String traceId) {
        try {
            // 检索范围：① 显式 kbId 优先；② 否则取 Agent 绑定的知识库；③ 都没有则纯对话。
            // 即「配了知识库才走 RAG」，绝不强制检索。
            List<Long> kbIds = resolveKbIds(req);
            boolean useRag = !kbIds.isEmpty();
            List<SearchResultItem> citations = useRag ? retrieveForKbs(req, kbIds) : List.of();
            // 始终前置发一个 citations 事件（无 KB 时为空数组），保证前端协议一致。
            sseServiceUtil.sendEvent(connectionId, "citations", JSONUtil.toJsonStr(citations));

            Map<String, Object> body = buildClaudeBody(req, citations, useRag);

            // 带 agentId 时加载 Agent 人设/模型/插件上下文：
            // prepareAgentContext 会把 systemPrompt 前置注入、补默认 model/params，
            // 并把 AgentContext 设到当前线程（下游工具据此决定可用插件——配了插件才会走）。
            if (StrUtil.isNotBlank(req.getAgentId())) {
                body.put("agent_id", req.getAgentId());
                claudeService.prepareAgentContext(body);
            }

            log.info("RAG/answer.prompt useRag={} agentId={} citations={} totalChunkChars={} historyTurns={}",
                    useRag, req.getAgentId(), citations.size(), totalContentChars(citations),
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

    private Map<String, Object> buildClaudeBody(AnswerRequest req, List<SearchResultItem> citations, boolean useRag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stream", true);
        // 仅在走 RAG 时注入知识库问答的系统提示（含引用标签规则）；
        // 纯对话场景不设 system，交由 Agent 的 systemPrompt 注入（prepareAgentContext）。
        if (useRag) {
            body.put("system", SYSTEM_PROMPT);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.getHistory() != null) messages.addAll(req.getHistory());

        String userContent;
        if (useRag) {
            StringBuilder ctx = new StringBuilder();
            ctx.append("【知识片段】\n");
            for (SearchResultItem c : citations) {
                ctx.append("\n[chunk_id=").append(c.getChunkId()).append(']');
                if (c.getHeadingPath() != null) ctx.append(" 章节: ").append(c.getHeadingPath());
                if (c.getPageNum() != null) ctx.append(" 页码: ").append(c.getPageNum());
                ctx.append('\n').append(c.getContent() == null ? "" : c.getContent()).append('\n');
            }
            ctx.append("\n【问题】\n").append(req.getQuery());
            userContent = ctx.toString();
        } else {
            // 纯对话：直接把用户问题作为消息内容。
            userContent = req.getQuery();
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
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

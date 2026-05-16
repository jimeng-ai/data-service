package com.jimeng.dataserver.ai.rag.skill;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.dataserver.ai.rag.service.search.HybridSearchService;
import com.jimeng.dataserver.ai.rag.service.search.RerankService;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.persistence.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 工具执行器：把 Claude 在对话中发出的 rag.search / rag.kb.list 工具调用，
 * 路由到 HybridSearchService / RerankService / KnowledgeBaseService。
 *
 * <p>由 SkillToolExecutorRegistryService 自动 Spring 注入收集，无需额外注册。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagSkillToolExecutor implements SkillToolExecutor {

    private static final String TOOL_SEARCH = "rag.search";
    private static final String TOOL_KB_LIST = "rag.kb.list";

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public boolean supports(String toolName) {
        return TOOL_SEARCH.equals(toolName) || TOOL_KB_LIST.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        try {
            if (TOOL_SEARCH.equals(toolName)) return doSearch(input);
            if (TOOL_KB_LIST.equals(toolName)) return doListKb();
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未知 RAG 工具: " + toolName);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("RAG 工具执行失败 tool={} input={}", toolName, input, e);
            throw new RuntimeException("RAG 工具执行失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> doSearch(Map<String, Object> input) throws Exception {
        Object kbIdObj = input == null ? null : input.get("kb_id");
        Object queryObj = input == null ? null : input.get("query");
        if (!(kbIdObj instanceof Number) || !(queryObj instanceof String) || ((String) queryObj).isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "rag.search 需要 kb_id(int) + query(string)");
        }
        Long kbId = ((Number) kbIdObj).longValue();
        String query = (String) queryObj;
        int topK = input.get("top_k") instanceof Number n ? n.intValue() : 10;
        boolean rerank = !(input.get("rerank") instanceof Boolean b) || b;

        int rrfWindow = 50;
        List<SearchResultItem> rrf = hybridSearchService.search(kbId, query, null, rrfWindow);
        List<SearchResultItem> hits = (rerank && !rrf.isEmpty())
                ? rerankService.rerank(query, rrf, topK)
                : rrf.subList(0, Math.min(topK, rrf.size()));

        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (SearchResultItem h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunk_id", h.getChunkId());
            m.put("doc_id", h.getDocId());
            m.put("chunk_type", h.getChunkType());
            m.put("heading_path", h.getHeadingPath());
            m.put("page_num", h.getPageNum());
            m.put("content", h.getContent());
            m.put("rerank_score", h.getRerankScore());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kb_id", kbId);
        out.put("query", query);
        out.put("count", items.size());
        out.put("results", items);
        return out;
    }

    private Map<String, Object> doListKb() {
        List<KnowledgeBase> all = knowledgeBaseService.list();
        List<Map<String, Object>> items = new ArrayList<>(all.size());
        for (KnowledgeBase kb : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", kb.getId());
            m.put("name", kb.getName());
            m.put("description", kb.getDescription());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("knowledge_bases", items);
        return out;
    }
}

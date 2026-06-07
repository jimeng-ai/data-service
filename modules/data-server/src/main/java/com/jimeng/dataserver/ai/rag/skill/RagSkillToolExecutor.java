package com.jimeng.dataserver.ai.rag.skill;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.CitationAssembler;
import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.dataserver.ai.rag.service.search.HybridSearchService;
import com.jimeng.dataserver.ai.rag.service.search.RerankService;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
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

    // 注意：Anthropic 工具名不允许点号，SkillRuntimeService.normalizeToolName 会把 tools.json 里的
    // 名字归一成下划线后再暴露给模型（rag.search → rag_search），模型回传的 tool_use 名亦是下划线。
    // 故此处用下划线名匹配；supports/execute 再做一次点→下划线归一，点号/下划线两种写法都认，避免再踩坑。
    private static final String TOOL_SEARCH = "rag_search";
    private static final String TOOL_KB_LIST = "rag_kb_list";

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final CitationAssembler citationAssembler;

    private static String norm(String toolName) {
        return toolName == null ? "" : toolName.replace('.', '_');
    }

    @Override
    public boolean supports(String toolName) {
        String n = norm(toolName);
        return TOOL_SEARCH.equals(n) || TOOL_KB_LIST.equals(n);
    }

    @Override
    public String traceStepType() {
        // RAG 检索/精排已在 HybridSearchService / RerankService 内部埋点（KB_SEARCH / RERANK），
        // 注册中心跳过，避免重复记录。
        return null;
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        String n = norm(toolName);
        try {
            if (TOOL_SEARCH.equals(n)) return doSearch(input);
            if (TOOL_KB_LIST.equals(n)) return doListKb();
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
        if (!(queryObj instanceof String) || ((String) queryObj).isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "rag.search 需要 query(string)");
        }
        // kb_id 优先取模型显式入参；缺省时回落到当前 Agent 绑定的知识库——绑定型 Agent 的 system 提示已给定
        // kb_id，此处是兜底，避免模型偶发漏传导致检索失败。两者都没有才报错。
        Long kbId = resolveKbId(kbIdObj);
        if (kbId == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "rag.search 需要 kb_id(int)：未显式提供且当前 Agent 未绑定知识库");
        }
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
        // 富化的「参考来源」走 __citations__ 旁路：AiConversationLoop 会抽出来单独发 citations 事件、
        // 并在回传给模型前剥离（不进模型上下文、不计入 token），仅供前端展示。
        out.put(ToolExecutionResult.CITATIONS_SIDECAR_KEY, citationAssembler.assemble(hits));
        return out;
    }

    /** kb_id 解析：模型显式入参优先；否则回落到当前 Agent 绑定的首个知识库；都没有返回 null。 */
    private Long resolveKbId(Object kbIdObj) {
        if (kbIdObj instanceof Number n) return n.longValue();
        AgentRuntimeView agent = AgentContext.get();
        if (agent != null && agent.getKbIds() != null && !agent.getKbIds().isEmpty()) {
            return agent.getKbIds().iterator().next();
        }
        return null;
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

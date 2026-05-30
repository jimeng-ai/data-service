package com.jimeng.dataserver.ai.rag.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.SearchRequest;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.search.HybridSearchService;
import com.jimeng.dataserver.ai.rag.service.search.RerankService;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "RAG-混合检索", description = "对知识库内文档做 BM25 + 向量混合召回，可选 rerank")
@RestController
@RequestMapping("/data/rag")
@RequiredArgsConstructor
public class RagSearchController {

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final RagProperties ragProperties;
    private final PermissionResolver permissionResolver;

    @Operation(summary = "混合检索 chunk",
            description = "在指定知识库内执行 BM25 + 向量召回，按 RRF 融合后可选走 reranker 精排，返回 topK 个 chunk 片段。" +
                    "用于知识问答前置检索；如需直接拿到 LLM 生成的答案，请改用 /data/rag/answer。")
    @PostMapping("/search")
    public List<SearchResultItem> search(@RequestBody SearchRequest req) throws Exception {
        if (req.getKbId() == null || req.getQuery() == null || req.getQuery().isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "kbId 与 query 必填");
        }
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, req.getKbId());
        int topK = req.getTopK() != null ? req.getTopK() : ragProperties.getRetrieval().getRerankTopK();
        // 先检索 BM25/KNN top-50（rrf 融合），再 rerank 到 topK
        int rrfWindow = Math.max(ragProperties.getRetrieval().getBm25TopK(),
                                 ragProperties.getRetrieval().getVectorTopK());
        List<SearchResultItem> rrf = hybridSearchService.search(
                req.getKbId(), req.getQuery(), req.getDocIds(), rrfWindow);
        boolean doRerank = req.getRerank() == null || req.getRerank();
        if (doRerank && !rrf.isEmpty()) {
            return rerankService.rerank(req.getQuery(), rrf, topK);
        }
        return rrf.subList(0, Math.min(topK, rrf.size()));
    }
}

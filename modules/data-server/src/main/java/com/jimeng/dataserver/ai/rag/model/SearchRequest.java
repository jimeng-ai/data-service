package com.jimeng.dataserver.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "RAG 混合检索请求体")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchRequest {
    @Schema(description = "知识库 ID（必填）", example = "1")
    private Long kbId;

    @Schema(description = "检索 query（必填）", example = "高德 POI 一级分类有哪些？")
    private String query;

    @Schema(description = "返回 chunk 数量，默认取 rag.retrieval.rerank-top-k", example = "10")
    private Integer topK;

    @Schema(description = "可选：限定只在这些 docId 内检索")
    private List<Long> docIds;

    @Schema(description = "是否启用 reranker 精排，默认 true", example = "true")
    private Boolean rerank;
}

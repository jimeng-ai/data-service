package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchResultItem {
    private String chunkId;
    private Long docId;
    private Long kbId;
    private Integer chunkIndex;
    private String chunkType;
    private String headingPath;
    private Integer pageNum;
    private String content;
    private String imageUrl;
    /** RRF 分（双路融合后） */
    private Double rrfScore;
    /** Reranker 分（精排后） */
    private Double rerankScore;
}

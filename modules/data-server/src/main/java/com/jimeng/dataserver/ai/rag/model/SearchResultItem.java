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

    /**
     * 统一对外分值：有精排分用精排分，否则回退 RRF 分。
     * 供「检索测试」等前端直接读 {@code score}（rrfScore/rerankScore 是内部两路分，
     * 前端不感知用哪路）。Jackson 会把这个无字段 getter 序列化成 {@code "score"} 属性。
     */
    public Double getScore() {
        return rerankScore != null ? rerankScore : rrfScore;
    }
}

package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 问答返回给前端的「引用项」：在 {@link SearchResultItem} 基础上补了展示用字段
 * （1-based 序号、文档标题、统一分值），前端据此按文档聚合成「参考来源」。
 *
 * <p>与 SearchResultItem 分开是因为：检索内部只关心 chunk + 双路/精排分，
 * 而前端展示需要文档标题（需联表 kb_document）和一个对用户友好的统一分值。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CitationItem {
    /** 1-based 展示序号 */
    private int index;
    private Long docId;
    /** 文档标题（来自 kb_document.title，可能为空） */
    private String docTitle;
    private String chunkId;
    private String content;
    private String headingPath;
    private Integer pageNum;
    /** 统一分值：有精排分用精排分，否则用 RRF 分 */
    private Double score;
}

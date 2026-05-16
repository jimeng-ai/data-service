package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 切片中间表示。pipeline 各阶段会逐步填充字段：
 * - chunker 出 chunk 时填 chunkIndex / type / content / headingPath / pageNum / tokenCount
 * - ImageDescriptionService 把 IMAGE chunk 的 content 填成 100-200 字描述
 * - ContextualizationService 填 contextualizedContent
 * - EmbeddingService 填 embedding
 * - 上传 MinIO 后填 imageUrl（如适用）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Chunk {

    private String chunkId;
    private int chunkIndex;
    private BlockType type;

    private String content;
    private String contextualizedContent;

    private List<String> headingPath;
    private Integer pageNum;
    private String imageUrl;

    private float[] embedding;
    private int tokenCount;
}

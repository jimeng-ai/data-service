package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@TableName("kb_chunk")
@Data
public class KbChunk extends BaseEntity {

    /** ES doc id（doc_id + "_" + chunk_index） */
    @TableField("chunk_id")
    private String chunkId;

    @TableField("doc_id")
    private Long docId;

    @TableField("kb_id")
    private Long kbId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    /** text / table / image / code */
    @TableField("chunk_type")
    private String chunkType;

    /** 例如 "Ch1 > Sec1.2 > Subsection" */
    @TableField("heading_path")
    private String headingPath;

    @TableField("page_num")
    private Integer pageNum;

    /** 原始 chunk 文本 */
    @TableField("content")
    private String content;

    /** 带 LLM 生成上下文前缀的版本，BM25/embedding 用这个 */
    @TableField("contextualized_content")
    private String contextualizedContent;

    @TableField("image_url")
    private String imageUrl;

    @TableField("token_count")
    private Integer tokenCount;
}

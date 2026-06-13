package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@TableName("kb_document")
@Data
public class KbDocument extends BaseEntity {

    @TableField("kb_id")
    private Long kbId;

    @TableField("title")
    private String title;

    /** pdf / docx / md / html / txt */
    @TableField("source_type")
    private String sourceType;

    @TableField("minio_bucket")
    private String minioBucket;

    @TableField("minio_object")
    private String minioObject;

    /** sha256 of file content，用于幂等 */
    @TableField("file_hash")
    private String fileHash;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 表格逐行切片：1=表格(xlsx/csv)每个数据行独立成 chunk；0/null=按 token 合并（默认） */
    @TableField("row_per_chunk")
    private Boolean rowPerChunk;

    /** UPLOADED / PARSING / CHUNKING / CONTEXTUALIZING / EMBEDDING / DONE / FAILED */
    @TableField("status")
    private String status;

    @TableField("failure_reason")
    private String failureReason;

    @TableField("total_chunks")
    private Integer totalChunks;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("ingestion_metadata")
    private String ingestionMetadata;
}

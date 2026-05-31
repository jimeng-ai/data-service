package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话内为代码执行 Agent 上传的输入文件（存 MinIO，不入 RAG 知识库）。
 */
@Schema(description = "Agent 输入文件")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_input_file")
@Data
public class AgentInputFile extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属会话 ID（可空）")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "MinIO bucket")
    @TableField("bucket")
    private String bucket;

    @Schema(description = "MinIO object name")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "原始文件名")
    @TableField("filename")
    private String filename;

    @Schema(description = "Content-Type")
    @TableField("content_type")
    private String contentType;

    @Schema(description = "文件大小（字节）")
    @TableField("size_bytes")
    private Long sizeBytes;
}

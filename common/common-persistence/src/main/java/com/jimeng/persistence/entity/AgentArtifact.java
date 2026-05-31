package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 运行产出的产物文件（存 MinIO，通过下载端点回传给用户）。
 */
@Schema(description = "Agent 产物文件")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_artifact")
@Data
public class AgentArtifact extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属运行 ID")
    @TableField("run_id")
    private Long runId;

    @Schema(description = "所属消息 ID（可空）")
    @TableField("message_id")
    private Long messageId;

    @Schema(description = "MinIO bucket")
    @TableField("bucket")
    private String bucket;

    @Schema(description = "MinIO object name")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "文件名")
    @TableField("filename")
    private String filename;

    @Schema(description = "Content-Type")
    @TableField("content_type")
    private String contentType;

    @Schema(description = "文件大小（字节）")
    @TableField("size_bytes")
    private Long sizeBytes;
}

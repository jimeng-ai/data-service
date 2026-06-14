package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 产品反馈图片。feedback_id 可空：NULL = 已上传待引用（草稿/孤儿）。 */
@Schema(description = "产品反馈图片")
@EqualsAndHashCode(callSuper = true)
@TableName("product_feedback_image")
@Data
public class ProductFeedbackImage extends BaseEntity {

    @Schema(description = "关联反馈 ID（NULL=未引用）")
    @TableField("feedback_id")
    private Long feedbackId;

    @Schema(description = "上传者租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "MinIO 对象名")
    @TableField("object_key")
    private String objectKey;

    @Schema(description = "Content-Type")
    @TableField("content_type")
    private String contentType;

    @Schema(description = "文件大小（字节）")
    @TableField("file_size")
    private Long fileSize;

    @Schema(description = "展示顺序")
    @TableField("sort_order")
    private Integer sortOrder;
}

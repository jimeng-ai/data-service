package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 产品反馈主记录。id/create_user/create_time 由 BaseEntity + MyMetaObjectHandler 自动填充。 */
@Schema(description = "产品反馈")
@EqualsAndHashCode(callSuper = true)
@TableName("product_feedback")
@Data
public class ProductFeedback extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "类型：1=问题反馈 2=功能建议")
    @TableField("feedback_type")
    private Integer feedbackType;

    @Schema(description = "文字描述")
    @TableField("content")
    private String content;
}

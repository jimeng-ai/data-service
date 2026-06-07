package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 调用链路 Trace 步骤明细：一步一行。
 * 模型步骤通过 {@link #refLogId} 反查 {@code ai_model_call_log} / {@code ai_model_call_content}。
 */
@EqualsAndHashCode(callSuper = true)
@TableName("ai_trace_step")
@Data
public class AiTraceStep extends BaseEntity {

    @TableField("trace_id")
    private String traceId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("user_id")
    private String userId;

    @TableField("step_index")
    private Integer stepIndex;

    /** LLM / KB_SEARCH / RERANK / TOOL_CALL / PLUGIN_TRIGGER。 */
    @TableField("step_type")
    private String stepType;

    @TableField("title")
    private String title;

    @TableField("sub_title")
    private String subTitle;

    @TableField("model")
    private String model;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("input_tokens")
    private Integer inputTokens;

    @TableField("output_tokens")
    private Integer outputTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("cost_usd")
    private BigDecimal costUsd;

    /** SUCCESS / WARN / ERROR。 */
    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("ref_log_id")
    private Long refLogId;

    /** 扩展字段 JSON 字符串（topK / hits / toolName / target / rows / channel 等）。 */
    @TableField("metadata")
    private String metadata;

    @TableField("step_time")
    private Date stepTime;
}

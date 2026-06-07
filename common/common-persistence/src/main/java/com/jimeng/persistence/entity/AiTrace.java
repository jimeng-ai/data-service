package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 调用链路 Trace 头表：一条 trace 一行，列表与概览统计的数据源。
 * 由 {@code TraceRecorder} 在每记录一个步骤时增量累计。
 */
@EqualsAndHashCode(callSuper = true)
@TableName("ai_trace")
@Data
public class AiTrace extends BaseEntity {

    @TableField("trace_id")
    private String traceId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("user_id")
    private String userId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("agent_name")
    private String agentName;

    /** 本次调用用户发送的消息（首步前捕获，仅记录一次）。 */
    @TableField("user_message")
    private String userMessage;

    @TableField("biz_type")
    private String bizType;

    @TableField("scene_code")
    private String sceneCode;

    /** SUCCESS / WARN / ERROR。 */
    @TableField("status")
    private String status;

    @TableField("step_count")
    private Integer stepCount;

    @TableField("total_latency_ms")
    private Long totalLatencyMs;

    @TableField("total_input_tokens")
    private Long totalInputTokens;

    @TableField("total_output_tokens")
    private Long totalOutputTokens;

    @TableField("total_tokens")
    private Long totalTokens;

    @TableField("total_cost_usd")
    private BigDecimal totalCostUsd;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("error_msg")
    private String errorMsg;

    /** 步骤明细（非持久化）：详情接口按需回填。 */
    @TableField(exist = false)
    private java.util.List<AiTraceStep> steps;

    /** 企业名称（非持久化）：运营侧列表按 tenant_id 回填，供前端展示。 */
    @TableField(exist = false)
    private String enterpriseName;
}

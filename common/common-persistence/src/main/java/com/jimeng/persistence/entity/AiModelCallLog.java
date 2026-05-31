package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 模型调用日志主表
 */
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model_call_log")
@Data
public class AiModelCallLog extends BaseEntity {

    @TableField("trace_id")
    private String traceId;

    @TableField("request_id")
    private String requestId;

    @TableField("biz_type")
    private String bizType;

    @TableField("biz_id")
    private String bizId;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("user_id")
    private String userId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("provider")
    private String provider;

    @TableField("model")
    private String model;

    @TableField("endpoint")
    private String endpoint;

    @TableField("stream")
    private Boolean stream;

    @TableField("has_text")
    private Boolean hasText;

    @TableField("has_image")
    private Boolean hasImage;

    @TableField("has_document")
    private Boolean hasDocument;

    @TableField("has_video")
    private Boolean hasVideo;

    @TableField("has_tool")
    private Boolean hasTool;

    @TableField("tool_names")
    private String toolNames;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("temperature")
    private BigDecimal temperature;

    @TableField("top_p")
    private BigDecimal topP;

    @TableField("input_tokens")
    private Integer inputTokens;

    @TableField("output_tokens")
    private Integer outputTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    /** 缓存读取 token（OpenAI cached_tokens / Anthropic cache_read_input_tokens）。 */
    @TableField("cache_read_tokens")
    private Integer cacheReadTokens;

    /** 缓存写入 token（Anthropic cache_creation_input_tokens；OpenAI 无）。 */
    @TableField("cache_write_tokens")
    private Integer cacheWriteTokens;

    /** 推理/思考 token（OpenAI reasoning_tokens；Anthropic 无，已并入 output，仅展示不重复计费）。 */
    @TableField("reasoning_tokens")
    private Integer reasoningTokens;

    /** 供应商返回的原始 usage 对象 JSON，便于将来按新字段/模态回溯重算。 */
    @TableField("usage_raw")
    private String usageRaw;

    @TableField("latency_ms")
    private Integer latencyMs;

    @TableField("first_token_ms")
    private Integer firstTokenMs;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("http_status")
    private Integer httpStatus;

    @TableField("call_status")
    private Integer callStatus;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("cost_usd")
    private BigDecimal costUsd;
}

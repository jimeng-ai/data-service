package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 代码执行 / 文件处理 Agent 的一次运行记录（边车 run 的落库）。
 * 主键 id 即 runId（雪花算法），传给边车作为 runId。
 */
@Schema(description = "Agent 代码执行运行记录")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_exec_run")
@Data
public class AgentExecRun extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属会话 ID（可空）")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "所属 Agent ID")
    @TableField("agent_id")
    private String agentId;

    @Schema(description = "发起用户 ID")
    @TableField("user_id")
    private String userId;

    @Schema(description = "状态：RUNNING / SUCCESS / FAILED")
    @TableField("status")
    private String status;

    @Schema(description = "输入 token 数")
    @TableField("input_tokens")
    private Long inputTokens;

    @Schema(description = "输出 token 数")
    @TableField("output_tokens")
    private Long outputTokens;

    @Schema(description = "总 token 数")
    @TableField("total_tokens")
    private Long totalTokens;

    @Schema(description = "估算成本（USD）")
    @TableField("cost_usd")
    private BigDecimal costUsd;

    @Schema(description = "工具调用轮数")
    @TableField("tool_rounds")
    private Integer toolRounds;

    @Schema(description = "产物数量")
    @TableField("artifact_count")
    private Integer artifactCount;

    @Schema(description = "总耗时（毫秒）")
    @TableField("elapsed_ms")
    private Long elapsedMs;

    @Schema(description = "失败原因（可空）")
    @TableField("error_msg")
    private String errorMsg;
}

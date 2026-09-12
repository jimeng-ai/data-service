package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * Skill 评测运行记录。用例本身不入库——它们在 skill 包的 {@code evals/evals.json} 里，
 * 跟着 skill 一起分发与版本化（沿用 Anthropic skill-creator 的布局）。
 *
 * @TableName skill_eval_run
 */
@Schema(description = "Skill 评测运行记录")
@EqualsAndHashCode(callSuper = true)
@TableName("skill_eval_run")
@Data
public class SkillEvalRun extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "ai_skill.id；评测构建器草稿时为空")
    @TableField("skill_id")
    private Long skillId;

    @Schema(description = "构建器会话 id；评测已发布 skill 时为空")
    @TableField("conversation_id")
    private Long conversationId;

    @TableField("skill_name")
    private String skillName;

    @Schema(description = "RECALL=不提 skill 名，测模型会不会想到用；CAPABILITY=明确要求用")
    @TableField("mode")
    private String mode;

    @Schema(description = "被测内容（body + files）的 sha256；发布门槛靠它判定评测是否已过期")
    @TableField("content_hash")
    private String contentHash;

    @Schema(description = "RUNNING | COMPLETED | FAILED")
    @TableField("status")
    private String status;

    @TableField("total_cases")
    private Integer totalCases;

    @TableField("finished_cases")
    private Integer finishedCases;

    @TableField("passed_cases")
    private Integer passedCases;

    @TableField("pass_rate")
    private BigDecimal passRate;

    @TableField("result_json")
    private String resultJson;

    @TableField("error")
    private String error;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 与技能的多对多绑定。承接插件下线后失去的「按 Agent 限定工具范围」能力。
 *
 * <p>存 {@code skill_id} 而非 name：{@code ai_skill} 刻意没有 name 唯一键，同名行可共存，
 * 用 name 做绑定键会出现「绑的到底是哪一个」。给模型看的仍是 name。
 *
 * <p>绑定只收窄可见性、不放大：scope/owner 过滤（{@code DbTenantSkillSourceProvider.filterVisible}）
 * 在绑定过滤之前执行，PRIVATE 技能仍然只对 owner 可见。
 *
 * @TableName agent_skill
 */
@Schema(description = "Agent 与技能绑定表")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill")
@Data
public class AgentSkill extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "Agent ID")
    @TableField("agent_id")
    private Long agentId;

    @Schema(description = "技能 ID（ai_skill.id）")
    @TableField("skill_id")
    private Long skillId;
}

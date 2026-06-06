package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 实体（岗位智能体）。
 *
 * <p>每个 Agent 是一份"人设 + 默认模型参数 + 可用插件集合"的配置。
 * 业务请求带 {@code agent_id} → ClaudeService 加载这份配置 → 注入到 Claude 请求。
 *
 * @TableName agent
 */
@Schema(description = "Agent 实体表")
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
@Data
public class Agent extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "Agent slug，租户内唯一")
    @TableField("code")
    private String code;

    @Schema(description = "Agent 展示名")
    @TableField("name")
    private String name;

    @Schema(description = "Agent 描述")
    @TableField("description")
    private String description;

    @Schema(description = "头像 URL")
    @TableField("avatar_url")
    private String avatarUrl;

    @Schema(description = "对话空状态的预设引导问题（JSON 数组字符串，如 [\"问题1\",\"问题2\"]）")
    @TableField("preset_questions")
    private String presetQuestions;

    @Schema(description = "人设 / 系统提示词")
    @TableField("system_prompt")
    private String systemPrompt;

    @Schema(description = "默认模型（如 claude-opus-4-1）；请求体可 override")
    @TableField("model")
    private String model;

    @Schema(description = "模型参数默认值 {temperature, max_tokens, top_p, ...}（JSON）")
    @TableField("model_params")
    private String modelParams;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED")
    @TableField("status")
    private String status;

    @Schema(description = "Agent 所有者用户 ID")
    @TableField("owner_id")
    private String ownerId;

    @Schema(description = "知识库绑定配置 JSON：{kbIds:[...], topK, scoreThreshold, rerank}")
    @TableField("kb_config")
    private String kbConfig;

    /**
     * 发布快照（JSON）：发布那一刻冻结的完整运行配置。
     *
     * <p>调试台读 Agent 实时字段（草稿），对话端只读这份快照——保证"保存草稿"只在调试台生效，
     * 必须"发布"后改动才对终端用户可见。结构见
     * {@code AgentService#buildPublishSnapshot}：{code,name,systemPrompt,model,modelParams,kbConfig,pluginIds}。
     * 为空 = 从未发布过。
     */
    @Schema(description = "发布快照 JSON：发布时冻结的运行配置；为空表示从未发布")
    @TableField("published_snapshot")
    private String publishedSnapshot;

    /**
     * 是否「已发布但存在未发布的草稿改动」——非持久化，由 {@code AgentService} 即时计算后回填。
     *
     * <p>true = status=PUBLISHED 且当前实时配置/插件绑定与发布快照不一致（编辑器/对话端可据此提示「有草稿未发布」）。
     * 非 PUBLISHED 时恒为 false（它本就是草稿，另行展示）。
     */
    @Schema(description = "是否存在未发布的草稿改动（已发布且实时配置与快照不一致）")
    @TableField(exist = false)
    private Boolean hasUnpublishedChanges;
}

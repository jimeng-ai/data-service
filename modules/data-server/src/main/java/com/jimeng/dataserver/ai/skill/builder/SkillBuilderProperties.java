package com.jimeng.dataserver.ai.skill.builder;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill 构建器（沙箱里原样跑 Anthropic skill-creator）的配置，Nacos 前缀 {@code skill.builder}。
 *
 * <p>与 {@code agent.sandbox.*} 分开：构建器一轮常常要跑十几二十分钟（对照运行 + 评分 + 触发优化），
 * 对话链路的 900s / 60 轮 / $5 不够用；而把全局的调大，又会让每一次普通对话的上限一起变松。
 * LLM 的 base-url / token 仍取 {@code agent.sandbox.llm}（同一个沙箱出口），只允许换模型。
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill.builder")
public class SkillBuilderProperties {

    /** 一轮的墙钟上限（秒）。边车按它 docker kill；data-service 的等待闩锁 = 它 + 60s。 */
    private int wallClockSec = 3600;
    /** 一轮的 CLI 轮数上限（主 agent；子 agent 各自计）。skill-creator 一次完整迭代轻松过百。 */
    private int maxTurns = 400;
    /** 一轮的 CLI 预算上限（美元）。注意它管不到容器里的 `claude -p` 子进程与触发测试的直连调用。 */
    private double maxBudgetUsd = 20;
    /** 构建器模型；空 = agent.sandbox.llm.model。 */
    private String model;
    /**
     * PROMPT 类触发测试用的模型：应当是租户 Agent 在对话平面实际用的那个（触发与否强依赖模型）。
     * 空 = 与构建器同一个模型。走的是沙箱的 LLM 出口，所以必须是那个出口认得的模型名。
     */
    private String triggerModel;
    /** 触发测试里与被测 skill 竞争的候选上限（生产发现阶段超过 30 个会截断，留一个位置给被测 skill）。 */
    private int triggerCandidatesMax = 29;
    /** DOER 类触发测试（每条一个 `claude -p` 进程）的并发上限，随上下文下发给边车。 */
    private int cliMaxWorkers = 3;
    /** 工作区所在的 MinIO 前缀根；实际前缀 = {root}/{tenantId}/{sessionId}/ws/。 */
    private String workspaceRoot = "skill-builder";
    /** 已发布 / 已放弃的会话，工作区保留多少天后清理。 */
    private int finishedRetentionDays = 7;
    /** 仍在进行（ACTIVE）但多久没动过的会话，工作区连同 DRAFT 一起清理。 */
    private int idleRetentionDays = 30;
    /** 评测运行的临时物化前缀（skills/eval/）保留多少天。 */
    private int evalMaterializationRetentionDays = 3;
    /** 评审页 HTML 的大小上限（字节），超过就不整页返回（skill-creator 会把产出嵌进页面，可能很大）。 */
    private long reviewMaxBytes = 30L * 1024 * 1024;
    /** 草稿预览里单个文本文件内容的上限（字节），超过只给元数据。 */
    private long previewFileMaxBytes = 512L * 1024;
}

package com.jimeng.dataserver.ai.skill.model;

import java.util.List;

/**
 * "工具包"统一抽象：可以是磁盘上的 SkillPackage（代码型能力），
 * 也可以是 DB 里的插件（{@code com.jimeng.dataserver.ai.plugin.source.PluginToolPackage}）。
 *
 * <p>未来如果接入 MCP 协议，只需再加一个 ToolPackage 实现即可，
 * SkillRuntimeService 的聚合/激活/合并主链路完全不动。
 */
public interface ToolPackage {

    /** 工具包唯一名（在租户内或全局唯一） */
    String getName();

    /** 给 LLM 看的简短描述（discovery 阶段列表里显示） */
    String getDescription();

    /** 完整 guidance 文本（激活后注入到 system prompt） */
    String getBody();

    /** 包含的工具定义列表 */
    List<SkillToolDefinition> getTools();

    /**
     * 租户 ID。返回 null 表示全局可见（如代码型 Skill）。
     * 业务层用这个字段做租户过滤。
     */
    default String getTenantId() {
        return null;
    }

    /**
     * 该工具包在其来源里的主键（DB 型技能 = ai_skill.id）。磁盘型平台技能没有 DB 行，返回 null。
     *
     * <p>按 Agent 过滤时用它而不是 name：ai_skill 刻意没有 name 唯一键，同名行可共存，
     * 按 name 匹配会出现「绑的到底是哪一个」。name 仍是给模型看的标识。
     */
    default Long getSourceId() {
        return null;
    }

    /**
     * 这个工具包需要 Agent 先绑定哪一类资源才生效（SKILL.md frontmatter 的 {@code requires:}）。
     *
     * <p>返回 null = 没有前置资源，走正常的 discovery→activate 流程。
     * 返回非 null 时由 {@code SkillRuntimeService} 判定：
     * <b>资源已绑 → 提升为直接注入</b>（工具立刻在手上，省掉 activate 往返）；
     * <b>资源未绑 → 整个摘除</b>（没有资源可用，暴露它只会诱导模型盲调一次必然失败的工具）。
     *
     * <p>DB 型技能暂不支持声明前置资源，默认 null。
     */
    default SkillRequirement getRequires() {
        return null;
    }

    /**
     * 工具包类型：{@link ToolPackageKind#SKILL} 走 discovery→activate 流程；
     * {@link ToolPackageKind#PLUGIN} 直接注入 tool_use。
     * 默认 SKILL，插件实现类覆盖返回 PLUGIN。
     */
    default ToolPackageKind getKind() {
        return ToolPackageKind.SKILL;
    }
}

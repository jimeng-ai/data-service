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
     * 工具包类型：{@link ToolPackageKind#SKILL} 走 discovery→activate 流程；
     * {@link ToolPackageKind#PLUGIN} 直接注入 tool_use。
     * 默认 SKILL，插件实现类覆盖返回 PLUGIN。
     */
    default ToolPackageKind getKind() {
        return ToolPackageKind.SKILL;
    }
}

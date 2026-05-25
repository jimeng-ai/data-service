package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.skill.model.ToolPackage;

import java.util.List;

/**
 * "工具包来源"统一接口。
 *
 * <p>当前实现：
 * <ul>
 *   <li>{@link FileSkillSourceProvider}：磁盘上的 SKILL.md / tools.json（gaode-poi / rag-knowledge 等）</li>
 *   <li>{@link com.jimeng.dataserver.ai.plugin.source.DbPluginSourceProvider}：DB 里的 HTTP 插件</li>
 * </ul>
 *
 * <p>未来加 MCP 来源时实现新的 Provider 即可，{@code ToolPackageRegistry} 自动聚合。
 */
public interface ToolSourceProvider {

    /**
     * 返回当前请求（租户）可见的工具包。
     * 不需要的实现可以返回固定列表（如代码型 Skill 全局可见），
     * 租户感知的实现可以从 {@link com.jimeng.common.core.tenant.TenantContext} 读当前租户后过滤。
     */
    List<ToolPackage> getPackages();
}

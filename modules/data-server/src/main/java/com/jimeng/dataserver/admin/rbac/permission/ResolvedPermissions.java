package com.jimeng.dataserver.admin.rbac.permission;

import com.jimeng.dataserver.admin.rbac.enums.ResourceType;

import java.util.Collections;
import java.util.Set;

/**
 * 某账号在某租户下解析出的有效权限快照。
 *
 * <p>超管（{@link #superAdmin}=true）：不受实例限制，可进入所有模块。
 * 成员：仅其角色被授予的模块码 + 实例 id 集合。
 */
public class ResolvedPermissions {

    private final boolean superAdmin;
    private final String userType;
    private final Set<String> modules;
    private final Set<Long> agentIds;
    private final Set<Long> knowledgeBaseIds;
    private final Set<Long> pluginIds;

    public ResolvedPermissions(boolean superAdmin, String userType, Set<String> modules,
                               Set<Long> agentIds, Set<Long> knowledgeBaseIds, Set<Long> pluginIds) {
        this.superAdmin = superAdmin;
        this.userType = userType;
        this.modules = modules == null ? Collections.emptySet() : modules;
        this.agentIds = agentIds == null ? Collections.emptySet() : agentIds;
        this.knowledgeBaseIds = knowledgeBaseIds == null ? Collections.emptySet() : knowledgeBaseIds;
        this.pluginIds = pluginIds == null ? Collections.emptySet() : pluginIds;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public String getUserType() {
        return userType;
    }

    public Set<String> getModules() {
        return modules;
    }

    public Set<Long> getAgentIds() {
        return agentIds;
    }

    public Set<Long> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public Set<Long> getPluginIds() {
        return pluginIds;
    }

    /** 是否可进入某模块（超管恒 true）。 */
    public boolean canEnter(String moduleCode) {
        return superAdmin || modules.contains(moduleCode);
    }

    /** 某资源类型下被授权的实例 id 集合（仅成员有意义；超管应在调用方直接 bypass）。 */
    public Set<Long> permittedIds(ResourceType type) {
        return switch (type) {
            case AGENT -> agentIds;
            case KNOWLEDGE_BASE -> knowledgeBaseIds;
            case PLUGIN -> pluginIds;
            case SKILL -> Collections.emptySet();
            case MENU -> Collections.emptySet();
        };
    }

    /** 成员是否被授权访问某实例（超管恒 true）。 */
    public boolean canAccess(ResourceType type, Long resourceId) {
        return superAdmin || permittedIds(type).contains(resourceId);
    }
}

package com.jimeng.dataserver.admin.rbac.enums;

/**
 * 角色可授权的资源类型（{@code sys_role_resource.resource_type}）。可扩展。
 *
 * <ul>
 *   <li>{@link #MENU}：模块/菜单授权，resource_id=0，resource_code 存模块码（见 PlatformConstant）。</li>
 *   <li>其余：实例级授权，resource_id 为对应实例的雪花 id。</li>
 * </ul>
 */
public enum ResourceType {
    MENU,
    AGENT,
    KNOWLEDGE_BASE,
    PLUGIN,
    SKILL
}

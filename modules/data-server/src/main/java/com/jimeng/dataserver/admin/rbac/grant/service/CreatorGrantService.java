package com.jimeng.dataserver.admin.rbac.grant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.entity.SysUserRole;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 创建者自授权：成员新建实例资源（Agent / 知识库 / 插件）后，把该实例授权给其名下所有角色，
 * 使创建者（及同角色成员）能立即在「列表 / 详情」中访问自己刚建的资源。
 *
 * <p>背景：实例级访问由 {@link com.jimeng.dataserver.admin.rbac.permission.PermissionResolver}
 * 依据 {@code sys_role_resource} 授权解析——成员的列表被 {@code filterCurrent} 过滤、单资源被
 * {@code assertCurrentAccess} 校验。创建接口若不写入授权，成员建完资源后：列表里看不到、
 * 读详情抛 4001（前端表现为「知识库列表为空」「保存插件后读回详情失败」）。这里在创建事务内补上授权。
 *
 * <p>超管不受实例限制（{@code filterCurrent}/{@code assertCurrentAccess} 对超管恒放行），无需授权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorGrantService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleResourceMapper sysRoleResourceMapper;

    /**
     * 把新建实例授权给当前请求账号的所有角色。建议在创建实体的同一事务中调用。
     *
     * @param type       资源类型（AGENT / KNOWLEDGE_BASE / PLUGIN；MENU 不适用）
     * @param resourceId 新建实例的雪花 id
     */
    public void grantNewResourceToCreator(ResourceType type, Long resourceId) {
        if (type == null || type == ResourceType.MENU || resourceId == null) {
            return;
        }
        Long userId = AdminRequestContext.requireUserId();
        String tenantId = AdminRequestContext.requireTenantId();

        // sys_user 不带租户列，按系统态查避免被租户拦截器加 WHERE（与 PermissionResolver 一致）。
        SysUser u = TenantContext.runAsSystem(() -> sysUserMapper.selectById(userId));
        if (u == null || SysUser.TYPE_SUPER_ADMIN.equals(u.getUserType())) {
            return; // 账号异常或超管：超管不受实例限制，无需授权
        }

        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId)
                .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            // 理论上成员能进到创建入口必然挂着角色（模块菜单也是角色授权）；兜底记一笔便于排查。
            log.warn("成员 {} 在租户 {} 下无任何角色，新建 {}#{} 无法自授权，创建者将看不到该资源",
                    userId, tenantId, type, resourceId);
            return;
        }
        for (SysUserRole ur : userRoles) {
            SysRoleResource row = new SysRoleResource();
            row.setTenantId(tenantId);
            row.setRoleId(ur.getRoleId());
            row.setResourceType(type.name());
            row.setResourceId(resourceId);
            sysRoleResourceMapper.insert(row);
        }
    }
}

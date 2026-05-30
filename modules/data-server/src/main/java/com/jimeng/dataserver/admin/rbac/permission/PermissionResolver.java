package com.jimeng.dataserver.admin.rbac.permission;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
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
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 权限解析：按 {@code user-id} + {@code X-Tenant-Id} 当场解析账号的有效权限。
 *
 * <p>不把权限塞进 JWT——token 小、改权限即时生效。被 {@code /me/permissions} 和各业务 service
 * （Agent/KB/Plugin 列表与单资源访问）复用。
 */
@Service
@RequiredArgsConstructor
public class PermissionResolver {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleResourceMapper sysRoleResourceMapper;

    /** 解析当前请求账号的权限（读 gateway 注入的头）。 */
    public ResolvedPermissions resolveCurrent() {
        return resolve(AdminRequestContext.requireUserId(), AdminRequestContext.requireTenantId());
    }

    /**
     * 按当前账号的实例授权过滤列表（超管返回全部）。用于 Agent/KB/Plugin 的「浏览/列表」接口。
     */
    public <T> List<T> filterCurrent(List<T> items, ResourceType type, Function<T, Long> idFn) {
        ResolvedPermissions p = resolveCurrent();
        if (p.isSuperAdmin()) {
            return items;
        }
        Set<Long> allowed = p.permittedIds(type);
        return items.stream().filter(it -> allowed.contains(idFn.apply(it))).collect(Collectors.toList());
    }

    /** 断言当前账号可访问某实例（超管恒通过），否则抛 4001。 */
    public void assertCurrentAccess(ResourceType type, Long id) {
        if (!resolveCurrent().canAccess(type, id)) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无权访问该资源");
        }
    }

    public ResolvedPermissions resolve(Long userId, String tenantId) {
        SysUser u = TenantContext.runAsSystem(() -> sysUserMapper.selectById(userId));
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号不存在或已禁用");
        }
        if (SysUser.TYPE_SUPER_ADMIN.equals(u.getUserType())) {
            return new ResolvedPermissions(true, u.getUserType(),
                    new HashSet<>(PlatformConstant.ALL_MODULES), null, null, null);
        }

        // 成员：取角色 → 取角色的资源授权
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId)
                .eq(SysUserRole::getUserId, userId));
        Set<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        if (roleIds.isEmpty()) {
            return new ResolvedPermissions(false, u.getUserType(), Set.of(), Set.of(), Set.of(), Set.of());
        }
        List<SysRoleResource> grants = sysRoleResourceMapper.selectList(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .in(SysRoleResource::getRoleId, roleIds));

        Set<String> modules = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        Set<Long> kbIds = new HashSet<>();
        Set<Long> pluginIds = new HashSet<>();
        for (SysRoleResource g : grants) {
            ResourceType type = parseType(g.getResourceType());
            if (type == null) {
                continue;
            }
            switch (type) {
                case MENU -> { if (g.getResourceCode() != null) modules.add(g.getResourceCode()); }
                case AGENT -> agentIds.add(g.getResourceId());
                case KNOWLEDGE_BASE -> kbIds.add(g.getResourceId());
                case PLUGIN -> pluginIds.add(g.getResourceId());
            }
        }
        return new ResolvedPermissions(false, u.getUserType(), modules, agentIds, kbIds, pluginIds);
    }

    private ResourceType parseType(String raw) {
        try {
            return ResourceType.valueOf(raw);
        } catch (Exception e) {
            return null;
        }
    }
}

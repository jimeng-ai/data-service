package com.jimeng.dataserver.admin.rbac.grant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源分享：以【资源】为中心管理 {@code sys_role_resource} 授权——把某个 Agent / 插件 / 知识库
 * 分享给若干角色(部门)，或设为「全公司可见」(哨兵 {@code role_id = 0}，见 {@link PermissionResolver})。
 *
 * <p>与 {@code RoleResourceService}（以角色为中心整体覆盖某角色的授权）互补；二者写的是同一张表，
 * 但本服务只增删【某个资源】对应的行，不会动其他资源的授权。
 */
@Service
@RequiredArgsConstructor
public class ResourceShareService {

    private final SysRoleResourceMapper sysRoleResourceMapper;
    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PluginMapper pluginMapper;

    /** 读取某资源当前分享给了哪些角色(部门) + 是否全公司可见。 */
    public ShareView getShares(String tenantId, ResourceType type, Long resourceId) {
        requireInstanceType(type);
        List<SysRoleResource> rows = sysRoleResourceMapper.selectList(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .eq(SysRoleResource::getResourceType, type.name())
                .eq(SysRoleResource::getResourceId, resourceId));
        List<Long> roleIds = new ArrayList<>();
        boolean tenantWide = false;
        for (SysRoleResource r : rows) {
            if (r.getRoleId() == null) {
                continue;
            }
            if (r.getRoleId() == PermissionResolver.TENANT_WIDE_ROLE_ID) {
                tenantWide = true;
            } else {
                roleIds.add(r.getRoleId());
            }
        }
        return new ShareView(roleIds, tenantWide);
    }

    /**
     * 整体覆盖某资源的分享设置：先删该资源的全部授权(含哨兵)，再按入参重建。
     * 注意是「覆盖」——前端须把已勾选的部门(含资源所属部门)一并回传，否则会丢失访问权。
     */
    @Transactional
    public void setShares(String tenantId, ResourceType type, Long resourceId,
                          List<Long> roleIds, boolean tenantWide) {
        requireInstanceType(type);
        requireResourceInTenant(type, resourceId);
        sysRoleResourceMapper.delete(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .eq(SysRoleResource::getResourceType, type.name())
                .eq(SysRoleResource::getResourceId, resourceId));
        if (roleIds != null) {
            for (Long rid : roleIds) {
                if (rid == null || rid == PermissionResolver.TENANT_WIDE_ROLE_ID) {
                    continue; // role_id=0 统一由 tenantWide 开关控制
                }
                insert(tenantId, rid, type, resourceId);
            }
        }
        if (tenantWide) {
            insert(tenantId, PermissionResolver.TENANT_WIDE_ROLE_ID, type, resourceId);
        }
    }

    private void insert(String tenantId, Long roleId, ResourceType type, Long resourceId) {
        SysRoleResource row = new SysRoleResource();
        row.setTenantId(tenantId);
        row.setRoleId(roleId);
        row.setResourceType(type.name());
        row.setResourceId(resourceId);
        sysRoleResourceMapper.insert(row);
    }

    private void requireInstanceType(ResourceType type) {
        if (type == null || type == ResourceType.MENU) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "不支持的分享资源类型");
        }
    }

    /** 实例 id 必须属于本租户（三表均在租户白名单，跨租户 id 查不到即拒绝）。 */
    private void requireResourceInTenant(ResourceType type, Long id) {
        boolean exists = switch (type) {
            case AGENT -> agentMapper.selectById(id) != null;
            case KNOWLEDGE_BASE -> knowledgeBaseMapper.selectById(id) != null;
            case PLUGIN -> pluginMapper.selectById(id) != null;
            default -> false;
        };
        if (!exists) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "资源不存在或不属于本企业：" + id);
        }
    }

    /** 分享视图：分享到的角色(部门) id 列表 + 是否全公司可见。 */
    @Data
    @AllArgsConstructor
    public static class ShareView {
        private List<Long> roleIds;
        private boolean tenantWide;
    }
}

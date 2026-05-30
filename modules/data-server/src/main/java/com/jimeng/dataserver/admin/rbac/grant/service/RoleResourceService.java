package com.jimeng.dataserver.admin.rbac.grant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.grant.dto.GrantRequest;
import com.jimeng.dataserver.admin.rbac.grant.dto.GrantView;
import com.jimeng.dataserver.admin.rbac.role.service.RoleService;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色资源授权：读取 / 整体覆盖某角色的模块与实例授权。
 *
 * <p>实例 id 校验走租户过滤的 {@code AgentMapper}/{@code KnowledgeBaseMapper}/{@code PluginMapper}
 * （这三表在白名单内，当前超管请求上下文已注入 {@code WHERE tenant_id=?}），跨租户 id 查不到即拒绝。
 */
@Service
@RequiredArgsConstructor
public class RoleResourceService {

    private final SysRoleResourceMapper sysRoleResourceMapper;
    private final RoleService roleService;
    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PluginMapper pluginMapper;

    public GrantView getGrants(String tenantId, Long roleId) {
        roleService.requireRoleForGrant(tenantId, roleId);
        List<SysRoleResource> rows = sysRoleResourceMapper.selectList(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .eq(SysRoleResource::getRoleId, roleId));
        List<String> modules = new ArrayList<>();
        List<Long> agents = new ArrayList<>();
        List<Long> kbs = new ArrayList<>();
        List<Long> plugins = new ArrayList<>();
        for (SysRoleResource r : rows) {
            switch (r.getResourceType()) {
                case "MENU" -> { if (r.getResourceCode() != null) modules.add(r.getResourceCode()); }
                case "AGENT" -> agents.add(r.getResourceId());
                case "KNOWLEDGE_BASE" -> kbs.add(r.getResourceId());
                case "PLUGIN" -> plugins.add(r.getResourceId());
                default -> { /* ignore unknown */ }
            }
        }
        return GrantView.builder().modules(modules).agents(agents).knowledgeBases(kbs).plugins(plugins).build();
    }

    @Transactional
    public void setGrants(String tenantId, Long roleId, GrantRequest req) {
        roleService.requireRoleForGrant(tenantId, roleId);
        if (req == null) {
            req = new GrantRequest();
        }
        // 校验模块码
        List<String> modules = nullToEmpty(req.getModules());
        for (String m : modules) {
            if (!PlatformConstant.ALL_MODULES.contains(m)) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未知模块码：" + m);
            }
        }
        // 校验实例 id 属本租户
        validateInstances(nullToEmpty(req.getAgents()), id -> agentMapper.selectById(id) != null, "智能体");
        validateInstances(nullToEmpty(req.getKnowledgeBases()), id -> knowledgeBaseMapper.selectById(id) != null, "知识库");
        validateInstances(nullToEmpty(req.getPlugins()), id -> pluginMapper.selectById(id) != null, "插件");

        // 整体覆盖：先软删旧授权，再插新授权
        sysRoleResourceMapper.delete(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .eq(SysRoleResource::getRoleId, roleId));

        for (String m : modules) {
            insertGrant(tenantId, roleId, ResourceType.MENU, 0L, m);
        }
        for (Long id : nullToEmpty(req.getAgents())) {
            insertGrant(tenantId, roleId, ResourceType.AGENT, id, null);
        }
        for (Long id : nullToEmpty(req.getKnowledgeBases())) {
            insertGrant(tenantId, roleId, ResourceType.KNOWLEDGE_BASE, id, null);
        }
        for (Long id : nullToEmpty(req.getPlugins())) {
            insertGrant(tenantId, roleId, ResourceType.PLUGIN, id, null);
        }
    }

    private void insertGrant(String tenantId, Long roleId, ResourceType type, Long resourceId, String code) {
        SysRoleResource row = new SysRoleResource();
        row.setTenantId(tenantId);
        row.setRoleId(roleId);
        row.setResourceType(type.name());
        row.setResourceId(resourceId);
        row.setResourceCode(code);
        sysRoleResourceMapper.insert(row);
    }

    private void validateInstances(List<Long> ids, java.util.function.Predicate<Long> exists, String label) {
        for (Long id : ids) {
            if (id == null || !exists.test(id)) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, label + "不存在或不属于本企业：" + id);
            }
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

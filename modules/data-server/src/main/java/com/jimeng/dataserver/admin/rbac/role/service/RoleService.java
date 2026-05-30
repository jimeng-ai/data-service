package com.jimeng.dataserver.admin.rbac.role.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.SysRole;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.entity.SysUserRole;
import com.jimeng.persistence.mapper.SysRoleMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import com.jimeng.dataserver.admin.rbac.role.dto.RoleUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 企业自定义角色 CRUD（租户内）。{@code sys_role} 不在租户白名单内，全部显式拼 {@code tenant_id}。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysRoleResourceMapper sysRoleResourceMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    public SysRole create(String tenantId, RoleUpsertRequest req) {
        if (req == null || StrUtil.isBlank(req.getName())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "角色名不能为空");
        }
        String code = StrUtil.isNotBlank(req.getCode())
                ? req.getCode().trim()
                : "role_" + IdUtil.fastSimpleUUID().substring(0, 8);
        if (codeExists(tenantId, code, null)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "角色标识已存在：" + code);
        }
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setCode(code);
        role.setName(req.getName().trim());
        role.setDescription(req.getDescription());
        sysRoleMapper.insert(role);
        return role;
    }

    public SysRole update(String tenantId, Long id, RoleUpsertRequest req) {
        SysRole role = requireRole(tenantId, id);
        if (StrUtil.isNotBlank(req.getCode()) && !req.getCode().trim().equals(role.getCode())) {
            if (codeExists(tenantId, req.getCode().trim(), id)) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "角色标识已存在：" + req.getCode());
            }
            role.setCode(req.getCode().trim());
        }
        if (StrUtil.isNotBlank(req.getName())) {
            role.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            role.setDescription(req.getDescription());
        }
        sysRoleMapper.updateById(role);
        return sysRoleMapper.selectById(id);
    }

    public List<SysRole> list(String tenantId) {
        return sysRoleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantId, tenantId)
                .orderByDesc(SysRole::getCreateTime));
    }

    public SysRole get(String tenantId, Long id) {
        return requireRole(tenantId, id);
    }

    @Transactional
    public void delete(String tenantId, Long id) {
        requireRole(tenantId, id);
        // 级联软删该角色的资源授权与成员绑定
        sysRoleResourceMapper.delete(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .eq(SysRoleResource::getRoleId, id));
        sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId)
                .eq(SysUserRole::getRoleId, id));
        sysRoleMapper.deleteById(id);
    }

    // ------------------------------------------------------------------ helpers

    private SysRole requireRole(String tenantId, Long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null || !tenantId.equals(role.getTenantId())) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "角色不存在: " + id);
        }
        return role;
    }

    private boolean codeExists(String tenantId, String code, Long excludeId) {
        Long c = sysRoleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantId, tenantId)
                .eq(SysRole::getCode, code)
                .ne(excludeId != null, SysRole::getId, excludeId));
        return c != null && c > 0;
    }

    /** 给授权服务用：确认角色属于本租户。 */
    public SysRole requireRoleForGrant(String tenantId, Long id) {
        return requireRole(tenantId, id);
    }
}

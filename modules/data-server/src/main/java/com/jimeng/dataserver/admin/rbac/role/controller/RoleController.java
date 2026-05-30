package com.jimeng.dataserver.admin.rbac.role.controller;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.role.dto.RoleUpsertRequest;
import com.jimeng.dataserver.admin.rbac.role.service.RoleService;
import com.jimeng.persistence.entity.SysRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 企业门户 —— 自定义角色管理（超管）。
 */
@Tag(name = "企业-角色管理", description = "自定义角色 CRUD")
@RestController
@RequestMapping("/data/admin/rbac/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "新建角色")
    @PostMapping
    public SysRole create(@RequestBody RoleUpsertRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return roleService.create(tenantId, req);
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    public SysRole update(@PathVariable Long id, @RequestBody RoleUpsertRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return roleService.update(tenantId, id, req);
    }

    @Operation(summary = "角色列表")
    @GetMapping
    public List<SysRole> list() {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return roleService.list(tenantId);
    }

    @Operation(summary = "角色详情")
    @GetMapping("/{id}")
    public SysRole get(@PathVariable Long id) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return roleService.get(tenantId, id);
    }

    @Operation(summary = "删除角色（级联解除授权与成员绑定）")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        roleService.delete(tenantId, id);
        return Map.of("deleted", true);
    }
}

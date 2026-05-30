package com.jimeng.dataserver.admin.rbac.grant.controller;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.grant.dto.GrantRequest;
import com.jimeng.dataserver.admin.rbac.grant.dto.GrantView;
import com.jimeng.dataserver.admin.rbac.grant.service.RoleResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 企业门户 —— 角色授权（模块 + 智能体/知识库/插件实例）。
 */
@Tag(name = "企业-角色授权", description = "读取 / 整体覆盖某角色的授权")
@RestController
@RequestMapping("/data/admin/rbac/roles/{roleId}/grants")
@RequiredArgsConstructor
public class RoleGrantController {

    private final RoleResourceService roleResourceService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "读取角色授权")
    @GetMapping
    public GrantView get(@PathVariable Long roleId) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return roleResourceService.getGrants(tenantId, roleId);
    }

    @Operation(summary = "设置角色授权（整体覆盖）")
    @PutMapping
    public Map<String, Object> set(@PathVariable Long roleId, @RequestBody GrantRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        roleResourceService.setGrants(tenantId, roleId, req);
        return Map.of("updated", true);
    }
}

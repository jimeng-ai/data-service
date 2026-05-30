package com.jimeng.dataserver.admin.rbac.member.controller;

import com.jimeng.dataserver.admin.operator.enterprise.dto.ResetPasswordRequest;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.member.dto.AssignRolesRequest;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberCreateRequest;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberUpdateRequest;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberView;
import com.jimeng.dataserver.admin.rbac.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
 * 企业门户 —— 成员账号管理（超管）。
 */
@Tag(name = "企业-成员管理", description = "成员 CRUD / 启停 / 重置密码 / 分配角色")
@RestController
@RequestMapping("/data/admin/rbac/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "新建成员")
    @PostMapping
    public MemberView create(@RequestBody MemberCreateRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return memberService.create(tenantId, req);
    }

    @Operation(summary = "更新成员")
    @PutMapping("/{id}")
    public MemberView update(@PathVariable Long id, @RequestBody MemberUpdateRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return memberService.update(tenantId, id, req);
    }

    @Operation(summary = "成员列表")
    @GetMapping
    public List<MemberView> list() {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return memberService.list(tenantId);
    }

    @Operation(summary = "成员详情")
    @GetMapping("/{id}")
    public MemberView get(@PathVariable Long id) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return memberService.get(tenantId, id);
    }

    @Operation(summary = "启用成员")
    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable Long id) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        memberService.setStatus(tenantId, id, true);
        return Map.of("status", 1);
    }

    @Operation(summary = "禁用成员")
    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable Long id) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        memberService.setStatus(tenantId, id, false);
        return Map.of("status", 0);
    }

    @Operation(summary = "重置成员密码")
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        memberService.resetPassword(tenantId, id, req.getNewPassword());
        return Map.of("reset", true);
    }

    @Operation(summary = "分配成员角色（整体覆盖）")
    @PutMapping("/{id}/roles")
    public MemberView assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest req) {
        String tenantId = superAdminGuard.requireSuperAdmin().getTenantId();
        return memberService.assignRoles(tenantId, id, req.getRoleIds());
    }
}

package com.jimeng.dataserver.admin.rbac.permission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前账号权限。jm-admin 与 jm-agent-front 登录后调用，用于菜单/模块门控。
 *
 * <p>仅企业账号（sys_user）可用；运营账号不在 sys_user 表，调用会 4001（运营门户按 realm 分支，不需要它）。
 */
@Tag(name = "当前账号权限", description = "解析当前登录账号的有效权限")
@RestController
@RequestMapping("/data/admin/me")
@RequiredArgsConstructor
public class MePermissionController {

    private final PermissionResolver permissionResolver;

    @Operation(summary = "获取当前账号的有效权限")
    @GetMapping("/permissions")
    public MePermissionsView permissions() {
        return MePermissionsView.from(permissionResolver.resolveCurrent());
    }
}

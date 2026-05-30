package com.jimeng.dataserver.admin.operator.auth.controller;

import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.operator.auth.service.OperatorAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台运营账号接口。
 *
 * <p>{@code POST /data/admin/operator/auth/login} 必须加入 gateway 白名单（{@code ignore.auth.whitesUrl}），
 * 其余走鉴权，沿用 gateway 注入的 {@code user-id} 头。
 */
@Tag(name = "平台运营账号", description = "运营登录 / 修改密码 / 当前用户 / 续期")
@RestController
@RequestMapping("/data/admin/operator/auth")
@RequiredArgsConstructor
public class OperatorAuthController {

    private final OperatorAuthService operatorAuthService;

    @Operation(summary = "运营登录")
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        return operatorAuthService.login(req);
    }

    @Operation(summary = "修改密码")
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest req) {
        operatorAuthService.changePassword(AdminRequestContext.requireUserId(), req);
        return Map.of("changed", true);
    }

    @Operation(summary = "获取当前登录运营")
    @GetMapping("/me")
    public LoginResponse.AdminUserView me() {
        return operatorAuthService.getCurrentUser(AdminRequestContext.requireUserId());
    }

    @Operation(summary = "滑动续期：用当前有效 token 换发新 token")
    @PostMapping("/refresh")
    public LoginResponse refresh() {
        return operatorAuthService.refresh(AdminRequestContext.requireUserId());
    }
}

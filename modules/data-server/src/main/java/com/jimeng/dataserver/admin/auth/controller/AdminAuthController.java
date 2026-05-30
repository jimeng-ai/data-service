package com.jimeng.dataserver.admin.auth.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台账户接口。
 *
 * <p>{@code POST /data/admin/auth/login} 必须加入 gateway 白名单（{@code ignore.auth.whitesUrl}），
 * 其余 {@code /data/admin/auth/**} 走鉴权，沿用 gateway 注入的 {@code user-id} 头。
 */
@Tag(name = "管理后台账户", description = "登录 / 修改密码 / 获取当前用户")
@RestController
@RequestMapping("/data/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        return adminAuthService.login(req);
    }

    @Operation(summary = "修改密码")
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestHeader(value = "user-id", required = false) String userIdHeader,
                                              @RequestBody ChangePasswordRequest req) {
        adminAuthService.changePassword(parseUserId(userIdHeader), req);
        return Map.of("changed", true);
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public LoginResponse.AdminUserView me(@RequestHeader(value = "user-id", required = false) String userIdHeader) {
        return adminAuthService.getCurrentUser(parseUserId(userIdHeader));
    }

    @Operation(summary = "滑动续期：用当前有效 token 换发新 token")
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestHeader(value = "user-id", required = false) String userIdHeader) {
        return adminAuthService.refresh(parseUserId(userIdHeader));
    }

    private Long parseUserId(String header) {
        if (StrUtil.isBlank(header) || "null".equals(header)) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "缺少 user-id 头");
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "user-id 头格式错误");
        }
    }
}

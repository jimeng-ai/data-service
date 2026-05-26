package com.jimeng.dataserver.admin.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台登录响应")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    @Schema(description = "JWT token，直接放进 Authorization 头使用（无 Bearer 前缀）")
    private String token;

    @Schema(description = "token 过期时长（秒）")
    private Long expiresIn;

    @Schema(description = "当前用户简档")
    private AdminUserView user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminUserView {
        @Schema(description = "用户 ID")
        private Long id;
        @Schema(description = "所属租户 ID")
        private String tenantId;
        @Schema(description = "登录名")
        private String username;
        @Schema(description = "展示名")
        private String displayName;
    }
}

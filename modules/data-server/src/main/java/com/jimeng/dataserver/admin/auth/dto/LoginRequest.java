package com.jimeng.dataserver.admin.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台登录请求")
@Data
public class LoginRequest {

    @Schema(description = "登录名")
    private String username;

    @Schema(description = "明文密码")
    private String password;
}

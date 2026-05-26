package com.jimeng.dataserver.admin.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "修改密码请求")
@Data
public class ChangePasswordRequest {

    @Schema(description = "旧密码")
    private String oldPassword;

    @Schema(description = "新密码（至少 6 位）")
    private String newPassword;
}

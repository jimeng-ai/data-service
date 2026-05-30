package com.jimeng.dataserver.admin.operator.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "重置密码请求")
@Data
public class ResetPasswordRequest {

    @Schema(description = "新密码（至少 6 位）")
    private String newPassword;
}

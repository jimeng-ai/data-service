package com.jimeng.dataserver.admin.operator.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "创建企业请求（同时创建其超级管理员账号）")
@Data
public class CreateEnterpriseRequest {

    @Schema(description = "企业名称")
    private String name;

    @Schema(description = "租户标识（可选，留空按名称自动生成）；[A-Za-z0-9._-] ≤64")
    private String tenantId;

    @Schema(description = "企业描述")
    private String description;

    @Schema(description = "超级管理员登录名（全局唯一，建议邮箱）")
    private String superAdminUsername;

    @Schema(description = "超级管理员初始密码（至少 6 位）")
    private String superAdminPassword;

    @Schema(description = "超级管理员展示名（可选）")
    private String superAdminDisplayName;
}

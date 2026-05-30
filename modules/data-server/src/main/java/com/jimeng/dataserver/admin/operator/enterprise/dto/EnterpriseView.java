package com.jimeng.dataserver.admin.operator.enterprise.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Schema(description = "企业视图（含超管登录名）")
@Data
@Builder
public class EnterpriseView {

    @Schema(description = "企业 ID")
    private Long id;

    @Schema(description = "租户标识")
    private String tenantId;

    @Schema(description = "企业名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "1=启用 0=停用")
    private Integer status;

    @Schema(description = "超级管理员登录名")
    private String superAdminUsername;

    @Schema(description = "创建时间")
    private Date createTime;
}

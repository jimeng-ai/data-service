package com.jimeng.dataserver.admin.rbac.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "角色新建/更新请求")
@Data
public class RoleUpsertRequest {

    @Schema(description = "角色 slug（租户内唯一）；更新时可不传")
    private String code;

    @Schema(description = "角色名")
    private String name;

    @Schema(description = "描述")
    private String description;
}

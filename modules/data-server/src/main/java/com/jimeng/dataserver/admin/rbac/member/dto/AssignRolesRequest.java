package com.jimeng.dataserver.admin.rbac.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "分配成员角色请求（整体覆盖）")
@Data
public class AssignRolesRequest {

    @Schema(description = "角色 id 列表")
    private List<Long> roleIds;
}

package com.jimeng.dataserver.admin.rbac.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "更新企业成员请求")
@Data
public class MemberUpdateRequest {

    @Schema(description = "展示名")
    private String displayName;
}

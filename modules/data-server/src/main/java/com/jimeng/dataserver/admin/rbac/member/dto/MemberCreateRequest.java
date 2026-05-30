package com.jimeng.dataserver.admin.rbac.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "创建企业成员请求")
@Data
public class MemberCreateRequest {

    @Schema(description = "登录名（全局唯一，建议邮箱）")
    private String username;

    @Schema(description = "初始密码（至少 6 位）")
    private String password;

    @Schema(description = "展示名（可选）")
    private String displayName;

    @Schema(description = "分配的角色 id 列表（可选）")
    private List<Long> roleIds;
}

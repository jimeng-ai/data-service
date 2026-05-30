package com.jimeng.dataserver.admin.rbac.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Schema(description = "企业成员视图")
@Data
@Builder
public class MemberView {

    @Schema(description = "成员 ID")
    private Long id;

    @Schema(description = "登录名")
    private String username;

    @Schema(description = "展示名")
    private String displayName;

    @Schema(description = "账号类型：MEMBER")
    private String userType;

    @Schema(description = "1=启用 0=禁用")
    private Integer status;

    @Schema(description = "已分配角色 id 列表")
    private List<Long> roleIds;

    @Schema(description = "最近登录时间")
    private Date lastLoginAt;

    @Schema(description = "创建时间")
    private Date createTime;
}

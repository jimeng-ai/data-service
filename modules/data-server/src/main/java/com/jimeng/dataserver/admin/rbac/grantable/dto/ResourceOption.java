package com.jimeng.dataserver.admin.rbac.grantable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "可授权资源选项")
@Data
@AllArgsConstructor
public class ResourceOption {

    @Schema(description = "实例 id")
    private Long id;

    @Schema(description = "实例 code（知识库可空）")
    private String code;

    @Schema(description = "展示名")
    private String name;
}

package com.jimeng.dataserver.admin.rbac.grantable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "可授权模块选项")
@Data
@AllArgsConstructor
public class ModuleOption {

    @Schema(description = "模块码")
    private String code;

    @Schema(description = "展示名")
    private String name;
}

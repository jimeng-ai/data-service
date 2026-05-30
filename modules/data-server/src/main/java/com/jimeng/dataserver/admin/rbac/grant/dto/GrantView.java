package com.jimeng.dataserver.admin.rbac.grant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Schema(description = "角色当前授权")
@Data
@Builder
public class GrantView {

    @Schema(description = "可进入的模块码")
    private List<String> modules;

    @Schema(description = "可用的智能体 id 列表")
    private List<Long> agents;

    @Schema(description = "可用的知识库 id 列表")
    private List<Long> knowledgeBases;

    @Schema(description = "可用的插件 id 列表")
    private List<Long> plugins;
}

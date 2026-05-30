package com.jimeng.dataserver.admin.rbac.grant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "角色授权设置（整体覆盖）")
@Data
public class GrantRequest {

    @Schema(description = "可进入的模块码（AGENT_MODULE / KB_MODULE / CHAT_MODULE / PLUGIN_MODULE）")
    private List<String> modules;

    @Schema(description = "可用的智能体 id 列表")
    private List<Long> agents;

    @Schema(description = "可用的知识库 id 列表")
    private List<Long> knowledgeBases;

    @Schema(description = "可用的插件 id 列表")
    private List<Long> plugins;
}

package com.jimeng.dataserver.admin.rbac.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Schema(description = "当前账号的有效权限")
@Data
@Builder
public class MePermissionsView {

    @Schema(description = "是否企业超管（true 则不受实例/模块限制）")
    private boolean superAdmin;

    @Schema(description = "账号类型：SUPER_ADMIN / MEMBER")
    private String userType;

    @Schema(description = "可进入的模块码列表")
    private List<String> modules;

    @Schema(description = "被授权的智能体 id 列表")
    private List<Long> agentIds;

    @Schema(description = "被授权的知识库 id 列表")
    private List<Long> knowledgeBaseIds;

    @Schema(description = "被授权的插件 id 列表")
    private List<Long> pluginIds;

    public static MePermissionsView from(ResolvedPermissions p) {
        return MePermissionsView.builder()
                .superAdmin(p.isSuperAdmin())
                .userType(p.getUserType())
                .modules(List.copyOf(p.getModules()))
                .agentIds(List.copyOf(p.getAgentIds()))
                .knowledgeBaseIds(List.copyOf(p.getKnowledgeBaseIds()))
                .pluginIds(List.copyOf(p.getPluginIds()))
                .build();
    }
}

package com.jimeng.dataserver.ai.agent.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.admin.common.UserNameResolver;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.AgentSkill;
import com.jimeng.dataserver.ai.connection.ConnectionService;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 管理后台接口：Agent CRUD + Agent-技能/连接绑定。
 */
@Tag(name = "Agent 管理", description = "ToB Agent 平台 - Agent CRUD + 技能/连接绑定")
@RestController
@RequestMapping("/data/admin/agent")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentService agentService;
    private final PermissionResolver permissionResolver;
    private final UserNameResolver userNameResolver;
    private final SkillTenantService skillTenantService;
    private final ConnectionService connectionService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "创建 Agent")
    @PostMapping("/agents")
    public Agent createAgent(@RequestBody Agent agent) {
        return agentService.create(agent);
    }

    @Operation(summary = "更新 Agent")
    @PutMapping("/agents/{id}")
    public Agent updateAgent(@PathVariable Long id, @RequestBody Agent agent) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        agent.setId(id);
        return agentService.update(agent);
    }

    @Operation(summary = "列出当前租户的 Agent（成员仅见被授权的）")
    @GetMapping("/agents")
    public List<Agent> list(@RequestParam(required = false) String status) {
        List<Agent> agents = permissionResolver.filterCurrent(
                agentService.list(status), ResourceType.AGENT, Agent::getId, Agent::getCreateUser);
        agentService.attachDirtyFlag(agents); // 回填 hasUnpublishedChanges（已发布但有未发布草稿）
        userNameResolver.fillCreatorNames(agents); // 回填创建人显示名
        return agents;
    }

    @Operation(summary = "Agent 详情")
    @GetMapping("/agents/{id}")
    public Agent get(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        Agent agent = agentService.getById(id);
        agentService.attachDirtyFlag(agent);
        return agent;
    }

    @Operation(summary = "删除 Agent")
    @DeleteMapping("/agents/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        agentService.delete(id);
        return Map.of("deleted", true);
    }

    @Operation(summary = "发布 Agent（status = PUBLISHED）")
    @PostMapping("/agents/{id}/publish")
    public Agent publish(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return agentService.publish(id);
    }

    @Operation(summary = "下架 Agent（status = DRAFT）")
    @PostMapping("/agents/{id}/unpublish")
    public Agent unpublish(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return agentService.unpublish(id);
    }

    // ============================ Agent-Skill 绑定 ============================

    @Data
    public static class BindSkillRequest {
        private Long skillId;
    }

    @Operation(summary = "绑定技能（幂等：重复绑定直接返回已存在的绑定）")
    @PostMapping("/agents/{id}/skills")
    public AgentSkill bindSkill(@PathVariable Long id, @RequestBody BindSkillRequest req) {
        if (req == null || req.getSkillId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "skill_id 不能为空");
        }
        // 双校验，与插件同构：Agent 走 RBAC，技能走 scope/owner（技能不在 RBAC 资源体系里，
        // SkillTenantService.get 对不可见的技能会抛异常）。少任一边，都能借 Agent 间接用到
        // 自己无权的技能。
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        skillTenantService.get(req.getSkillId(), TenantContext.required(),
                AdminRequestContext.findUserIdOrNull());
        return agentService.bindSkill(id, req.getSkillId());
    }

    @Operation(summary = "解绑技能")
    @DeleteMapping("/agents/{id}/skills/{skillId}")
    public Map<String, Object> unbindSkill(@PathVariable Long id, @PathVariable Long skillId) {
        // 解绑不校验技能可见性：技能被停用/删除后仍必须能解绑，否则会留下永远摘不掉的绑定。
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return Map.of("unbound", agentService.unbindSkill(id, skillId));
    }

    @Operation(summary = "列出 Agent 已绑定的技能")
    @GetMapping("/agents/{id}/skills")
    public List<AgentSkill> listSkillBindings(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return agentService.listSkillBindings(id);
    }

    @Data
    public static class GrantConnectionRequest {
        private Long connectionId;
    }

    @Operation(summary = "授予 Agent 一条外部连接（幂等）")
    @PostMapping("/agents/{id}/connections")
    public AgentConnection grantConnection(@PathVariable Long id, @RequestBody GrantConnectionRequest req) {
        if (req == null || req.getConnectionId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "connection_id 不能为空");
        }
        // 授予连接 = 让这个 Agent 能以某个外部身份发请求，比绑技能重：限超管。
        // 技能只是"怎么调"的说明，连接才是"能不能调"。
        superAdminGuard.requireSuperAdmin();
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        connectionService.get(req.getConnectionId());   // 不存在则抛
        return agentService.grantConnection(id, req.getConnectionId());
    }

    @Operation(summary = "撤销 Agent 的一条外部连接")
    @DeleteMapping("/agents/{id}/connections/{connectionId}")
    public Map<String, Object> revokeConnection(@PathVariable Long id, @PathVariable Long connectionId) {
        // 撤销不校验连接是否还存在：连接被删后仍必须能摘掉残留授权。
        superAdminGuard.requireSuperAdmin();
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return Map.of("revoked", agentService.revokeConnection(id, connectionId));
    }

    @Operation(summary = "列出 Agent 已被授予的外部连接")
    @GetMapping("/agents/{id}/connections")
    public List<AgentConnection> listConnectionGrants(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return agentService.listConnectionGrants(id);
    }
}

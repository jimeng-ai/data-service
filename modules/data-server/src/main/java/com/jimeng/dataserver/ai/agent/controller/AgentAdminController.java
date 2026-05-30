package com.jimeng.dataserver.ai.agent.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentPlugin;
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
 * Agent 管理后台接口：Agent CRUD + Agent-Plugin 绑定。
 */
@Tag(name = "Agent 管理", description = "ToB Agent 平台 - Agent CRUD + 插件绑定")
@RestController
@RequestMapping("/data/admin/agent")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentService agentService;
    private final PermissionResolver permissionResolver;

    @Operation(summary = "创建 Agent")
    @PostMapping("/agents")
    public Agent createAgent(@RequestBody Agent agent) {
        return agentService.create(agent);
    }

    @Operation(summary = "更新 Agent")
    @PutMapping("/agents/{id}")
    public Agent updateAgent(@PathVariable Long id, @RequestBody Agent agent) {
        agent.setId(id);
        return agentService.update(agent);
    }

    @Operation(summary = "列出当前租户的 Agent（成员仅见被授权的）")
    @GetMapping("/agents")
    public List<Agent> list(@RequestParam(required = false) String status) {
        return permissionResolver.filterCurrent(agentService.list(status), ResourceType.AGENT, Agent::getId);
    }

    @Operation(summary = "Agent 详情")
    @GetMapping("/agents/{id}")
    public Agent get(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, id);
        return agentService.getById(id);
    }

    @Operation(summary = "删除 Agent")
    @DeleteMapping("/agents/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        agentService.delete(id);
        return Map.of("deleted", true);
    }

    @Operation(summary = "发布 Agent（status = PUBLISHED）")
    @PostMapping("/agents/{id}/publish")
    public Agent publish(@PathVariable Long id) {
        return agentService.publish(id);
    }

    @Operation(summary = "下架 Agent（status = DRAFT）")
    @PostMapping("/agents/{id}/unpublish")
    public Agent unpublish(@PathVariable Long id) {
        return agentService.unpublish(id);
    }

    // ============================ Agent-Plugin 绑定 ============================

    @Data
    public static class BindPluginRequest {
        private Long pluginId;
    }

    @Operation(summary = "绑定插件（幂等：重复绑定直接返回已存在的绑定）")
    @PostMapping("/agents/{id}/plugins")
    public AgentPlugin bindPlugin(@PathVariable Long id, @RequestBody BindPluginRequest req) {
        if (req == null || req.getPluginId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "plugin_id 不能为空");
        }
        return agentService.bindPlugin(id, req.getPluginId());
    }

    @Operation(summary = "解绑插件")
    @DeleteMapping("/agents/{id}/plugins/{pluginId}")
    public Map<String, Object> unbindPlugin(@PathVariable Long id, @PathVariable Long pluginId) {
        int affected = agentService.unbindPlugin(id, pluginId);
        return Map.of("unbound", affected);
    }

    @Operation(summary = "列出 Agent 已绑定的插件")
    @GetMapping("/agents/{id}/plugins")
    public List<AgentPlugin> listBindings(@PathVariable Long id) {
        return agentService.listBindings(id);
    }
}

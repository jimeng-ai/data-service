package com.jimeng.dataserver.ai.plugin.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.service.PluginCredentialService;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.plugin.service.PluginHttpInvoker;
import com.jimeng.dataserver.ai.plugin.service.PluginRegistryService;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginCredential;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
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
 * 插件管理后台接口。
 * 所有写操作改完后会触发 {@link PluginRegistryService} 缓存刷新。
 */
@Tag(name = "插件管理", description = "ToB Agent 平台 - 插件 / 工具 / HTTP 映射 / 凭证 CRUD")
@RestController
@RequestMapping("/data/admin/plugin")
@RequiredArgsConstructor
public class PluginAdminController {

    private final PluginCrudService crudService;
    private final PluginCredentialService credentialService;
    private final PluginRegistryService registryService;
    private final PluginHttpInvoker httpInvoker;
    private final PermissionResolver permissionResolver;
    private final SuperAdminGuard superAdminGuard;

    // 子资源（工具/映射）端点共用：校验当前账号对父插件有访问权，且 toolId 确实属于该插件，
    // 防止「用自己有权的 pluginId 套别人的 toolId」绕过实例级授权。
    private void assertToolUnderAccessiblePlugin(Long pluginId, Long toolId) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
        Long ownerPluginId = crudService.resolvePluginIdByTool(toolId);
        if (!pluginId.equals(ownerPluginId)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool 不属于该插件");
        }
    }

    // ============================ Plugin ============================

    @Operation(summary = "创建插件")
    @PostMapping("/plugins")
    public Plugin createPlugin(@RequestBody Plugin plugin) {
        return crudService.createPlugin(plugin);
    }

    @Operation(summary = "更新插件")
    @PutMapping("/plugins/{id}")
    public Plugin updatePlugin(@PathVariable Long id, @RequestBody Plugin plugin) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, id);
        plugin.setId(id);
        return crudService.updatePlugin(plugin);
    }

    @Operation(summary = "列出当前租户的插件（成员仅见被授权的）")
    @GetMapping("/plugins")
    public List<Plugin> listPlugins(@RequestParam(required = false) String status) {
        return permissionResolver.filterCurrent(crudService.listPlugins(status), ResourceType.PLUGIN, Plugin::getId);
    }

    @Operation(summary = "插件详情")
    @GetMapping("/plugins/{id}")
    public Plugin getPlugin(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, id);
        return crudService.getPlugin(id);
    }

    @Operation(summary = "删除插件（级联删除工具/映射）")
    @DeleteMapping("/plugins/{id}")
    public Map<String, Object> deletePlugin(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, id);
        crudService.deletePlugin(id);
        return Map.of("deleted", true);
    }

    @Operation(summary = "发布插件（status = PUBLISHED）")
    @PostMapping("/plugins/{id}/publish")
    public Plugin publishPlugin(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, id);
        return crudService.publish(id);
    }

    @Operation(summary = "下架插件（status = DRAFT）")
    @PostMapping("/plugins/{id}/unpublish")
    public Plugin unpublishPlugin(@PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, id);
        return crudService.unpublish(id);
    }

    // ============================ Tool + HTTP Mapping ============================

    @Data
    public static class ToolWithMapping {
        private PluginTool tool;
        private PluginHttpMapping mapping;
    }

    @Operation(summary = "为插件新增工具（同时建立 HTTP 映射）")
    @PostMapping("/plugins/{pluginId}/tools")
    public PluginTool createTool(@PathVariable Long pluginId, @RequestBody ToolWithMapping body) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
        if (body == null || body.getTool() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool 不能为空");
        }
        return crudService.createTool(pluginId, body.getTool(), body.getMapping());
    }

    @Operation(summary = "更新工具及其映射")
    @PutMapping("/plugins/{pluginId}/tools/{toolId}")
    public PluginTool updateTool(@PathVariable Long pluginId, @PathVariable Long toolId,
                                  @RequestBody ToolWithMapping body) {
        assertToolUnderAccessiblePlugin(pluginId, toolId);
        return crudService.updateTool(pluginId, toolId,
                body == null ? null : body.getTool(),
                body == null ? null : body.getMapping());
    }

    @Operation(summary = "删除工具（连带映射）")
    @DeleteMapping("/plugins/{pluginId}/tools/{toolId}")
    public Map<String, Object> deleteTool(@PathVariable Long pluginId, @PathVariable Long toolId) {
        assertToolUnderAccessiblePlugin(pluginId, toolId);
        crudService.deleteTool(toolId);
        return Map.of("deleted", true);
    }

    @Operation(summary = "列出插件下的工具")
    @GetMapping("/plugins/{pluginId}/tools")
    public List<PluginTool> listTools(@PathVariable Long pluginId) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
        return crudService.listTools(pluginId);
    }

    @Operation(summary = "获取工具的 HTTP 映射")
    @GetMapping("/plugins/{pluginId}/tools/{toolId}/mapping")
    public PluginHttpMapping getMapping(@PathVariable Long pluginId, @PathVariable Long toolId) {
        assertToolUnderAccessiblePlugin(pluginId, toolId);
        return crudService.getMappingByTool(toolId);
    }

    // ============================ Credential ============================
    // 每个插件在租户内只有一份凭证，因此用单数路径 + GET/PUT 替代旧的 list/CRUD。

    @Operation(summary = "获取插件凭证（不存在返回 null）")
    @GetMapping("/plugins/{pluginId}/credential")
    public PluginCredential getCredential(@PathVariable Long pluginId) {
        // 凭证含 API key / token 等敏感信息，必须做实例级鉴权（未授权抛 4001），再触发归属校验（跨租户/不存在抛 404）。
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
        crudService.getPlugin(pluginId);
        return credentialService.findByPlugin(pluginId);
    }

    @Operation(summary = "保存插件凭证（upsert）")
    @PutMapping("/plugins/{pluginId}/credential")
    public PluginCredential saveCredential(@PathVariable Long pluginId,
                                            @RequestBody PluginCredential credential) {
        permissionResolver.assertCurrentAccess(ResourceType.PLUGIN, pluginId);
        crudService.getPlugin(pluginId);
        credential.setPluginId(pluginId);
        return credentialService.save(credential);
    }

    // ============================ 调试 / 缓存 ============================

    @Data
    public static class PluginTestRequest {
        // 工具 id 是雪花 id（19 位），超出 JS 安全整数，前端以字符串传入，这里再转 Long
        private String toolId;
        private Map<String, Object> input;
    }

    @Operation(summary = "试调用：不经 LLM、不经发布缓存，按 toolId 直取配置 → 调插件并回传请求/curl/响应")
    @PostMapping("/plugins/{pluginId}/test")
    public Object testInvoke(@PathVariable Long pluginId, @RequestBody PluginTestRequest req) {
        String rawToolId = req == null ? null : req.getToolId();
        if (rawToolId == null || rawToolId.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "toolId 不能为空");
        }
        Long toolId;
        try {
            toolId = Long.valueOf(rawToolId.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "toolId 非法");
        }
        // 试调用会真实打到插件后端（可能有副作用）：做实例级鉴权 + 父子归属校验。
        // 但不要求插件已发布 / 工具已启用——调试草稿也能直接试跑。
        assertToolUnderAccessiblePlugin(pluginId, toolId);
        Plugin plugin = crudService.getPlugin(pluginId);
        PluginTool tool = crudService.getTool(toolId);
        PluginHttpMapping mapping = crudService.getMappingByTool(toolId);
        if (mapping == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "该工具尚未配置 HTTP 映射，无法试调用");
        }
        PluginToolEntry entry = new PluginToolEntry(plugin, tool, mapping);
        return httpInvoker.invokeForTest(entry, req.getInput());
    }

    @Operation(summary = "手动刷新插件缓存")
    @PostMapping("/plugins/_refresh")
    public Map<String, Object> refresh() {
        // 全局缓存重载是平台级操作（无资源 id 可校验），仅限企业超管，防止成员高频触发拖垮缓存。
        superAdminGuard.requireSuperAdmin();
        registryService.reload();
        return Map.of("refreshed", true);
    }
}

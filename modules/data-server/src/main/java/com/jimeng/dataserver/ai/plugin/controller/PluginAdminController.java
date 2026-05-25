package com.jimeng.dataserver.ai.plugin.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.service.PluginCredentialService;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.plugin.service.PluginHttpInvoker;
import com.jimeng.dataserver.ai.plugin.service.PluginRegistryService;
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
import java.util.Optional;

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

    // ============================ Plugin ============================

    @Operation(summary = "创建插件")
    @PostMapping("/plugins")
    public Plugin createPlugin(@RequestBody Plugin plugin) {
        return crudService.createPlugin(plugin);
    }

    @Operation(summary = "更新插件")
    @PutMapping("/plugins/{id}")
    public Plugin updatePlugin(@PathVariable Long id, @RequestBody Plugin plugin) {
        plugin.setId(id);
        return crudService.updatePlugin(plugin);
    }

    @Operation(summary = "列出当前租户的插件")
    @GetMapping("/plugins")
    public List<Plugin> listPlugins(@RequestParam(required = false) String status) {
        return crudService.listPlugins(status);
    }

    @Operation(summary = "插件详情")
    @GetMapping("/plugins/{id}")
    public Plugin getPlugin(@PathVariable Long id) {
        return crudService.getPlugin(id);
    }

    @Operation(summary = "删除插件（级联删除工具/映射）")
    @DeleteMapping("/plugins/{id}")
    public Map<String, Object> deletePlugin(@PathVariable Long id) {
        crudService.deletePlugin(id);
        return Map.of("deleted", true);
    }

    @Operation(summary = "发布插件（status = PUBLISHED）")
    @PostMapping("/plugins/{id}/publish")
    public Plugin publishPlugin(@PathVariable Long id) {
        return crudService.publish(id);
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
        if (body == null || body.getTool() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool 不能为空");
        }
        return crudService.createTool(pluginId, body.getTool(), body.getMapping());
    }

    @Operation(summary = "更新工具及其映射")
    @PutMapping("/plugins/{pluginId}/tools/{toolId}")
    public PluginTool updateTool(@PathVariable Long pluginId, @PathVariable Long toolId,
                                  @RequestBody ToolWithMapping body) {
        return crudService.updateTool(pluginId, toolId,
                body == null ? null : body.getTool(),
                body == null ? null : body.getMapping());
    }

    @Operation(summary = "删除工具（连带映射）")
    @DeleteMapping("/plugins/{pluginId}/tools/{toolId}")
    public Map<String, Object> deleteTool(@PathVariable Long pluginId, @PathVariable Long toolId) {
        crudService.deleteTool(toolId);
        return Map.of("deleted", true);
    }

    @Operation(summary = "列出插件下的工具")
    @GetMapping("/plugins/{pluginId}/tools")
    public List<PluginTool> listTools(@PathVariable Long pluginId) {
        return crudService.listTools(pluginId);
    }

    @Operation(summary = "获取工具的 HTTP 映射")
    @GetMapping("/plugins/{pluginId}/tools/{toolId}/mapping")
    public PluginHttpMapping getMapping(@PathVariable Long pluginId, @PathVariable Long toolId) {
        return crudService.getMappingByTool(toolId);
    }

    // ============================ Credentials ============================

    @Operation(summary = "为插件新增凭证（明文 JSON 落库）")
    @PostMapping("/plugins/{pluginId}/credentials")
    public PluginCredential createCredential(@PathVariable Long pluginId,
                                              @RequestBody PluginCredential credential) {
        credential.setPluginId(pluginId);
        return credentialService.create(credential);
    }

    @Operation(summary = "更新凭证")
    @PutMapping("/plugins/{pluginId}/credentials/{credentialId}")
    public PluginCredential updateCredential(@PathVariable Long pluginId,
                                              @PathVariable Long credentialId,
                                              @RequestBody PluginCredential credential) {
        credential.setId(credentialId);
        credential.setPluginId(pluginId);
        return credentialService.update(credential);
    }

    @Operation(summary = "删除凭证")
    @DeleteMapping("/plugins/{pluginId}/credentials/{credentialId}")
    public Map<String, Object> deleteCredential(@PathVariable Long pluginId,
                                                 @PathVariable Long credentialId) {
        credentialService.deleteById(credentialId);
        return Map.of("deleted", true);
    }

    @Operation(summary = "列出某插件的凭证")
    @GetMapping("/plugins/{pluginId}/credentials")
    public List<PluginCredential> listCredentials(@PathVariable Long pluginId) {
        return credentialService.listByPlugin(pluginId);
    }

    // ============================ 调试 / 缓存 ============================

    @Data
    public static class PluginTestRequest {
        private String toolName;
        private Map<String, Object> input;
        private String credentialAlias;  // 可选
    }

    @Operation(summary = "试调用：不经 LLM 直接传入参数 → 调插件并返回真实响应")
    @PostMapping("/plugins/{pluginId}/test")
    public Object testInvoke(@PathVariable Long pluginId, @RequestBody PluginTestRequest req) {
        // 校验插件归属当前租户：getPlugin 会查不到时抛 404
        Plugin plugin = crudService.getPlugin(pluginId);
        Optional<PluginToolEntry> entryOpt = registryService.findToolByName(plugin.getTenantId(), req.getToolName());
        if (entryOpt.isEmpty()) {
            throw new ServiceException(ExceptionCode.NOT_FOUND,
                    "找不到工具（请检查插件是否已 PUBLISHED 且工具 enabled=true）: " + req.getToolName());
        }
        return httpInvoker.invoke(entryOpt.get(), req.getInput(), req.getCredentialAlias());
    }

    @Operation(summary = "手动刷新插件缓存")
    @PostMapping("/plugins/_refresh")
    public Map<String, Object> refresh() {
        registryService.reload();
        return Map.of("refreshed", true);
    }
}

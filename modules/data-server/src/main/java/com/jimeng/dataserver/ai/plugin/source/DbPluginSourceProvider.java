package com.jimeng.dataserver.ai.plugin.source;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.service.PluginRegistryService;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.ToolSourceProvider;
import com.jimeng.persistence.entity.Plugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DB 插件 ToolPackage 来源：从 {@link PluginRegistryService} 按当前租户读取。
 */
@Component
@RequiredArgsConstructor
public class DbPluginSourceProvider implements ToolSourceProvider {

    private final PluginRegistryService pluginRegistryService;

    @Override
    public List<ToolPackage> getPackages() {
        String tenantId = TenantContext.get();
        if (tenantId == null) return List.of();

        List<Plugin> plugins = pluginRegistryService.listPlugins(tenantId);
        if (plugins.isEmpty()) return List.of();

        List<ToolPackage> packages = new ArrayList<>();
        for (Plugin plugin : plugins) {
            List<PluginToolEntry> entries = pluginRegistryService
                    .listToolsByPluginCode(tenantId, plugin.getCode());
            if (entries.isEmpty()) continue;
            packages.add(new PluginToolPackage(plugin, entries));
        }
        return packages;
    }
}

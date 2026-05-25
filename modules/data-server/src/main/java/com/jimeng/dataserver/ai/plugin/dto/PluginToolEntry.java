package com.jimeng.dataserver.ai.plugin.dto;

import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 内存缓存中一个插件工具的完整视图（Plugin + Tool + HttpMapping 三表 join）。
 */
@Getter
@RequiredArgsConstructor
public class PluginToolEntry {

    private final Plugin plugin;
    private final PluginTool tool;
    private final PluginHttpMapping mapping;

    public String tenantId() {
        return plugin.getTenantId();
    }

    public String toolName() {
        return tool.getName();
    }

    public String pluginCode() {
        return plugin.getCode();
    }
}

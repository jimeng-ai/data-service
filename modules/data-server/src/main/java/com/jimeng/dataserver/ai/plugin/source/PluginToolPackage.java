package com.jimeng.dataserver.ai.plugin.source;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DB 插件作为 ToolPackage 的视图——把 {@link Plugin} + 它的 {@link PluginTool} 列表
 * 包装成 SkillRuntimeService 能直接消费的 ToolPackage。
 */
@Slf4j
public class PluginToolPackage implements ToolPackage {

    private final Plugin plugin;
    private final List<PluginToolEntry> entries;

    public PluginToolPackage(Plugin plugin, List<PluginToolEntry> entries) {
        this.plugin = plugin;
        this.entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
    }

    @Override
    public String getName() {
        return plugin.getCode();
    }

    @Override
    public String getDescription() {
        return plugin.getDescription();
    }

    @Override
    public String getBody() {
        // 自动生成 guidance：插件描述 + 工具清单
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(plugin.getDescription())) {
            sb.append(plugin.getDescription()).append("\n\n");
        }
        if (!entries.isEmpty()) {
            sb.append("可用工具：\n");
            for (PluginToolEntry e : entries) {
                sb.append("- `").append(e.getTool().getName()).append("`");
                if (StringUtils.hasText(e.getTool().getDescription())) {
                    sb.append(": ").append(e.getTool().getDescription());
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    @Override
    public List<SkillToolDefinition> getTools() {
        List<SkillToolDefinition> defs = new ArrayList<>();
        for (PluginToolEntry e : entries) {
            PluginTool tool = e.getTool();
            if (tool == null || Boolean.FALSE.equals(tool.getEnabled())) continue;
            Map<String, Object> inputSchema = parseSchema(tool.getInputSchema());
            defs.add(new SkillToolDefinition(
                    tool.getName(),
                    tool.getDescription() == null ? "" : tool.getDescription(),
                    inputSchema
            ));
        }
        return defs;
    }

    @Override
    public String getTenantId() {
        return plugin.getTenantId();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String json) {
        if (!StringUtils.hasText(json)) return Collections.emptyMap();
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            log.warn("解析 plugin_tool.input_schema 失败: tool={}, error={}", plugin.getCode(), ex.getMessage());
            return Collections.emptyMap();
        }
    }
}

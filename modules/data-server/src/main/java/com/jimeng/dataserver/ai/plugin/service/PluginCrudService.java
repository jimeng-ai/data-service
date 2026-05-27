package com.jimeng.dataserver.ai.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
import com.jimeng.persistence.mapper.PluginHttpMappingMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import com.jimeng.persistence.mapper.PluginToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Plugin / PluginTool / PluginHttpMapping 三表的 CRUD 聚合服务。
 * 所有写操作 finally 触发 {@link PluginRegistryService#reload()}（保证缓存与 DB 一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginCrudService {

    private final PluginMapper pluginMapper;
    private final PluginToolMapper pluginToolMapper;
    private final PluginHttpMappingMapper pluginHttpMappingMapper;
    private final PluginRegistryService registryService;

    // ============================ Plugin ============================

    public Plugin createPlugin(Plugin plugin) {
        if (!StringUtils.hasText(plugin.getCode())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "plugin.code 不能为空");
        }
        if (!StringUtils.hasText(plugin.getName())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "plugin.name 不能为空");
        }
        if (!StringUtils.hasText(plugin.getStatus())) {
            plugin.setStatus("DRAFT");
        }
        if (!StringUtils.hasText(plugin.getAuthType())) {
            plugin.setAuthType("NONE");
        }
        pluginMapper.insert(plugin);
        registryService.reload();
        return plugin;
    }

    public Plugin updatePlugin(Plugin plugin) {
        if (plugin.getId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "plugin.id 不能为空");
        }
        pluginMapper.updateById(plugin);
        registryService.reload();
        return pluginMapper.selectById(plugin.getId());
    }

    public Plugin getPlugin(Long id) {
        Plugin plugin = pluginMapper.selectById(id);
        if (plugin == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "plugin 不存在: " + id);
        }
        return plugin;
    }

    public List<Plugin> listPlugins(String status) {
        LambdaQueryWrapper<Plugin> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Plugin::getStatus, status);
        }
        wrapper.orderByDesc(Plugin::getCreateTime);
        return pluginMapper.selectList(wrapper);
    }

    @Transactional
    public void deletePlugin(Long id) {
        // 级联软删工具 + 映射 + 凭证（保留凭证由调用方决定，简化版连删）
        LambdaQueryWrapper<PluginTool> toolQuery = new LambdaQueryWrapper<PluginTool>()
                .eq(PluginTool::getPluginId, id);
        List<PluginTool> tools = pluginToolMapper.selectList(toolQuery);
        for (PluginTool tool : tools) {
            pluginHttpMappingMapper.delete(
                    new LambdaQueryWrapper<PluginHttpMapping>().eq(PluginHttpMapping::getPluginToolId, tool.getId()));
        }
        pluginToolMapper.delete(toolQuery);
        pluginMapper.deleteById(id);
        registryService.reload();
    }

    public Plugin publish(Long id) {
        Plugin plugin = getPlugin(id);
        plugin.setStatus("PUBLISHED");
        pluginMapper.updateById(plugin);
        registryService.reload();
        return plugin;
    }

    public Plugin unpublish(Long id) {
        Plugin plugin = getPlugin(id);
        plugin.setStatus("DRAFT");
        pluginMapper.updateById(plugin);
        registryService.reload();
        return plugin;
    }

    // ============================ Tool + HTTP Mapping ============================

    @Transactional
    public PluginTool createTool(Long pluginId, PluginTool tool, PluginHttpMapping mapping) {
        if (!StringUtils.hasText(tool.getName())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "tool.name 不能为空");
        }
        if (mapping == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "mapping 不能为空");
        }
        tool.setPluginId(pluginId);
        if (tool.getEnabled() == null) tool.setEnabled(Boolean.TRUE);
        pluginToolMapper.insert(tool);

        mapping.setPluginToolId(tool.getId());
        pluginHttpMappingMapper.insert(mapping);
        registryService.reload();
        return tool;
    }

    @Transactional
    public PluginTool updateTool(Long pluginId, Long toolId, PluginTool tool, PluginHttpMapping mapping) {
        if (tool != null) {
            tool.setId(toolId);
            tool.setPluginId(pluginId);
            pluginToolMapper.updateById(tool);
        }
        if (mapping != null) {
            PluginHttpMapping existing = pluginHttpMappingMapper.selectOne(
                    new LambdaQueryWrapper<PluginHttpMapping>().eq(PluginHttpMapping::getPluginToolId, toolId));
            if (existing != null) {
                mapping.setId(existing.getId());
                mapping.setPluginToolId(toolId);
                pluginHttpMappingMapper.updateById(mapping);
            } else {
                mapping.setPluginToolId(toolId);
                pluginHttpMappingMapper.insert(mapping);
            }
        }
        registryService.reload();
        return pluginToolMapper.selectById(toolId);
    }

    @Transactional
    public void deleteTool(Long toolId) {
        pluginHttpMappingMapper.delete(
                new LambdaQueryWrapper<PluginHttpMapping>().eq(PluginHttpMapping::getPluginToolId, toolId));
        pluginToolMapper.deleteById(toolId);
        registryService.reload();
    }

    public List<PluginTool> listTools(Long pluginId) {
        return pluginToolMapper.selectList(
                new LambdaQueryWrapper<PluginTool>().eq(PluginTool::getPluginId, pluginId));
    }

    public PluginHttpMapping getMappingByTool(Long toolId) {
        return pluginHttpMappingMapper.selectOne(
                new LambdaQueryWrapper<PluginHttpMapping>().eq(PluginHttpMapping::getPluginToolId, toolId));
    }
}

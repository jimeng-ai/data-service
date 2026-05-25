package com.jimeng.dataserver.ai.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.PluginHttpMapping;
import com.jimeng.persistence.entity.PluginTool;
import com.jimeng.persistence.mapper.PluginHttpMappingMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import com.jimeng.persistence.mapper.PluginToolMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 插件注册表：从 DB 加载所有租户的已发布插件，按租户分桶缓存在内存。
 *
 * <p>线程模型：单一 {@link AtomicReference} 持有 immutable 快照，refresh 时整体替换。
 * 业务读不需要锁。
 *
 * <p>租户隔离：加载用 {@link TenantContext#runAsSystem(Runnable)} 跳过自动 tenant 过滤，
 * 业务读用 {@link #findToolByName(String, String)} 显式带租户参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginRegistryService {

    private final PluginMapper pluginMapper;
    private final PluginToolMapper pluginToolMapper;
    private final PluginHttpMappingMapper pluginHttpMappingMapper;

    /** Map<tenantId, TenantIndex> 的不可变快照 */
    private final AtomicReference<Map<String, TenantIndex>> cache = new AtomicReference<>(Collections.emptyMap());

    @PostConstruct
    public void init() {
        reload();
    }

    /** 全量重建缓存。CRUD 改动后由 admin 接口调用。 */
    public void reload() {
        Map<String, TenantIndex> newCache = TenantContext.runAsSystem(() -> {
            // 1. 取所有 PUBLISHED 插件
            LambdaQueryWrapper<Plugin> pluginQuery = new LambdaQueryWrapper<Plugin>()
                    .eq(Plugin::getStatus, "PUBLISHED");
            List<Plugin> plugins = pluginMapper.selectList(pluginQuery);
            if (plugins.isEmpty()) {
                return Collections.<String, TenantIndex>emptyMap();
            }

            // 2. 取这些插件下的所有 enabled 工具
            List<Long> pluginIds = plugins.stream().map(Plugin::getId).toList();
            LambdaQueryWrapper<PluginTool> toolQuery = new LambdaQueryWrapper<PluginTool>()
                    .in(PluginTool::getPluginId, pluginIds)
                    .eq(PluginTool::getEnabled, Boolean.TRUE);
            List<PluginTool> tools = pluginToolMapper.selectList(toolQuery);
            if (tools.isEmpty()) {
                return Collections.<String, TenantIndex>emptyMap();
            }

            // 3. 取这些工具的 HTTP 映射
            List<Long> toolIds = tools.stream().map(PluginTool::getId).toList();
            LambdaQueryWrapper<PluginHttpMapping> mappingQuery = new LambdaQueryWrapper<PluginHttpMapping>()
                    .in(PluginHttpMapping::getPluginToolId, toolIds);
            List<PluginHttpMapping> mappings = pluginHttpMappingMapper.selectList(mappingQuery);

            // 4. 组装索引
            Map<Long, Plugin> pluginById = plugins.stream()
                    .collect(Collectors.toMap(Plugin::getId, Function.identity()));
            Map<Long, PluginHttpMapping> mappingByToolId = mappings.stream()
                    .collect(Collectors.toMap(PluginHttpMapping::getPluginToolId, Function.identity(), (a, b) -> a));

            Map<String, TenantIndex> built = new ConcurrentHashMap<>();
            for (PluginTool tool : tools) {
                Plugin plugin = pluginById.get(tool.getPluginId());
                PluginHttpMapping mapping = mappingByToolId.get(tool.getId());
                if (plugin == null || mapping == null) continue;
                String tenantId = plugin.getTenantId();
                if (tenantId == null) continue;

                PluginToolEntry entry = new PluginToolEntry(plugin, tool, mapping);
                built.computeIfAbsent(tenantId, k -> new TenantIndex())
                        .add(entry);
            }
            return built;
        });

        cache.set(newCache == null ? Collections.emptyMap() : newCache);
        int totalTools = newCache == null ? 0 :
                newCache.values().stream().mapToInt(idx -> idx.byToolName.size()).sum();
        log.info("PluginRegistry 重建完成: 租户数={}, 工具总数={}",
                newCache == null ? 0 : newCache.size(), totalTools);
    }

    /** 路由：按 (租户, 工具名) 查 entry。 */
    public Optional<PluginToolEntry> findToolByName(String tenantId, String toolName) {
        if (tenantId == null || toolName == null) return Optional.empty();
        TenantIndex idx = cache.get().get(tenantId);
        if (idx == null) return Optional.empty();
        return Optional.ofNullable(idx.byToolName.get(toolName));
    }

    /** 列出该租户所有插件（按 code 去重） */
    public List<Plugin> listPlugins(String tenantId) {
        TenantIndex idx = cache.get().get(tenantId);
        if (idx == null) return Collections.emptyList();
        return new ArrayList<>(idx.byPluginCode.keySet()).stream()
                .map(code -> idx.byPluginCode.get(code).get(0).getPlugin())
                .toList();
    }

    /** 按 plugin_code 拿到该插件的所有工具 entry */
    public List<PluginToolEntry> listToolsByPluginCode(String tenantId, String pluginCode) {
        TenantIndex idx = cache.get().get(tenantId);
        if (idx == null) return Collections.emptyList();
        return idx.byPluginCode.getOrDefault(pluginCode, Collections.emptyList());
    }

    /** 内部租户级索引 */
    static class TenantIndex {
        final Map<String, PluginToolEntry> byToolName = new LinkedHashMap<>();
        final Map<String, List<PluginToolEntry>> byPluginCode = new LinkedHashMap<>();

        void add(PluginToolEntry entry) {
            byToolName.put(entry.toolName(), entry);
            byPluginCode.computeIfAbsent(entry.pluginCode(), k -> new ArrayList<>()).add(entry);
        }
    }
}

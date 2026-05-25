package com.jimeng.dataserver.ai.skill.source;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具包注册中心：聚合所有 {@link ToolSourceProvider} 实现，
 * 给 SkillRuntimeService 提供统一的工具包视图。
 *
 * <p>命名冲突策略：先注册的 provider 优先（preserve first），按 List 顺序聚合。
 * 这避免插件意外覆盖代码型 Skill。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolPackageRegistry {

    private final List<ToolSourceProvider> providers;

    /** 聚合所有 provider 返回的工具包到一个 Map（按 name 去重）。 */
    public Map<String, ToolPackage> aggregate() {
        Map<String, ToolPackage> merged = new LinkedHashMap<>();
        if (providers == null) return merged;
        for (ToolSourceProvider provider : providers) {
            List<ToolPackage> pkgs = provider.getPackages();
            if (pkgs == null) continue;
            for (ToolPackage pkg : pkgs) {
                if (pkg == null || StrUtil.isBlank(pkg.getName())) continue;
                merged.putIfAbsent(pkg.getName(), pkg);  // 先到先得
            }
        }
        return merged;
    }

    /** 大小写不敏感地按 name 查找。 */
    public ToolPackage findByName(Map<String, ToolPackage> map, String name) {
        if (map == null || map.isEmpty() || StrUtil.isBlank(name)) return null;
        ToolPackage exact = map.get(name);
        if (exact != null) return exact;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ToolPackage> entry : map.entrySet()) {
            if (normalized.equals(entry.getKey().trim().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }
}

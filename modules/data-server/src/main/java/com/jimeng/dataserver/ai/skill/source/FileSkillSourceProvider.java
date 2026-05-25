package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 磁盘 Skill 来源（包装现有 {@link SkillPackageLoaderService}）。
 * Skill 对所有租户全局可见——返回的 ToolPackage 的 tenantId 全是 null。
 */
@Component
@RequiredArgsConstructor
public class FileSkillSourceProvider implements ToolSourceProvider {

    private final SkillPackageLoaderService skillPackageLoaderService;

    @Override
    public List<ToolPackage> getPackages() {
        Map<String, SkillPackage> skillMap = skillPackageLoaderService.loadSkillPackages();
        if (skillMap == null || skillMap.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(skillMap.values());
    }
}

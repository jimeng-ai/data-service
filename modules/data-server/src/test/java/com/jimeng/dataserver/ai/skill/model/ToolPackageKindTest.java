package com.jimeng.dataserver.ai.skill.model;

import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.source.PluginToolPackage;
import com.jimeng.persistence.entity.Plugin;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPackageKindTest {
    @Test
    void defaultKindIsSkill() {
        ToolPackage pkg = new ToolPackage() {
            public String getName() { return "x"; }
            public String getDescription() { return "x"; }
            public String getBody() { return "x"; }
            public List<SkillToolDefinition> getTools() { return List.of(); }
        };
        assertEquals(ToolPackageKind.SKILL, pkg.getKind());
    }
    @Test
    void pluginPackageKindIsPlugin() {
        Plugin p = new Plugin();
        p.setCode("c"); p.setDescription("d"); p.setTenantId("t1");
        PluginToolPackage pkg = new PluginToolPackage(p, List.<PluginToolEntry>of());
        assertEquals(ToolPackageKind.PLUGIN, pkg.getKind());
    }
}

package com.jimeng.dataserver.ai.skill.model;

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
}

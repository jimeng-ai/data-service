package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.skill.model.ToolPackageKind;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiSkillToolPackageTest {
    @Test
    void exposesRowAsSkillPackage() {
        AiSkill s = new AiSkill();
        s.setName("my-skill"); s.setDescription("desc"); s.setBody("body text"); s.setTenantId("t1");
        AiSkillToolPackage pkg = new AiSkillToolPackage(s);
        assertEquals("my-skill", pkg.getName());
        assertEquals("desc", pkg.getDescription());
        assertEquals("body text", pkg.getBody());
        assertEquals("t1", pkg.getTenantId());
        assertEquals(ToolPackageKind.SKILL, pkg.getKind());
        assertTrue(pkg.getTools().isEmpty());
    }
}

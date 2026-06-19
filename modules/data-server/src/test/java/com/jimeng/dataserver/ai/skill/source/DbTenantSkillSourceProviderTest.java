package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DbTenantSkillSourceProviderTest {
    private AiSkill skill(String name, String scope, Long owner) {
        AiSkill s = new AiSkill();
        s.setName(name); s.setScope(scope); s.setOwnerUserId(owner);
        s.setDescription("d"); s.setBody("b"); s.setTenantId("t1");
        return s;
    }
    @Test
    void tenantScopedVisibleToEveryone() {
        List<AiSkill> rows = List.of(skill("shared", SkillConst.SCOPE_TENANT, 99L));
        assertEquals(1, DbTenantSkillSourceProvider.filterVisible(rows, 1L).size());
    }
    @Test
    void privateVisibleOnlyToOwner() {
        List<AiSkill> rows = List.of(skill("mine", SkillConst.SCOPE_PRIVATE, 1L),
                                     skill("other", SkillConst.SCOPE_PRIVATE, 2L));
        List<AiSkill> visible = DbTenantSkillSourceProvider.filterVisible(rows, 1L);
        assertEquals(1, visible.size());
        assertEquals("mine", visible.get(0).getName());
    }
    @Test
    void nullUserSeesOnlyTenantScoped() {
        List<AiSkill> rows = List.of(skill("shared", SkillConst.SCOPE_TENANT, 9L),
                                     skill("mine", SkillConst.SCOPE_PRIVATE, 1L));
        List<AiSkill> visible = DbTenantSkillSourceProvider.filterVisible(rows, null);
        assertEquals(1, visible.size());
        assertEquals("shared", visible.get(0).getName());
    }
}

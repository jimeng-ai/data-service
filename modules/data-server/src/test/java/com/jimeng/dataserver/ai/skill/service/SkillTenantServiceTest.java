package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillTenantServiceTest {
    private SkillTenantService newService(AiSkillMapper mapper) {
        AiSkillRegistryService registry = mock(AiSkillRegistryService.class);
        return new SkillTenantService(mapper, registry);
    }
    @Test
    void createFromMarkdownSetsDefaults() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        SkillTenantService svc = newService(mapper);
        String raw = "---\nname: my-skill\ndescription: 测试\n---\n正文";
        AiSkill created = svc.createFromMarkdown(raw, "t1", 7L);
        assertEquals("my-skill", created.getName());
        assertEquals("t1", created.getTenantId());
        assertEquals(7L, created.getOwnerUserId());
        assertEquals(SkillConst.SCOPE_PRIVATE, created.getScope());
        assertEquals(SkillConst.TYPE_PROMPT, created.getSkillType());
        assertEquals(SkillConst.SOURCE_UPLOAD, created.getSource());
        assertEquals(SkillConst.STATUS_ACTIVE, created.getStatus());
        verify(mapper).insert(any());
    }
    @Test
    void createRejectsInvalidMarkdown() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        SkillTenantService svc = newService(mapper);
        assertThrows(ServiceException.class, () -> svc.createFromMarkdown("no frontmatter", "t1", 7L));
        verify(mapper, never()).insert(any());
    }
    @Test
    void shareSetsTenantScopeForOwner() {
        AiSkill row = new AiSkill();
        row.setId(5L); row.setTenantId("t1"); row.setOwnerUserId(7L); row.setScope(SkillConst.SCOPE_PRIVATE);
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(5L)).thenReturn(row);
        when(mapper.updateById(any())).thenReturn(1);
        SkillTenantService svc = newService(mapper);
        svc.setScope(5L, SkillConst.SCOPE_TENANT, 7L);
        assertEquals(SkillConst.SCOPE_TENANT, row.getScope());
        verify(mapper).updateById(row);
    }
    @Test
    void mutateByNonOwnerRejected() {
        AiSkill row = new AiSkill();
        row.setId(5L); row.setTenantId("t1"); row.setOwnerUserId(7L);
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(5L)).thenReturn(row);
        SkillTenantService svc = newService(mapper);
        assertThrows(ServiceException.class, () -> svc.setScope(5L, SkillConst.SCOPE_TENANT, 999L));
        verify(mapper, never()).updateById(any());
    }
}

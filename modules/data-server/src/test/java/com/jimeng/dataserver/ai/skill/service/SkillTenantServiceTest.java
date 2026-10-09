package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillTenantServiceTest {
    private SkillTenantService newService(AiSkillMapper mapper) {
        return newService(mapper, mock(SkillBuilderSessionMapper.class));
    }

    private SkillTenantService newService(AiSkillMapper mapper, SkillBuilderSessionMapper sessions) {
        return newService(mapper, sessions, mock(SkillSecretService.class));
    }

    private SkillTenantService newService(AiSkillMapper mapper, SkillBuilderSessionMapper sessions, SkillSecretService secrets) {
        AiSkillRegistryService registry = mock(AiSkillRegistryService.class);
        return new SkillTenantService(mapper, registry, sessions, secrets);
    }

    private static AiSkill owned(String status) {
        AiSkill s = new AiSkill();
        s.setId(11L);
        s.setOwnerUserId(7L);
        s.setStatus(status);
        return s;
    }

    @Test
    void draftCannotBeEnabledDirectly() {
        // 草稿只能经构建器「发布」上线；直接启用会绕过 frontmatter 校验与版本化 bundle。
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(11L)).thenReturn(owned(SkillConst.STATUS_DRAFT));
        SkillTenantService svc = newService(mapper);
        assertThrows(ServiceException.class, () -> svc.setStatus(11L, SkillConst.STATUS_ACTIVE, 7L));
        verify(mapper, never()).updateById(any(AiSkill.class));
    }

    @Test
    void deletingDraftAbandonsItsBuilderSession() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        SkillBuilderSessionMapper sessions = mock(SkillBuilderSessionMapper.class);
        when(mapper.selectById(11L)).thenReturn(owned(SkillConst.STATUS_DRAFT));
        newService(mapper, sessions).delete(11L, 7L);
        verify(mapper).deleteById(11L);
        verify(sessions).update(any(), any());
    }

    @Test
    void deletingSkillDeletesItsKeysToo() {
        // 删 skill 时它所有版本的 key 一并删掉，不然密文会留在 skill_secret 里没人管
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        SkillSecretService secrets = mock(SkillSecretService.class);
        when(mapper.selectById(11L)).thenReturn(owned(SkillConst.STATUS_ACTIVE));
        newService(mapper, mock(SkillBuilderSessionMapper.class), secrets).delete(11L, 7L);
        verify(secrets).deleteAll(11L);
    }

    @Test
    void notOwnerCannotDeleteItsKeys() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        SkillSecretService secrets = mock(SkillSecretService.class);
        when(mapper.selectById(11L)).thenReturn(owned(SkillConst.STATUS_ACTIVE));
        SkillTenantService svc = newService(mapper, mock(SkillBuilderSessionMapper.class), secrets);
        assertThrows(ServiceException.class, () -> svc.delete(11L, 999L));
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
        verify(secrets, never()).deleteAll(any());
    }

    @Test
    void deletingPublishedSkillLeavesSessionsAlone() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        SkillBuilderSessionMapper sessions = mock(SkillBuilderSessionMapper.class);
        when(mapper.selectById(11L)).thenReturn(owned(SkillConst.STATUS_ACTIVE));
        newService(mapper, sessions).delete(11L, 7L);
        verify(sessions, never()).update(any(), any());
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

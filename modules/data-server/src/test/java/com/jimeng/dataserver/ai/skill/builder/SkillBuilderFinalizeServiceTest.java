package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillBuilderFinalizeServiceTest {
    private SkillBuilderFinalizeService svc(AiSkillMapper mapper, AiSkillRegistryService reg) {
        return new SkillBuilderFinalizeService(mapper, reg, mock(SkillDraftStore.class), mock(RagMinioStorageService.class));
    }
    @Test void finalizeFlipsDraftToActive() {
        AiSkill draft = new AiSkill();
        draft.setId(9L); draft.setTenantId("t1"); draft.setOwnerUserId(7L);
        draft.setStatus(SkillConst.STATUS_DRAFT); draft.setName("csv-tool");
        draft.setDescription("d"); draft.setBody("正文"); draft.setSkillType(SkillConst.TYPE_PROMPT);
        draft.setOriginRef("builder:100");
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(9L)).thenReturn(draft);
        when(mapper.updateById(any())).thenReturn(1);
        AiSkillRegistryService reg = mock(AiSkillRegistryService.class);
        AiSkill out = svc(mapper, reg).finalizeDraft(9L, "t1", 7L);
        assertEquals(SkillConst.STATUS_ACTIVE, out.getStatus());
        assertNull(out.getOriginRef());
        verify(reg).reloadAndBroadcast();
    }
    @Test void finalizeRejectsIncompleteDraft() {
        AiSkill draft = new AiSkill();
        draft.setId(9L); draft.setTenantId("t1"); draft.setOwnerUserId(7L);
        draft.setStatus(SkillConst.STATUS_DRAFT);
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(9L)).thenReturn(draft);
        assertThrows(ServiceException.class, () -> svc(mapper, mock(AiSkillRegistryService.class)).finalizeDraft(9L, "t1", 7L));
    }
    @Test void finalizeRejectsNonOwner() {
        AiSkill draft = new AiSkill();
        draft.setId(9L); draft.setTenantId("t1"); draft.setOwnerUserId(7L);
        draft.setStatus(SkillConst.STATUS_DRAFT); draft.setName("x"); draft.setDescription("d"); draft.setBody("b");
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectById(9L)).thenReturn(draft);
        assertThrows(ServiceException.class, () -> svc(mapper, mock(AiSkillRegistryService.class)).finalizeDraft(9L, "t1", 999L));
    }
}

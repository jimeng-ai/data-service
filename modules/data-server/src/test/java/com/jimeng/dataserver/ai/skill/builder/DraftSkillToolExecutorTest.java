package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftSkillToolExecutorTest {
    /** applyDraft/supports 只用到 aiSkillMapper；其余依赖 mock 占位即可。 */
    private DraftSkillToolExecutor newExecutor(AiSkillMapper mapper) {
        return new DraftSkillToolExecutor(mapper, mock(ChatConversationService.class),
                mock(RunEventTee.class), mock(SkillDraftStore.class));
    }

    @Test void supportsDraftSkill() {
        DraftSkillToolExecutor ex = newExecutor(mock(AiSkillMapper.class));
        assertTrue(ex.supports("draft_skill"));
    }
    @Test void createsDraftRowOnFirstCall() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        DraftSkillToolExecutor ex = newExecutor(mapper);
        ex.applyDraft(42L, "t1", 7L, Map.of("name", "csv-tool", "skillType", SkillConst.TYPE_DOER));
        verify(mapper).insert(argThat(s -> {
            AiSkill a = (AiSkill) s;
            return SkillConst.STATUS_DRAFT.equals(a.getStatus())
                    && "csv-tool".equals(a.getName())
                    && "builder:42".equals(a.getOriginRef());
        }));
    }
}

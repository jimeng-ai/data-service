package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.imports.*;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillImportServiceTest {
    @Test void importsPromptSkillFromCoordinates() {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch("anthropics", "skills", "main")).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), eq("brand")))
                .thenReturn(new SkillBundle("---\nname: brand\ndescription: 品牌\n---\n正文",
                        Map.of("references/x.md", new byte[]{1})));
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        AiSkillRegistryService registry = mock(AiSkillRegistryService.class);
        SkillImportService svc = new SkillImportService(fetcher, extractor, mapper, registry, null);
        AiSkill s = svc.importFromGithub("anthropics", "skills", "main", "brand", "t1", 7L);
        assertEquals("brand", s.getName());
        assertEquals(SkillConst.SOURCE_MARKET, s.getSource());
        assertEquals(SkillConst.TYPE_PROMPT, s.getSkillType());
        assertEquals("anthropics/skills@main:brand", s.getOriginRef());
        assertEquals(SkillConst.STATUS_ACTIVE, s.getStatus());
        verify(mapper).insert(any());
        verify(registry).reloadAndBroadcast();
    }
    @Test void importsDoerSkillStoresBundle() throws Exception {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch(any(), any(), any())).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), any()))
                .thenReturn(new SkillBundle("---\nname: pdf\ndescription: d\n---\n正文",
                        Map.of("scripts/run.py", new byte[]{1})));
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        doAnswer(inv -> { ((AiSkill) inv.getArgument(0)).setId(99L); return 1; }).when(mapper).insert(any());
        AiSkillRegistryService registry = mock(AiSkillRegistryService.class);
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        SkillImportService svc = new SkillImportService(fetcher, extractor, mapper, registry, minio);
        AiSkill s = svc.importFromGithub("o", "r", "main", "pdf", "t1", 7L);
        assertEquals(SkillConst.TYPE_DOER, s.getSkillType());
        assertEquals("skills/99/1/", s.getBundleKey());
        verify(minio, atLeast(2)).putObject(anyString(), any(), anyString());
        verify(mapper).updateById(any());
    }
}

package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.imports.GithubTarballFetcher;
import com.jimeng.dataserver.ai.skill.imports.SkillBundle;
import com.jimeng.dataserver.ai.skill.imports.SkillBundleExtractor;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillImportServiceManifestTest {

    private SkillImportService serviceReturning(String skillMd) {
        return serviceReturning(skillMd, mock(AiSkillMapper.class));
    }

    private SkillImportService serviceReturning(String skillMd, AiSkillMapper mapper) {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch(any(), any(), any())).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), any())).thenReturn(new SkillBundle(skillMd, Map.of()));
        return new SkillImportService(fetcher, extractor, mapper, mock(AiSkillRegistryService.class), null,
                mock(SkillSecretService.class));
    }

    @Test
    @DisplayName("GitHub 导入走宽松解析：不认识的顶层键忽略，metadata.jm 存进 manifest")
    void storesManifest() {
        AiSkill s = serviceReturning("---\nname: weather\ndescription: 查天气\nversion: 1.2\nmetadata:\n  jm:\n    web: true\n---\n正文\n")
                .importFromGithub("o", "r", "main", "weather", "t1", 7L);
        assertThat(s.getName()).isEqualTo("weather");
        assertThat(SkillManifest.fromJson(s.getManifest()).web()).isTrue();
    }

    @Test
    @DisplayName("GitHub 导入：description 里没加引号的冒号，按 Claude Code 的规则补引号后接受（见计划「本计划定下的设计」第 6 条）")
    void acceptsUnquotedColonLikeClaudeCode() {
        AiSkill s = serviceReturning("---\nname: w\ndescription: use when: x\n---\n正文\n")
                .importFromGithub("o", "r", "main", "w", "t1", 7L);
        assertThat(s.getDescription()).isEqualTo("use when: x");
    }

    @Test
    @DisplayName("交给 insert 的那一刻 manifest 已经在实体上：挪到 insert 之后，库里就没有 manifest 了，而返回的对象上还看得见")
    void manifestIsOnTheEntityWhenItIsInserted() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        // ArgumentCaptor 只留实体的引用，分不出「insert 之前还是之后设的」，所以在 insert 被调用的那一刻记下快照
        List<String> manifestAtInsert = new ArrayList<>();
        when(mapper.insert(any(AiSkill.class))).thenAnswer(inv -> {
            manifestAtInsert.add(inv.<AiSkill>getArgument(0).getManifest());
            return 1;
        });

        AiSkill returned = serviceReturning("---\nname: weather\ndescription: 查天气\nmetadata:\n  jm:\n    web: true\n---\n正文\n", mapper)
                .importFromGithub("o", "r", "main", "weather", "t1", 7L);

        ArgumentCaptor<AiSkill> inserted = ArgumentCaptor.forClass(AiSkill.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue()).isSameAs(returned);
        assertThat(manifestAtInsert).hasSize(1);
        assertThat(SkillManifest.fromJson(manifestAtInsert.get(0)).web()).isTrue();
    }
}

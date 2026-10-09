package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.imports.GithubTarballFetcher;
import com.jimeng.dataserver.ai.skill.imports.SkillBundle;
import com.jimeng.dataserver.ai.skill.imports.SkillBundleExtractor;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillImportServiceManifestTest {

    private SkillImportService serviceReturning(String skillMd) {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch(any(), any(), any())).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), any())).thenReturn(new SkillBundle(skillMd, Map.of()));
        return new SkillImportService(fetcher, extractor, mock(AiSkillMapper.class), mock(AiSkillRegistryService.class), null);
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
}

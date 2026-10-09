package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillImportServiceSecretsTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";

    @Test
    @DisplayName("GitHub 导入带 secrets.json：key 走同一套提取，包里没有 secrets.json，脚本里是占位符，运行方式按处理后的文件判")
    void importExtractsSecrets() throws Exception {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch(any(), any(), any())).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), any())).thenReturn(new SkillBundle(
                "---\nname: crm\ndescription: d\n---\n正文\n",
                Map.of("scripts/call.py", ("T='" + TOKEN + "'").getBytes(StandardCharsets.UTF_8),
                        "secrets.json", ("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}")
                                .getBytes(StandardCharsets.UTF_8))));
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        doAnswer(inv -> { ((AiSkill) inv.getArgument(0)).setId(99L); return 1; }).when(mapper).insert(any());
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        SkillSecretService secrets = mock(SkillSecretService.class);
        when(secrets.prepare(any(), any(), any(), any())).thenReturn(List.of());

        AiSkill s = new SkillImportService(fetcher, extractor, mapper, mock(AiSkillRegistryService.class), minio, secrets)
                .importFromGithub("o", "r", "main", "crm", "t1", 7L);

        assertThat(s.getSkillType()).isEqualTo(SkillConst.TYPE_DOER);
        assertThat(SkillManifest.fromJson(s.getManifest()).keys()).extracting(SkillManifest.KeyDecl::name).containsExactly("CRM_TOKEN");
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(minio, org.mockito.Mockito.atLeastOnce()).putObject(names.capture(), bodies.capture(), anyString());
        assertThat(names.getAllValues()).noneMatch(n -> n.endsWith("secrets.json"));
        for (byte[] b : bodies.getAllValues()) assertThat(new String(b, StandardCharsets.UTF_8)).doesNotContain(TOKEN);
        verify(secrets).prepare(eq("t1"), isNull(), isNull(), anyList());
        verify(secrets).save(eq(99L), eq(1), anyList());
    }
}

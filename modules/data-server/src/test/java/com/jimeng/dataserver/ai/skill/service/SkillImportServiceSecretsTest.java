package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.imports.GithubTarballFetcher;
import com.jimeng.dataserver.ai.skill.imports.SkillBundle;
import com.jimeng.dataserver.ai.skill.imports.SkillBundleExtractor;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
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
        // 交给 prepare 的条目带着值（不然 prepare 只会当成「沿用上一版」）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillSecretsFile.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(secrets).prepare(eq("t1"), isNull(), isNull(), entries.capture());
        assertThat(entries.getValue()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("CRM_TOKEN");
            assertThat(e.value()).isEqualTo(TOKEN);
        });
        verify(secrets).save(eq(99L), eq(1), anyList());
    }

    @Test
    @DisplayName("★ GitHub 导入也先抹值再解析：SKILL.md 的 frontmatter 写坏、报错片段带着 key 原文时，导入失败的文案里没有它（连被截断的前半截也没有），什么都不存")
    void importErrorsCarryNoValue() throws Exception {
        GithubTarballFetcher fetcher = mock(GithubTarballFetcher.class);
        when(fetcher.fetch(any(), any(), any())).thenReturn(new byte[]{1});
        SkillBundleExtractor extractor = mock(SkillBundleExtractor.class);
        when(extractor.extract(any(), any())).thenReturn(new SkillBundle(
                // 宽松模式会给「值里有冒号」的行补引号，缩进写错它救不回来；报错片段是「…字字sk-live-CR …」，值被截断在了中间
                "---\nname: crm\n  description: " + "字".repeat(20) + TOKEN + "\n---\n正文\n",
                Map.of("secrets.json", ("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}")
                        .getBytes(StandardCharsets.UTF_8))));
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        SkillSecretService secrets = mock(SkillSecretService.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SkillImportService(fetcher, extractor, mapper,
                        mock(AiSkillRegistryService.class), minio, secrets).importFromGithub("o", "r", "main", "crm", "t1", 7L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKILL.md")
                .hasMessageNotContaining("sk-live").hasMessageNotContaining(TOKEN);

        verify(mapper, org.mockito.Mockito.never()).insert(any());
        verify(minio, org.mockito.Mockito.never()).putObject(anyString(), any(), anyString());
        verify(secrets, org.mockito.Mockito.never()).save(any(), org.mockito.ArgumentMatchers.anyInt(), anyList());
    }
}

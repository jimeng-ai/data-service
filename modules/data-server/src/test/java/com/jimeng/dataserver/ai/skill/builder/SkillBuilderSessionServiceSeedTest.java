package com.jimeng.dataserver.ai.skill.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.manifest.JmMetadata;
import com.jimeng.dataserver.ai.skill.manifest.ParsedSkill;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「用 AI 改进」已发布 skill 时，构建器工作区的起点（{@code seedFromPublished}）。
 *
 * <p>钉的是接线：渲染器本身对不对由 {@code SkillMarkdownRendererTest} 管，这里管「库里 manifest 里的声明真的交给了渲染器」。
 * 漏了这一步不会报错——重建出的 SKILL.md 照样合规，只是 metadata.jm 没了；构建器改完一发布，域名和超时就静默丢了。
 */
class SkillBuilderSessionServiceSeedTest {

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final SkillSecretService secretService = mock(SkillSecretService.class);
    private final SkillBuilderSessionService svc = new SkillBuilderSessionService(
            mock(SkillBuilderSessionMapper.class), mock(AiSkillMapper.class), mock(AgentMapper.class),
            mock(ChatConversationService.class), storage, mock(SkillWorkspaceReader.class),
            mock(SkillBuilderProperties.class), new ObjectMapper(), secretService);

    /** 没有 bundleKey = 上传 / 导入的纯 PROMPT 老 skill，SKILL.md 要按库里的列重建。 */
    private static AiSkill published(String manifestJson) {
        AiSkill s = new AiSkill();
        s.setId(11L);
        s.setName("order-lookup");
        s.setDescription("查订单: 先问\"订单号\"");
        s.setBody("## 流程\n正文");
        s.setManifest(manifestJson);
        return s;
    }

    private String seededSkillMd(AiSkill base) throws Exception {
        svc.seedFromPublished("ws/1/", base);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).putObject(eq("ws/1/skill/order-lookup/SKILL.md"), bytes.capture(), eq("text/markdown"));
        return new String(bytes.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("没有 bundle 的已发布 skill：重建的 SKILL.md 带上库里 manifest 的 metadata.jm（五个字段原样，过严格校验）")
    void rebuiltSkillMdCarriesManifestDeclarations() throws Exception {
        JmMetadata jm = new JmMetadata(List.of("api.example.com", "*.cdn.example.com"), true, 90,
                SkillRequirement.CONNECTIONS, "always");
        String md = seededSkillMd(published(SkillManifest.of(jm, List.of()).toJson()));

        assertThat(md).contains("metadata:\n  jm:\n    hosts: [\"api.example.com\", \"*.cdn.example.com\"]");
        ParsedSkill p = SkillManifestParser.parse(md, SkillManifestParser.Mode.STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.name()).isEqualTo("order-lookup");
        assertThat(p.description()).isEqualTo("查订单: 先问\"订单号\"");
        assertThat(p.jm()).isEqualTo(jm);
    }

    @Test
    @DisplayName("老数据（manifest 为 NULL）：重建出来的 SKILL.md 没有 metadata 块，和改动前一样")
    void legacyRowWithoutManifestGetsNoMetadataBlock() throws Exception {
        String md = seededSkillMd(published(null));
        assertThat(md).doesNotContain("metadata");
        assertThat(SkillManifestParser.parse(md, SkillManifestParser.Mode.STRICT).errors()).isEmpty();
    }
    // ---------------------------------------------------------------- 第 1 轮修复：key 的声明回到工作区

    private static final String DIR = "ws/1/skill/order-lookup/";

    @Test
    @DisplayName("★ 改进已发布的 skill：这个版本在 skill_secret 里的 key，写一份不带值的 secrets.json 放进工作区（hosts 照库里存的；不限的 methods / paths 省略）")
    void keyDeclarationsComeBackToTheWorkspaceWithoutValues() throws Exception {
        AiSkill base = published(null);
        base.setVersion(2);
        when(secretService.declarationsOf(11L, 2)).thenReturn(List.of(
                new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com", "*.cdn.example.com"), List.of("GET"), null),
                new SkillManifest.KeyDecl("OTHER_KEY", List.of("x.example.com"), null, List.of("/v1/**"))));

        svc.seedFromPublished("ws/1/", base);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).putObject(eq(DIR + "secrets.json"), bytes.capture(), eq("application/json"));
        String json = new String(bytes.getValue(), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("value");
        SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(bytes.getValue());   // 种子过得了 secrets.json 自己的校验
        assertThat(parsed.valid()).as(parsed.errors().toString()).isTrue();
        assertThat(parsed.entries()).hasSize(2);
        SkillSecretsFile.Entry crm = parsed.entries().get(0);
        assertThat(crm.name()).isEqualTo("CRM_TOKEN");
        assertThat(crm.value()).isNull();                      // 没有 value = 沿用上一版的值
        assertThat(crm.hosts()).containsExactly("api.customer.com", "*.cdn.example.com");
        assertThat(crm.methods()).containsExactly("GET");
        assertThat(crm.paths()).isNull();                      // 不限 = 字段省略，不是 []
        assertThat(parsed.entries().get(1).methods()).isNull();
        assertThat(parsed.entries().get(1).paths()).containsExactly("/v1/**");
    }

    @Test
    @DisplayName("这个版本没有 key：不写 secrets.json")
    void noKeysNoSecretsFile() throws Exception {
        AiSkill base = published(null);
        base.setVersion(2);
        when(secretService.declarationsOf(11L, 2)).thenReturn(List.of());

        svc.seedFromPublished("ws/1/", base);

        verify(storage, never()).putObject(eq(DIR + "secrets.json"), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("★ 老 bundle 里残留的 secrets.json（S1 之前的 bundle 里可能有，是 key 原文）不带进构建器工作区；别的文件照常复制")
    void aStraySecretsJsonInAnOldBundleIsNotCopiedIntoTheWorkspace() throws Exception {
        AiSkill base = published(null);
        base.setBundleKey("skills/11/2/");
        base.setVersion(2);
        when(storage.listObjects("skills/11/2/")).thenReturn(List.of(
                "skills/11/2/SKILL.md", "skills/11/2/secrets.json", "skills/11/2/scripts/run.py"));
        when(secretService.declarationsOf(11L, 2)).thenReturn(List.of());

        svc.seedFromPublished("ws/1/", base);

        verify(storage).copyObject("skills/11/2/SKILL.md", DIR + "SKILL.md");
        verify(storage).copyObject("skills/11/2/scripts/run.py", DIR + "scripts/run.py");
        verify(storage, never()).copyObject(eq("skills/11/2/secrets.json"), anyString());
        verify(storage, never()).putObject(eq(DIR + "secrets.json"), any(byte[].class), anyString());
    }
}

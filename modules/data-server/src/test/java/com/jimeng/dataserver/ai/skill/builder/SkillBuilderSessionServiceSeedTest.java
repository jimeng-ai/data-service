package com.jimeng.dataserver.ai.skill.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.manifest.JmMetadata;
import com.jimeng.dataserver.ai.skill.manifest.ParsedSkill;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 「用 AI 改进」已发布 skill 时，构建器工作区的起点（{@code seedFromPublished}）。
 *
 * <p>钉的是接线：渲染器本身对不对由 {@code SkillMarkdownRendererTest} 管，这里管「库里 manifest 里的声明真的交给了渲染器」。
 * 漏了这一步不会报错——重建出的 SKILL.md 照样合规，只是 metadata.jm 没了；构建器改完一发布，域名和超时就静默丢了。
 */
class SkillBuilderSessionServiceSeedTest {

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final SkillBuilderSessionService svc = new SkillBuilderSessionService(
            mock(SkillBuilderSessionMapper.class), mock(AiSkillMapper.class), mock(AgentMapper.class),
            mock(ChatConversationService.class), storage, mock(SkillWorkspaceReader.class),
            mock(SkillBuilderProperties.class), new ObjectMapper());

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
}

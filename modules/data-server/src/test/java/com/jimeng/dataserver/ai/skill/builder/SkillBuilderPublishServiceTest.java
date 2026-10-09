package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.manifest.JmMetadata;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.AiSkillVersion;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.AiSkillVersionMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布构建器 skill 时补老版本记录（{@code recordVersionIfMissing}）。publish 主路径的 manifest 写入已换成发布处理器的结果，
 * 由 {@link SkillBuilderPublishSecretsTest} 在 mapper 被调用的那一刻记快照来断言，这里不重复。
 */
class SkillBuilderPublishServiceTest {

    private final AiSkillVersionMapper versionMapper = mock(AiSkillVersionMapper.class);
    private final SkillBuilderPublishService svc = new SkillBuilderPublishService(
            mock(SkillBuilderSessionService.class), mock(SkillBuilderSessionMapper.class), mock(SkillWorkspaceReader.class),
            mock(RagMinioStorageService.class), mock(AiSkillMapper.class), versionMapper,
            mock(AiSkillRegistryService.class), mock(ChatConversationService.class), mock(SkillSecretService.class),
            new SkillBuilderProperties());

    /** 本功能之前发布的 v2，库里没有对应的版本记录。 */
    private static AiSkill publishedRow() {
        AiSkill row = new AiSkill();
        row.setId(5L);
        row.setTenantId("t1");
        row.setVersion(2);
        row.setName("order-lookup");
        row.setDescription("查订单");
        row.setSkillType("DOER");
        row.setBundleKey("skills/5/2/");
        row.setBundleHash("h2");
        row.setManifest(SkillManifest.of(
                new JmMetadata(List.of("api.example.com"), false, 60, null, "optional"), List.of()).toJson());
        return row;
    }

    @Test
    @DisplayName("改进老 skill 前补的版本记录，带上 ai_skill 行上的 manifest（与同一行的 bundle 字段是同一时刻的东西）")
    void backfilledVersionRowCarriesTheManifestOfTheSkillRow() {
        when(versionMapper.selectCount(any())).thenReturn(0L);
        AiSkill row = publishedRow();

        svc.recordVersionIfMissing(row);

        ArgumentCaptor<AiSkillVersion> inserted = ArgumentCaptor.forClass(AiSkillVersion.class);
        verify(versionMapper).insert(inserted.capture());
        AiSkillVersion v = inserted.getValue();
        assertThat(v.getSkillId()).isEqualTo(5L);
        assertThat(v.getVersion()).isEqualTo(2);
        assertThat(v.getBundleKey()).isEqualTo("skills/5/2/");
        assertThat(v.getBundleHash()).isEqualTo("h2");
        assertThat(v.getManifest()).isEqualTo(row.getManifest());
        assertThat(SkillManifest.fromJson(v.getManifest()).hosts()).containsExactly("api.example.com");
    }

    @Test
    @DisplayName("这一版已经有版本记录就不再补")
    void existingVersionRowIsNotDuplicated() {
        when(versionMapper.selectCount(any())).thenReturn(1L);

        svc.recordVersionIfMissing(publishedRow());

        verify(versionMapper, never()).insert(any(AiSkillVersion.class));
    }
}

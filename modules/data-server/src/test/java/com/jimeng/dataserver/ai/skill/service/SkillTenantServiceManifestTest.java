package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTenantServiceManifestTest {

    private final SkillTenantService svc = new SkillTenantService(
            mock(AiSkillMapper.class), mock(AiSkillRegistryService.class), mock(SkillBuilderSessionMapper.class),
            mock(SkillSecretService.class));

    @Test
    @DisplayName("单文件上传走宽松解析：名字可以大写下划线、不认识的键忽略；metadata.jm 存进 manifest")
    void storesManifest() {
        AiSkill s = svc.createFromMarkdown("---\nname: Order_Lookup\ndescription: 查订单\nversion: 3\n"
                + "metadata:\n  jm:\n    hosts: [api.example.com]\n---\n正文\n", "t1", 7L);
        assertThat(s.getName()).isEqualTo("Order_Lookup");
        assertThat(SkillManifest.fromJson(s.getManifest()).hosts()).containsExactly("api.example.com");
    }

    @Test
    @DisplayName("单文件上传：description 里没加引号的冒号，按 Claude Code 的规则补引号后接受（见计划「本计划定下的设计」第 6 条）")
    void acceptsUnquotedColonLikeClaudeCode() {
        AiSkill s = svc.createFromMarkdown("---\nname: a\ndescription: use when: x\n---\n正文\n", "t1", 7L);
        assertThat(s.getDescription()).isEqualTo("use when: x");
    }

    @Test
    @DisplayName("单文件上传：补了引号也救不回来的 YAML（缩进错误）直接拒绝")
    void rejectsBadYaml() {
        assertThatThrownBy(() -> svc.createFromMarkdown("---\nname: a\n  description: x\n---\n正文\n", "t1", 7L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("YAML");
    }

    @Test
    @DisplayName("交给 insert 的那一刻 manifest 已经在实体上：挪到 insert 之后，库里就没有 manifest 了，而返回的对象上还看得见")
    void manifestIsOnTheEntityWhenItIsInserted() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        // ArgumentCaptor 只留实体的引用，之后对它的改动也看得见，分不出「insert 之前还是之后设的」，所以在 insert 被调用的那一刻记下快照
        List<String> manifestAtInsert = new ArrayList<>();
        when(mapper.insert(any(AiSkill.class))).thenAnswer(inv -> {
            manifestAtInsert.add(inv.<AiSkill>getArgument(0).getManifest());
            return 1;
        });
        SkillTenantService local = new SkillTenantService(
                mapper, mock(AiSkillRegistryService.class), mock(SkillBuilderSessionMapper.class), mock(SkillSecretService.class));

        AiSkill returned = local.createFromMarkdown("---\nname: a\ndescription: d\nmetadata:\n  jm:\n"
                + "    hosts: [api.example.com]\n    timeout-seconds: 90\n---\n正文\n", "t1", 7L);

        ArgumentCaptor<AiSkill> inserted = ArgumentCaptor.forClass(AiSkill.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue()).isSameAs(returned);
        assertThat(manifestAtInsert).hasSize(1);
        SkillManifest m = SkillManifest.fromJson(manifestAtInsert.get(0));
        assertThat(m.hosts()).containsExactly("api.example.com");
        assertThat(m.timeoutSeconds()).isEqualTo(90);
    }
}

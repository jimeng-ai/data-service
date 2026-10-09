package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SkillTenantServiceManifestTest {

    private final SkillTenantService svc = new SkillTenantService(
            mock(AiSkillMapper.class), mock(AiSkillRegistryService.class), mock(SkillBuilderSessionMapper.class));

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
}

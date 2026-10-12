package com.jimeng.dataserver.ai.skill.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillDetailViewKeysTest {

    @Test
    @DisplayName("详情带出 key 的名字和域名（只读 manifest，不碰密文）；没有 key 时是空列表")
    void keysFromManifest() throws Exception {
        AiSkill s = new AiSkill();
        s.setId(1L);
        s.setManifest(new SkillManifest(null, false, null, null, null, List.of(
                new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com"), List.of("GET"), null))).toJson());
        SkillDetailView v = SkillDetailView.of(s, List.of(), "张三");
        assertThat(v.getKeys()).singleElement().satisfies(k -> {
            assertThat(k.getName()).isEqualTo("CRM_TOKEN");
            assertThat(k.getHosts()).containsExactly("api.customer.com");
        });
        String json = new ObjectMapper().writeValueAsString(v);
        assertThat(json).doesNotContain("value").doesNotContain("ciphertext").doesNotContain("methods");

        AiSkill old = new AiSkill();
        old.setId(2L);
        assertThat(SkillDetailView.of(old, List.of(), null).getKeys()).isEmpty();
    }
}

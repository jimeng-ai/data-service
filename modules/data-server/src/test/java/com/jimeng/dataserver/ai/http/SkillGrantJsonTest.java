package com.jimeng.dataserver.ai.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 8 把 SkillGrant 序列化进 Redis 的运行登记；data-server 的 Jackson 是 2.11.1、不认 record，这里用真实 ObjectMapper 往返钉住。 */
class SkillGrantJsonTest {

    @Test
    @DisplayName("SkillGrant 列表经 ObjectMapper 往返后相等：含平台 skill（skillId 为 null）和带 key 声明的 manifest")
    void roundTrip() throws Exception {
        SkillManifest m = new SkillManifest(List.of("api.customer.com"), false, 30, null, null, List.of(
                new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com"), List.of("GET"), null)));
        List<SkillGrant> grants = List.of(new SkillGrant(7L, 3, "crm", m), new SkillGrant(null, 1, "web-search", SkillManifest.EMPTY));
        ObjectMapper json = new ObjectMapper();
        List<SkillGrant> back = json.readValue(json.writeValueAsString(grants), new TypeReference<List<SkillGrant>>() {});
        assertThat(back).isEqualTo(grants);
    }
}

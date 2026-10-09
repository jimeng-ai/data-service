package com.jimeng.dataserver.ai.skill.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.persistence.entity.SkillSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSecretEntityTest {

    private static final String CIPHERTEXT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    private static SkillSecret row() {
        SkillSecret r = new SkillSecret();
        r.setTenantId("t1");
        r.setSkillId(42L);
        r.setVersion(3);
        r.setName("CRM_TOKEN");
        r.setHosts("[\"api.customer.com\"]");
        r.setCiphertext(CIPHERTEXT);
        r.setCipherVersion(1);
        return r;
    }

    @Test
    @DisplayName("★ 密文不进 JSON：哪天谁直接把实体当响应返回，密文也不会出去；其余字段照常序列化")
    void ciphertextNeverReachesJson() throws Exception {
        String json = new ObjectMapper().writeValueAsString(row());
        assertThat(json).doesNotContain(CIPHERTEXT).doesNotContain("ciphertext");
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("name").asText()).isEqualTo("CRM_TOKEN");
        assertThat(node.get("hosts").asText()).isEqualTo("[\"api.customer.com\"]");
    }

    @Test
    @DisplayName("密文不进 toString")
    void ciphertextNeverReachesToString() {
        assertThat(row().toString()).doesNotContain(CIPHERTEXT).contains("CRM_TOKEN");
    }
}

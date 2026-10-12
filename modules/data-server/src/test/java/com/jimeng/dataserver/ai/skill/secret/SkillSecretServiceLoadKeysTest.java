package com.jimeng.dataserver.ai.skill.secret;

import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.http.SkillKey;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSecretServiceLoadKeysTest {

    @Test
    @DisplayName("只解密本次授权的那个版本的 key；平台 skill（没有 skillId）跳过；toString 不带值")
    void loadsGrantedVersionOnly() {
        CredentialCipher cipher = SkillSecretServiceTest.realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row(cipher, 7L, 1, "old-value-123"), row(cipher, 7L, 2, "new-value-456")));
        List<SkillKey> keys = new SkillSecretService(mapper, cipher).loadKeys(List.of(
                new SkillGrant(7L, 2, "crm", withKey("CRM_TOKEN")), new SkillGrant(null, 1, "connector", SkillManifest.EMPTY)));
        assertThat(keys).singleElement().satisfies(k -> {
            assertThat(k.value()).isEqualTo("new-value-456");
            assertThat(k.skillName()).isEqualTo("crm");
            assertThat(k.hosts()).containsExactly("api.customer.com");
            assertThat(k.methods()).containsExactly("GET");
            assertThat(k.paths()).isNull();
            assertThat(k.toString()).doesNotContain("new-value");
        });
    }

    @Test
    @DisplayName("没有 key 的 skill（平台 skill，或 manifest 里只声明了 hosts / web）不查库、不需要加密密钥")
    void noGrantsNoQuery() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillManifest hostsOnly = new SkillManifest(List.of("api.example.com"), false, null, null, null, List.of());
        assertThat(new SkillSecretService(mapper, new CredentialCipher()).loadKeys(List.of(
                new SkillGrant(null, 1, "x", SkillManifest.EMPTY), new SkillGrant(8L, 1, "hosts-only", hostsOnly)))).isEmpty();
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("解不开的 key 照样返回、标成不可用，不抛；同一个 skill 的其余 key 不受影响（R31）")
    void undecryptableKeyIsUnavailableNotFatal() {
        CredentialCipher cipher = SkillSecretServiceTest.realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret broken = row(cipher, 7L, 2, "x-value-123");
        broken.setName("BROKEN_KEY");
        broken.setCiphertext("not-a-ciphertext");
        when(mapper.selectList(any())).thenReturn(List.of(row(cipher, 7L, 2, "new-value-456"), broken));
        List<SkillKey> keys = new SkillSecretService(mapper, cipher).loadKeys(List.of(new SkillGrant(7L, 2, "crm", withKey("CRM_TOKEN"))));
        assertThat(keys).extracting(SkillKey::name, SkillKey::available)
                .containsExactlyInAnyOrder(tuple("CRM_TOKEN", true), tuple("BROKEN_KEY", false));
        assertThat(keys).allSatisfy(k -> assertThat(k.toString()).doesNotContain("value-"));
    }

    private static SkillManifest withKey(String name) {
        return new SkillManifest(List.of(), false, null, null, null,
                List.of(new SkillManifest.KeyDecl(name, List.of("api.customer.com"), null, null)));
    }

    private static SkillSecret row(CredentialCipher cipher, long skillId, int version, String value) {
        SkillSecret s = new SkillSecret();
        s.setSkillId(skillId);
        s.setVersion(version);
        s.setName("CRM_TOKEN");
        s.setHosts("[\"api.customer.com\"]");
        s.setMethods("[\"GET\"]");
        s.setCiphertext(cipher.encrypt(value));
        s.setCipherVersion(CredentialCipher.CURRENT_VERSION);
        return s;
    }
}

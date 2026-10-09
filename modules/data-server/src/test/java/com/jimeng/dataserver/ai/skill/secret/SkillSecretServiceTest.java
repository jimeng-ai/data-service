package com.jimeng.dataserver.ai.skill.secret;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSecretServiceTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";

    static CredentialCipher realCipher() {
        CredentialCipher c = new CredentialCipher();
        ReflectionTestUtils.setField(c, "keyBase64", Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    private static SkillSecretsFile.Entry entry(String value, List<String> hosts, List<String> methods) {
        return new SkillSecretsFile.Entry("CRM_TOKEN", value, hosts, methods, null);
    }

    private static SkillSecret previousRow(CredentialCipher cipher, List<String> methodsJsonList) {
        SkillSecret old = new SkillSecret();
        old.setName("CRM_TOKEN");
        old.setHosts("[\"api.customer.com\"]");
        old.setMethods(methodsJsonList == null ? null : "[\"" + String.join("\",\"", methodsJsonList) + "\"]");
        old.setCiphertext(cipher.encrypt(TOKEN));
        old.setCipherVersion(CredentialCipher.CURRENT_VERSION);
        return old;
    }

    @Test
    @DisplayName("新值加密保存：密文里没有原文，解得回原值；租户、范围都记上")
    void encryptsNewValue() {
        CredentialCipher cipher = realCipher();
        SkillSecretService svc = new SkillSecretService(mock(SkillSecretMapper.class), cipher);
        List<SkillSecret> rows = svc.prepare("t1", 42L, null, List.of(entry(TOKEN, List.of("api.customer.com"), List.of("GET"))));
        SkillSecret r = rows.get(0);
        assertThat(r.getTenantId()).isEqualTo("t1");
        assertThat(r.getHosts()).isEqualTo("[\"api.customer.com\"]");
        assertThat(r.getMethods()).isEqualTo("[\"GET\"]");
        assertThat(r.getPaths()).isNull();
        assertThat(r.getCiphertext()).doesNotContain(TOKEN);
        assertThat(cipher.decrypt(r.getCiphertext(), r.getCipherVersion())).isEqualTo(TOKEN);
        assertThat(r.toString()).doesNotContain(r.getCiphertext());
    }

    @Test
    @DisplayName("没给值、范围没变（顺序不同也算没变）：沿用上一版的密文")
    void reusesWhenScopeUnchanged() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret old = previousRow(cipher, List.of("POST", "GET"));
        when(mapper.selectList(any())).thenReturn(List.of(old));
        List<SkillSecret> rows = new SkillSecretService(mapper, cipher)
                .prepare("t1", 42L, 3, List.of(entry(null, List.of("api.customer.com"), List.of("GET", "POST"))));
        assertThat(rows.get(0).getCiphertext()).isEqualTo(old.getCiphertext());
    }

    @Test
    @DisplayName("没给值但 hosts 改了：旧值作废，拒绝发布（不能靠改一个域名把 key 发到别处去）")
    void rejectsWhenHostsChanged() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null)));
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher)
                .prepare("t1", 42L, 3, List.of(entry(null, List.of("evil.example.com"), null))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN").hasMessageContaining("作废");
    }

    @Test
    @DisplayName("没给值但 methods 从「不限」变成 [GET]：同样算范围改了")
    void rejectsWhenMethodsChanged() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null)));
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher)
                .prepare("t1", 42L, 3, List.of(entry(null, List.of("api.customer.com"), List.of("GET")))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("作废");
    }

    @Test
    @DisplayName("第一次出现的 key 没给值：拒绝")
    void rejectsMissingValueWithoutPrevious() {
        assertThatThrownBy(() -> new SkillSecretService(mock(SkillSecretMapper.class), realCipher())
                .prepare("t1", 42L, null, List.of(entry(null, List.of("api.customer.com"), null))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("没有给值");
    }

    @Test
    @DisplayName("没配加密密钥：说清楚是平台没配，不是用户写错了")
    void cipherUnavailable() {
        assertThatThrownBy(() -> new SkillSecretService(mock(SkillSecretMapper.class), new CredentialCipher())
                .prepare("t1", 42L, null, List.of(entry(TOKEN, List.of("api.customer.com"), null))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CONNECTION_CREDENTIAL_KEY");
    }

    @Test
    @DisplayName("save 写上 skillId 和版本号，id 交给雪花算法重新生成")
    void saveStampsSkillAndVersion() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret r = new SkillSecret();
        r.setId(1L);
        new SkillSecretService(mapper, realCipher()).save(42L, 4, List.of(r));
        ArgumentCaptor<SkillSecret> c = ArgumentCaptor.forClass(SkillSecret.class);
        verify(mapper, times(1)).insert(c.capture());
        assertThat(c.getValue().getId()).isNull();
        assertThat(c.getValue().getSkillId()).isEqualTo(42L);
        assertThat(c.getValue().getVersion()).isEqualTo(4);
    }
}

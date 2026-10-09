package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSecretServiceTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";

    /** 要看 Wrapper 里的条件（按 skill_id + version 查、按 skill_id 删），就得渲染它，渲染要用 MP 的实体缓存（同 RoleResourceServiceTest）。 */
    @BeforeAll
    static void initEntityCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkillSecret.class);
    }

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
    @Test
    @DisplayName("没给值但 paths 改了：旧值作废")
    void rejectsWhenPathsChanged() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret old = previousRow(cipher, null);
        old.setPaths("[\"/v1/orders/**\"]");
        when(mapper.selectList(any())).thenReturn(List.of(old));
        SkillSecretsFile.Entry widened = new SkillSecretsFile.Entry("CRM_TOKEN", null, List.of("api.customer.com"), null, List.of("/v1/**"));
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher).prepare("t1", 42L, 3, List.of(widened)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("作废");
        SkillSecretsFile.Entry same = new SkillSecretsFile.Entry("CRM_TOKEN", null, List.of("api.customer.com"), null, List.of("/v1/orders/**"));
        assertThat(new SkillSecretService(mapper, cipher).prepare("t1", 42L, 3, List.of(same)).get(0).getCiphertext())
                .isEqualTo(old.getCiphertext());
    }

    @Test
    @DisplayName("没给值但 methods 从 [GET] 放宽成「不限」（null）：同样作废，只有 null 才等于 null")
    void rejectsWhenRestrictionIsDropped() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, List.of("GET"))));
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher)
                .prepare("t1", 42L, 3, List.of(entry(null, List.of("api.customer.com"), null))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("作废");
    }

    @Test
    @DisplayName("全部沿用旧值时 prepare 只复制密文，不需要密钥（核对文件里有没有旧值的原文，是发布处理器解密时的事）")
    void reusingNeedsNoCipherToCopyCiphertext() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret old = previousRow(realCipher(), null);
        when(mapper.selectList(any())).thenReturn(List.of(old));
        List<SkillSecret> rows = new SkillSecretService(mapper, new CredentialCipher())
                .prepare("t1", 42L, 3, List.of(entry(null, List.of("api.customer.com"), null)));
        assertThat(rows.get(0).getCiphertext()).isEqualTo(old.getCiphertext());
    }

    @Test
    @DisplayName("★ 找上一版的 key 是按 skill_id + version 查的（改成只按 skillId、或者查 version+1，这条会红）")
    void previousIsLookedUpBySkillIdAndVersion() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null)));
        new SkillSecretService(mapper, cipher).prepare("t1", 42L, 3, List.of(entry(null, List.of("api.customer.com"), null)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SkillSecret>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(w.capture());
        assertThat(w.getValue().getSqlSegment()).contains("skill_id").contains("version");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w.getValue()).getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(42L, 3);
    }

    @Test
    @DisplayName("★ 删 skill 的 key 是物理删除、按 skill_id：密文不再留在表里（BaseMapper.delete 对带 @TableLogic 的实体只是打删除标记）")
    void deleteAllIsPhysicalAndBySkillId() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecretService svc = new SkillSecretService(mapper, realCipher());
        svc.deleteAll(42L);
        verify(mapper).deleteBySkillId(42L);
        verify(mapper, never()).delete(any());
        svc.deleteAll(null);
        verify(mapper, times(1)).deleteBySkillId(any());
    }

    @Test
    @DisplayName("previousKeys：names 不需要密钥；values 只解密被问到的，解得回原值")
    void previousKeysDecryptsOnlyWhatIsAsked() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret other = previousRow(cipher, null);
        other.setName("OTHER_KEY");
        other.setCiphertext("not-even-base64");   // 没被问到就不能去解它
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null), other));

        PreviousKeys prev = new SkillSecretService(mapper, cipher).previousKeys(42L, 3);

        assertThat(prev.names()).containsExactlyInAnyOrder("CRM_TOKEN", "OTHER_KEY");
        assertThat(prev.values(List.of("CRM_TOKEN"))).isEqualTo(Map.of("CRM_TOKEN", TOKEN));
        assertThat(prev.values(List.of("NEVER_HAD_IT"))).isEmpty();
        assertThat(prev.values(List.of())).isEmpty();
        assertThat(new SkillSecretService(mapper, cipher).previousKeys(null, 3).names()).isEmpty();
    }

    @Test
    @DisplayName("★ 没配密钥就解不了沿用的值：拒绝，不是跳过——跳过核对等于放行（没有要解的就不需要密钥）")
    void previousKeysRefuseWhenTheCipherIsMissing() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(realCipher(), null)));
        PreviousKeys prev = new SkillSecretService(mapper, new CredentialCipher()).previousKeys(42L, 3);
        assertThat(prev.names()).containsExactly("CRM_TOKEN");
        assertThat(prev.values(List.of())).isEmpty();
        assertThatThrownBy(() -> prev.values(List.of("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无法核对沿用的 key").hasMessageContaining("CONNECTION_CREDENTIAL_KEY");
    }

    @Test
    @DisplayName("解密失败（密钥轮换过、密文坏了）：拒绝，文案点名 key、不带密文")
    void previousKeysRefuseWhenDecryptionFails() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret broken = previousRow(cipher, null);
        broken.setCiphertext("bm90LWEtdmFsaWQtY2lwaGVydGV4dC1hdC1hbGwtbm90LWEtdmFsaWQ=");
        when(mapper.selectList(any())).thenReturn(List.of(broken));
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher).previousKeys(42L, 3).values(List.of("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN").hasMessageContaining("解密失败")
                .hasMessageNotContaining(broken.getCiphertext());
    }

    @Test
    @DisplayName("declarationsOf：由 skill_secret 行（不是 manifest）还原 key 的声明；不限的 methods / paths 是 null")
    void declarationsComeFromTheRows() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret a = previousRow(realCipher(), List.of("GET", "POST"));
        a.setPaths("[\"/v1/orders/**\"]");
        SkillSecret b = previousRow(realCipher(), null);
        b.setName("ANOTHER_KEY");
        b.setHosts("[\"x.example.com\",\"*.y.example.com\"]");
        when(mapper.selectList(any())).thenReturn(List.of(a, b));

        List<SkillManifest.KeyDecl> decls = new SkillSecretService(mapper, realCipher()).declarationsOf(42L, 3);

        assertThat(decls).extracting(SkillManifest.KeyDecl::name).containsExactly("ANOTHER_KEY", "CRM_TOKEN");
        assertThat(decls.get(0).hosts()).containsExactly("x.example.com", "*.y.example.com");
        assertThat(decls.get(0).methods()).isNull();
        assertThat(decls.get(0).paths()).isNull();
        assertThat(decls.get(1).methods()).containsExactly("GET", "POST");
        assertThat(decls.get(1).paths()).containsExactly("/v1/orders/**");
        assertThat(decls.toString()).doesNotContain(TOKEN);
    }
}

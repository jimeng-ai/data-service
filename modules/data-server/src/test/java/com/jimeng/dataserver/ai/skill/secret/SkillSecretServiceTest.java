package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.skill.LogCapture;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.manifest.SkillPackageProcessor;
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
    @DisplayName("previousKeys：一次把上一版所有 key 解成一份快照（发布时最先调，R23）——names 里有全部 key，values 只给解得开的、解得回原值，"
            + "解不开的就是 undecryptable()；之后只是查表，不再解密")
    void previousKeysIsADecryptedSnapshot() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret other = previousRow(cipher, null);
        other.setName("OTHER_KEY");
        other.setCiphertext("not-even-base64");   // 坏的密文：解不开，不抛
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null), other));
        CredentialCipher counting = org.mockito.Mockito.spy(cipher);

        PreviousKeys prev = new SkillSecretService(mapper, counting).previousKeys(42L, 3);

        assertThat(prev.names()).containsExactlyInAnyOrder("CRM_TOKEN", "OTHER_KEY");
        assertThat(prev.values(List.of("CRM_TOKEN"))).isEqualTo(Map.of("CRM_TOKEN", TOKEN));
        assertThat(prev.values(List.of("NEVER_HAD_IT"))).isEmpty();
        assertThat(prev.values(List.of())).isEmpty();
        assertThat(prev.allValues()).isEqualTo(Map.of("CRM_TOKEN", TOKEN));
        assertThat(prev.undecryptable()).containsExactly("OTHER_KEY");
        verify(counting, times(2)).decryptOrNull(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());   // 每个 key 只解一次
        assertThat(new SkillSecretService(mapper, cipher).previousKeys(null, 3).names()).isEmpty();
    }

    @Test
    @DisplayName("★ 没配密钥就解不了上一版的值：previousKeys 直接拒绝，不是跳过——跳过核对等于放行；上一版没有 key 行就不需要密钥")
    void previousKeysRefuseWhenTheCipherIsMissing() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(realCipher(), null)));
        SkillSecretService noCipher = new SkillSecretService(mapper, new CredentialCipher());
        assertThatThrownBy(() -> noCipher.previousKeys(42L, 3))
                .isInstanceOf(ServiceException.class).hasMessageContaining("加密密钥没有配置").hasMessageContaining("CONNECTION_CREDENTIAL_KEY");

        when(mapper.selectList(any())).thenReturn(List.of());
        assertThat(noCipher.previousKeys(42L, 3).names()).isEmpty();
    }

    @Test
    @DisplayName("★ R27 配了密钥、但某个 key 的密文解不开（密钥换过、密文坏了）：不抛，解得开的照常给，解不开的单独报出来（不参与抹值）；"
            + "key 服务这里一行日志都不记（第 4 轮：WARN 要等处理器的名字检查过了，由发布入口抹过值再记；也不经 decrypt 构造 ServiceException、记 ERROR）")
    void aKeyThatCannotBeDecryptedIsReportedNotThrown() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret good = previousRow(cipher, null);
        SkillSecret broken = previousRow(cipher, null);
        broken.setName("BROKEN_KEY");
        broken.setSkillId(42L);
        broken.setCiphertext("bm90LWEtdmFsaWQtY2lwaGVydGV4dC1hdC1hbGwtbm90LWEtdmFsaWQ=");
        when(mapper.selectList(any())).thenReturn(List.of(good, broken));

        try (LogCapture logs = LogCapture.everything()) {
            PreviousKeys prev = new SkillSecretService(mapper, cipher).previousKeys(42L, 3);

            assertThat(prev.names()).containsExactlyInAnyOrder("CRM_TOKEN", "BROKEN_KEY");   // 名字照样在：文件里还在用 {{BROKEN_KEY}} 的核对要用
            assertThat(prev.undecryptable()).containsExactly("BROKEN_KEY");
            assertThat(prev.values(List.of("BROKEN_KEY"))).isEmpty();                          // 单独问也不抛
            assertThat(prev.allValues()).isEqualTo(Map.of("CRM_TOKEN", TOKEN));

            assertThat(logs.warnings()).isEmpty();
            assertThat(logs.errors()).isEmpty();
        }
    }

    @Test
    @DisplayName("PreviousKeys.decrypted(值, 解不开的)：names 包含两者，values 只有解得开的，undecryptable 就是解不开的那些；不带第二个参数就是没有解不开的")
    void decryptedKeepsTheUndecryptableNames() {
        PreviousKeys prev = PreviousKeys.decrypted(Map.of("GOOD_KEY", "good-value-123"), java.util.Set.of("BROKEN_KEY"));
        assertThat(prev.names()).containsExactlyInAnyOrder("GOOD_KEY", "BROKEN_KEY");
        assertThat(prev.allValues()).isEqualTo(Map.of("GOOD_KEY", "good-value-123"));
        assertThat(prev.undecryptable()).containsExactly("BROKEN_KEY");
        assertThat(PreviousKeys.decrypted(Map.of("GOOD_KEY", "good-value-123")).undecryptable()).isEmpty();
        assertThat(PreviousKeys.NONE.allValues()).isEmpty();
        assertThat(PreviousKeys.NONE.undecryptable()).isEmpty();
    }

    @Test
    @DisplayName("★ prepare 里所有拼了名字的异常文案，一律过 SecretValues 抹一遍（名字里万一带着这次的值，不会原样进异常和 ERROR 日志）")
    void prepareMessagesNeverCarryAValue() {
        SkillSecretsFile.Entry carrier = new SkillSecretsFile.Entry("MY_FIRSTSECRET1_KEY", null, List.of("a.example.com"), null, null);
        SkillSecretsFile.Entry owner = new SkillSecretsFile.Entry("OWNER_KEY", "FIRSTSECRET1", List.of("a.example.com"), null, null);

        // 第一次出现、没给值
        assertThatThrownBy(() -> new SkillSecretService(mock(SkillSecretMapper.class), realCipher()).prepare("t1", 42L, null, List.of(carrier, owner)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("没有给值").hasMessageNotContaining("FIRSTSECRET1");

        // 范围改了
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret old = previousRow(cipher, null);
        old.setName("MY_FIRSTSECRET1_KEY");
        when(mapper.selectList(any())).thenReturn(List.of(old));
        SkillSecretsFile.Entry moved = new SkillSecretsFile.Entry("MY_FIRSTSECRET1_KEY", null, List.of("evil.example.com"), null, null);
        assertThatThrownBy(() -> new SkillSecretService(mapper, cipher).prepare("t1", 42L, 3, List.of(moved, owner)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("作废").hasMessageNotContaining("FIRSTSECRET1");
    }

    // ---------------------------------------------------------------- 第 4 轮 3：PreviousKeys 的契约跟上 R27

    private static CredentialCipher cipherWithKey(byte fill) {
        CredentialCipher c = new CredentialCipher();
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        ReflectionTestUtils.setField(c, "keyBase64", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @Test
    @DisplayName("★ 第 4 轮 3（复审探针 B）：key 服务给的 PreviousKeys 直接交给发布处理器（不经任何「先打开」的步骤），上一版解不开的 key 没写 value——拒绝。"
            + "以前处理器放行，随后 prepare 把解不开的旧密文原样复制进新版本，网关永远解不开它")
    void theKeyServicesPreviousKeysStraightIntoTheProcessorCannotReuseAnUndecryptableKey() {
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret broken = previousRow(realCipher(), null);   // 用 zeros 密钥加密的 CRM_TOKEN
        broken.setSkillId(5L);
        when(mapper.selectList(any())).thenReturn(List.of(broken));
        SkillSecretService svc = new SkillSecretService(mapper, cipherWithKey((byte) 1));   // 现在配的是另一把
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n带上 {{CRM_TOKEN}} 调接口\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("secrets.json", "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, svc.previousKeys(5L, 3)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("key CRM_TOKEN 上一版的值解不开").hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("★ 第 4 轮 3：不管从哪儿拿到 PreviousKeys，undecryptable() 都如实返回——key 服务给的、decrypted(..) 造的、NONE，"
            + "以及只实现了 names / values 的（在 names() 里、却拿不到值的就是解不开的）")
    void undecryptableIsTruthfulWhereverThePreviousKeysComeFrom() {
        CredentialCipher cipher = realCipher();
        SkillSecretMapper mapper = mock(SkillSecretMapper.class);
        SkillSecret broken = previousRow(cipherWithKey((byte) 1), null);   // 用别的密钥加密的：现在的密钥解不开
        broken.setName("BROKEN_KEY");
        when(mapper.selectList(any())).thenReturn(List.of(previousRow(cipher, null), broken));
        PreviousKeys fromTheService = new SkillSecretService(mapper, cipher).previousKeys(42L, 3);
        assertThat(fromTheService.undecryptable()).containsExactly("BROKEN_KEY");
        assertThat(fromTheService.names()).containsExactlyInAnyOrder("CRM_TOKEN", "BROKEN_KEY");
        assertThat(fromTheService.allValues()).isEqualTo(Map.of("CRM_TOKEN", TOKEN));

        assertThat(PreviousKeys.decrypted(Map.of("GOOD_KEY", "good-value-123"), java.util.Set.of("BROKEN_KEY")).undecryptable())
                .containsExactly("BROKEN_KEY");
        assertThat(PreviousKeys.NONE.undecryptable()).isEmpty();

        PreviousKeys namesAndValuesOnly = new PreviousKeys() {
            @Override
            public java.util.Set<String> names() {
                return java.util.Set.of("GOOD_KEY", "SILENT_KEY");
            }

            @Override
            public Map<String, String> values(java.util.Collection<String> wanted) {
                return wanted.contains("GOOD_KEY") ? Map.of("GOOD_KEY", "good-value-123") : Map.of();
            }
        };
        assertThat(namesAndValuesOnly.undecryptable()).containsExactly("SILENT_KEY");
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

package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.AiSkillVersionMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「用 AI 改进」带 key 的 skill，从种子到发布的完整一程：真的草稿读取、真的发布处理器、真的 key 服务和加密，只有存储和 mapper 是桩。
 *
 * <p>钉住的是：种子里的 secrets.json 不带 value、范围原样；不改范围再发布就沿用旧密文；模型把旧 key 原文写进脚本，
 * 发布后 bundle 里也只有 {@code {{名字}}}；沿用的值解不出来（没配密钥）就拒绝，而不是跳过核对；
 * 上一版有的 key 这一版删了、文件里还在用，拒绝。
 */
class SkillBuilderImproveSecretsTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";
    private static final String MD_USING_THE_KEY = "---\nname: refund-flow\ndescription: 退款\n---\n带上 {{CRM_TOKEN}} 调接口\n";

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final SkillSecretMapper secretMapper = mock(SkillSecretMapper.class);
    private final AiSkillMapper aiSkillMapper = mock(AiSkillMapper.class);
    private final AiSkillVersionMapper versionMapper = mock(AiSkillVersionMapper.class);
    private final SkillBuilderSessionService sessions = mock(SkillBuilderSessionService.class);
    private final CredentialCipher cipher = realCipher();

    private static CredentialCipher realCipher() {
        CredentialCipher c = new CredentialCipher();
        ReflectionTestUtils.setField(c, "keyBase64", Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @BeforeEach
    void beginTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 上一版（v3）里的 CRM_TOKEN：值是 TOKEN，只能发往 api.customer.com；methods / paths 按参数（null = 不限）。 */
    private SkillSecret previousRow(String methodsJson, String pathsJson) {
        SkillSecret row = new SkillSecret();
        row.setTenantId("t1");
        row.setSkillId(5L);
        row.setVersion(3);
        row.setName("CRM_TOKEN");
        row.setHosts("[\"api.customer.com\"]");
        row.setMethods(methodsJson);
        row.setPaths(pathsJson);
        row.setCiphertext(cipher.encrypt(TOKEN));
        row.setCipherVersion(CredentialCipher.CURRENT_VERSION);
        return row;
    }

    private SkillBuilderPublishService serviceWith(CredentialCipher withCipher) {
        return new SkillBuilderPublishService(sessions, mock(SkillBuilderSessionMapper.class),
                new SkillWorkspaceReader(storage, new SkillBuilderProperties()), storage, aiSkillMapper, versionMapper,
                mock(AiSkillRegistryService.class), mock(ChatConversationService.class),
                new SkillSecretService(secretMapper, withCipher), new SkillBuilderProperties());
    }

    /** 改进 refund-flow（skill 5，当前 v3，上一版的 key 行是 previousRow）的会话，工作区里放这些文件。 */
    private void improvementSession(Map<String, String> workspaceFiles, SkillSecret previousRow) throws Exception {
        SkillBuilderSession session = new SkillBuilderSession();
        session.setId(1L);
        session.setTenantId("t1");
        session.setOwnerUserId(7L);
        session.setConversationId(9L);
        session.setWorkspacePrefix("ws/1/");
        session.setBaseSkillId(5L);
        session.setBaseVersion(3);
        when(sessions.requireOwned(1L)).thenReturn(session);
        when(sessions.requireActive(session)).thenReturn(session);

        List<ObjectInfo> infos = new ArrayList<>();
        for (Map.Entry<String, String> e : workspaceFiles.entrySet()) {
            String name = "ws/1/skill/refund-flow/" + e.getKey();
            infos.add(new ObjectInfo(name, b(e.getValue()).length, new Date()));
            when(storage.readBytes(name)).thenReturn(b(e.getValue()));
        }
        when(storage.listObjectInfos("ws/1/")).thenReturn(infos);

        AiSkill base = new AiSkill();
        base.setId(5L);
        base.setTenantId("t1");
        base.setName("refund-flow");
        base.setVersion(3);
        when(aiSkillMapper.selectById(5L)).thenReturn(base);
        when(versionMapper.selectCount(any())).thenReturn(1L);   // v3 已有版本记录，不用补
        when(secretMapper.selectList(any())).thenReturn(List.of(previousRow));
    }

    /** 「用 AI 改进」放进工作区的种子：由 skill_secret 行还原出来的、不带值的 secrets.json（与 seedFromPublished 同样的两步）。 */
    private String seedFile() {
        return SkillSecretsFile.valueLessJson(new SkillSecretService(secretMapper, cipher).declarationsOf(5L, 3));
    }

    private void seedThenPublishReusesTheCiphertextAndKeepsTheBundleClean(String methodsJson, String pathsJson) throws Exception {
        SkillSecret row = previousRow(methodsJson, pathsJson);
        when(secretMapper.selectList(any())).thenReturn(List.of(row));
        String seed = seedFile();
        // 不限的 methods / paths 在种子里是省略（写成 [] 会被 secrets.json 的校验拒绝），限了的原样回来
        assertThat(seed).doesNotContain("value").doesNotContain(TOKEN);
        assertThat(seed.contains("methods")).isEqualTo(methodsJson != null);
        assertThat(seed.contains("paths")).isEqualTo(pathsJson != null);

        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\nprint(TOKEN)\n");   // 用户又贴了一次 key，模型直接写进了脚本
        workspace.put("secrets.json", seed);
        improvementSession(workspace, row);

        serviceWith(cipher).publish(1L);

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(storage, atLeastOnce()).putObject(names.capture(), bodies.capture(), anyString());
        for (int i = 0; i < names.getAllValues().size(); i++) {
            assertThat(new String(bodies.getAllValues().get(i), StandardCharsets.UTF_8)).as(names.getAllValues().get(i)).doesNotContain(TOKEN);
        }
        int script = names.getAllValues().indexOf("skills/5/4/scripts/call.py");
        assertThat(script).isNotNegative();
        assertThat(new String(bodies.getAllValues().get(script), StandardCharsets.UTF_8)).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\nprint(TOKEN)\n");

        ArgumentCaptor<SkillSecret> saved = ArgumentCaptor.forClass(SkillSecret.class);
        verify(secretMapper).insert(saved.capture());
        assertThat(saved.getValue().getCiphertext()).isEqualTo(row.getCiphertext());   // 范围没变：沿用旧密文
        assertThat(saved.getValue().getVersion()).isEqualTo(4);
        assertThat(saved.getValue().getSkillId()).isEqualTo(5L);
        assertThat(saved.getValue().getMethods()).isEqualTo(methodsJson);
        assertThat(saved.getValue().getPaths()).isEqualTo(pathsJson);
    }

    @Test
    @DisplayName("★ 种子（不带值、范围不限）→ 模型把旧 key 原文写进脚本 → 发布：沿用旧密文，bundle 里只有 {{名字}}，哪里都没有值")
    void seedThenPublishWithAnUnrestrictedKey() throws Exception {
        seedThenPublishReusesTheCiphertextAndKeepsTheBundleClean(null, null);
    }

    @Test
    @DisplayName("★ 同上，key 限了 methods 和 paths：种子里原样回来，不改范围再发布照样沿用旧密文")
    void seedThenPublishWithARestrictedKey() throws Exception {
        seedThenPublishReusesTheCiphertextAndKeepsTheBundleClean("[\"GET\",\"POST\"]", "[\"/v1/orders/**\"]");
    }

    @Test
    @DisplayName("★ 沿用的值要解密来核对文件：密钥没配（或解不开）就拒绝发布，什么都不写——不是跳过核对放行")
    void withoutTheCipherAReusedKeyCannotBePublished() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        SkillSecret row = previousRow(null, null);
        when(secretMapper.selectList(any())).thenReturn(List.of(row));
        workspace.put("secrets.json", seedFile());
        improvementSession(workspace, row);

        SkillBuilderPublishService noCipher = serviceWith(new CredentialCipher());
        assertThatThrownBy(() -> noCipher.publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("无法核对沿用的 key").hasMessageContaining("CONNECTION_CREDENTIAL_KEY");

        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(storage, never()).deletePrefix(anyString());
        verify(secretMapper, never()).insert(any(SkillSecret.class));
        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
    }

    @Test
    @DisplayName("★ 上一版有的 key 这一版的 secrets.json 里没了、文件里还在用 {{X}}：拒绝发布，什么都不写（以前是静默丢 key）")
    void aKeyThatWasDroppedButIsStillUsedIsRejected() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);   // 模型把 secrets.json 整个删了（或者重写时漏了这一条）
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("{{CRM_TOKEN}}").hasMessageContaining("secrets.json 里没有 CRM_TOKEN 了");

        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(secretMapper, never()).insert(any(SkillSecret.class));
    }

    @Test
    @DisplayName("★ 改进会话里 frontmatter 写坏、报错片段正好带着沿用的 key 原文：发布失败的文案里不能有它（连被截断的前半截也不行）")
    void aYamlErrorAroundAReusedKeyCarriesNoPartOfIt() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", "---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + TOKEN + " 调接口\n---\n带上 {{CRM_TOKEN}}\n");
        SkillSecret row = previousRow(null, null);
        when(secretMapper.selectList(any())).thenReturn(List.of(row));
        workspace.put("secrets.json", seedFile());
        improvementSession(workspace, row);

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("YAML").hasMessageNotContaining("sk-live").hasMessageNotContaining(TOKEN);
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("范围改了（hosts 换了一个域名）：旧值作废，要求重新给值；什么都不写")
    void aChangedRangeInvalidatesTheOldValue() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"hosts\":[\"evil.example.com\"]}}");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("作废");
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(secretMapper, never()).insert(any(SkillSecret.class));
    }
}

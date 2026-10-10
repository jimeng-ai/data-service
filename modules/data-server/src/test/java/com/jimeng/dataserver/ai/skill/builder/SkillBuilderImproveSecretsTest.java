package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.LogCapture;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    /** 上一版 WECOM_HOOK 的值（企业微信 webhook 的 key，复审探针里用的那个）。 */
    private static final String OLD = "693a91f6-7a2b-4c1d-9e8f-0123456789ab";
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
        return previousRowOf("CRM_TOKEN", TOKEN, "[\"api.customer.com\"]", methodsJson, pathsJson);
    }

    private SkillSecret previousRowOf(String name, String value, String hostsJson, String methodsJson, String pathsJson) {
        SkillSecret row = new SkillSecret();
        row.setTenantId("t1");
        row.setSkillId(5L);
        row.setVersion(3);
        row.setName(name);
        row.setHosts(hostsJson);
        row.setMethods(methodsJson);
        row.setPaths(pathsJson);
        row.setCiphertext(cipher.encrypt(value));
        row.setCipherVersion(CredentialCipher.CURRENT_VERSION);
        return row;
    }

    private SkillBuilderPublishService serviceWith(CredentialCipher withCipher) {
        return serviceWith(withCipher, new SkillBuilderProperties());
    }

    private SkillBuilderPublishService serviceWith(CredentialCipher withCipher, SkillBuilderProperties props) {
        return new SkillBuilderPublishService(sessions, mock(SkillBuilderSessionMapper.class),
                new SkillWorkspaceReader(storage, props), storage, aiSkillMapper, versionMapper,
                mock(AiSkillRegistryService.class), mock(ChatConversationService.class),
                new SkillSecretService(secretMapper, withCipher), props);
    }

    /** 改进 refund-flow（skill 5，当前 v3，上一版的 key 行是 previousRow）的会话，工作区里放这些文件。 */
    private void improvementSession(Map<String, String> workspaceFiles, SkillSecret previousRow) throws Exception {
        improvementSession(workspaceFiles, Map.of(), previousRow);
    }

    /** 同上；declaredSizes 是列举时「报出来」的大小（可以和内容对不上：列举之后、读取之前，沙箱可以把文件换大）。 */
    private void improvementSession(Map<String, String> workspaceFiles, Map<String, Long> declaredSizes, SkillSecret previousRow) throws Exception {
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
            infos.add(new ObjectInfo(name, declaredSizes.getOrDefault(e.getKey(), (long) b(e.getValue()).length), new Date()));
            StorageStubs.object(storage, name, b(e.getValue()));
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
                .hasMessageContaining("加密密钥没有配置").hasMessageContaining("CONNECTION_CREDENTIAL_KEY");

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
    // ---------------------------------------------------------------- 第 2 轮：上一版的值，发布时全部解密，所有出口都用

    private static final String PLAIN_MD = "---\nname: refund-flow\ndescription: 退款\n---\n正文\n";

    private void nothingWasWritten() throws Exception {
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(storage, never()).deletePrefix(anyString());
        verify(secretMapper, never()).insert(any(SkillSecret.class));
        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
    }

    @Test
    @DisplayName("★ 探针 B：secrets.json 有一条校验错误（企微 webhook 写法，paths 里带 ?key=<旧值>），回显的条目里不能有旧值——不论这一条合不合规，上一版的值都要先解密出来抹")
    void anEchoedPathDoesNotCarryTheOldValueWhenSecretsJsonIsInvalid() throws Exception {
        SkillSecret hook = previousRowOf("WECOM_HOOK", OLD, "[\"qyapi.weixin.qq.com\"]", null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", "---\nname: refund-flow\ndescription: 通知\n---\n用 {{WECOM_HOOK}}\n");
        workspace.put("secrets.json", "{\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"],"
                + "\"paths\":[\"/cgi-bin/webhook/send?key=" + OLD + "\"]}}");
        improvementSession(workspace, hook);

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("{{WECOM_HOOK}}")
                .hasMessageNotContaining(OLD).hasMessageNotContaining(OLD.substring(0, 12));
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 探针 C：methods 写错一个（GTE），同时 description 里「key: <旧值>」触发 YAML 报错：报错片段里连旧值的前半截都不能有")
    void aYamlErrorNextToAnotherSecretsErrorDoesNotCarryPartOfTheOldValue() throws Exception {
        SkillSecret hook = previousRowOf("WECOM_HOOK", OLD, "[\"qyapi.weixin.qq.com\"]", null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", "---\nname: refund-flow\ndescription: 通知时用 key: " + OLD + " 发消息\n---\n正文\n");
        workspace.put("secrets.json", "{\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"],\"methods\":[\"GTE\"]}}");
        improvementSession(workspace, hook);

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("YAML").hasMessageContaining("GTE")
                .hasMessageNotContaining(OLD).hasMessageNotContaining(OLD.substring(0, 10));
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ key 删了、旧值又被打进脚本：拒绝（文案点名 key 和文件，不带值）")
    void theOldValueOfADroppedKeyInAScriptIsRejected() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        workspace.put("secrets.json", "{\"OTHER_KEY\":{\"value\":\"another-new-value-1\",\"hosts\":[\"x.example.com\"]}}");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("scripts/call.py").hasMessageContaining("CRM_TOKEN").hasMessageContaining("上一版的值")
                .hasMessageNotContaining(TOKEN);
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ key 换了新值、旧值还留在脚本里：拒绝（新值替换掉了新值，旧值不能就这么留在 bundle 里）")
    void aNewValueDoesNotExcuseTheOldOneStillInAScript() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/call.py", "OLD = '" + TOKEN + "'\nNEW = 'sk-live-NEW-9876543210'\n");
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("scripts/call.py").hasMessageContaining("CRM_TOKEN").hasMessageContaining("上一版的值")
                .hasMessageNotContaining(TOKEN).hasMessageNotContaining("sk-live-NEW");
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 旧值出现在文件名里：拒绝，文案里的路径已抹值")
    void theOldValueInAFileNameIsRejected() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("scripts/" + TOKEN + ".py", "print(1)\n");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("CRM_TOKEN").hasMessageContaining("路径").hasMessageNotContaining(TOKEN);
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 上一版有 key、这一版每个 key 都给了新值、没配密钥：拒绝，而且是先因为解不开上一版的值而拒绝（不是等到加密新值时才发现）")
    void everyKeyGetsANewValueButTheOldOnesCannotBeDecrypted() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(new CredentialCipher()).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("加密密钥没有配置").hasMessageContaining("CONNECTION_CREDENTIAL_KEY");
        nothingWasWritten();
    }

    // ---------------------------------------------------------------- 第 3 轮：残留按原文判、名字里不能有值、解不开的 key 不卡死

    private static final String AWS_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String AWS_COMBINED = AWS_ID + ":wJalrXUtnFEMIK7MDENG";

    private static CredentialCipher cipherWithKey(byte fill) {
        CredentialCipher c = new CredentialCipher();
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        ReflectionTestUtils.setField(c, "keyBase64", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @Test
    @DisplayName("★ 复审探针 R1（真实流程）：上一版 AWS_CRED 是「id:secret」，这一版只留了 AK_ID（前一半），脚本里还是整串旧值——拒绝，什么都不写，文案里没有 secret 那一半")
    void aCurrentKeyCoveringHalfOfAnOldValueIsRejectedThroughTheRealFlow() throws Exception {
        SkillSecret awsCred = previousRowOf("AWS_CRED", AWS_COMBINED, "[\"s3.amazonaws.com\"]", null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("scripts/call.py", "CRED = '" + AWS_COMBINED + "'\n");
        workspace.put("secrets.json", "{\"AK_ID\":{\"value\":\"" + AWS_ID + "\",\"hosts\":[\"s3.amazonaws.com\"]}}");
        improvementSession(workspace, awsCred);

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("scripts/call.py").hasMessageContaining("AWS_CRED").hasMessageContaining("上一版的值")
                .hasMessageNotContaining("wJalrXUtnFEMIK7MDENG").hasMessageNotContaining(AWS_ID);
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 复审探针 R4（真实流程）：上一版的值（AWS access key id）写成了不带 value 的 key 名——以前处理器放行、到 SkillSecretService.prepare 才抛「key <值> 没有给值」"
            + "（没抹值，进 ERROR 日志）；现在在读 secrets.json 的时候就拒绝，文案只说第几个 key 的名字")
    void aPreviousValueWrittenAsAValueLessKeyNameIsRejectedBeforeAnythingPrintsIt() throws Exception {
        SkillSecret awsKeyId = previousRowOf("AWS_KEY_ID", AWS_ID, "[\"s3.amazonaws.com\"]", null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("secrets.json", "{\"" + AWS_ID + "\":{\"hosts\":[\"s3.amazonaws.com\"]}}");
        improvementSession(workspace, awsKeyId);

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 1 个 key 的名字").hasMessageNotContaining(AWS_ID).hasMessageNotContaining("AKIA");
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ R27：配了密钥、但上一版某个 key 的密文解不开（密钥换过），这一版给了新值——照常发布，新值用现在的密钥加密；"
            + "WARN（第 4 轮起由发布入口在处理器之后记）只带 skill id 和 key 名")
    void anUndecryptableKeyWithANewValueStillPublishes() throws Exception {
        SkillSecret row = previousRow(null, null);                     // 用 zeros 密钥加密的
        CredentialCipher rotated = cipherWithKey((byte) 1);            // 现在配的是另一把
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/call.py", "TOKEN = 'sk-live-NEW-9876543210'\n");
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, row);

        try (LogCapture logs = LogCapture.of(SkillBuilderPublishService.class)) {
            serviceWith(rotated).publish(1L);

            assertThat(logs.warnings()).singleElement().satisfies(m -> assertThat(m).contains("CRM_TOKEN").contains("5")
                    .doesNotContain(TOKEN).doesNotContain("sk-live").doesNotContain(row.getCiphertext()));
            assertThat(String.join("\n", logs.all())).doesNotContain("sk-live").doesNotContain(row.getCiphertext());
        }
        ArgumentCaptor<SkillSecret> saved = ArgumentCaptor.forClass(SkillSecret.class);
        verify(secretMapper).insert(saved.capture());
        assertThat(rotated.decrypt(saved.getValue().getCiphertext(), saved.getValue().getCipherVersion())).isEqualTo("sk-live-NEW-9876543210");
        assertThat(saved.getValue().getVersion()).isEqualTo(4);
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(storage, atLeastOnce()).putObject(names.capture(), bodies.capture(), anyString());
        for (int i = 0; i < names.getAllValues().size(); i++) {
            assertThat(new String(bodies.getAllValues().get(i), StandardCharsets.UTF_8)).as(names.getAllValues().get(i)).doesNotContain("sk-live-NEW");
        }
    }

    @Test
    @DisplayName("★ R27：同样解不开，这一版没写 value（种子原样）——拒绝，说清楚是解不开、没法沿用，要么重新写 value 要么去掉；什么都不写")
    void anUndecryptableKeyWithoutAValueIsRejectedWithAWayOut() throws Exception {
        SkillSecret row = previousRow(null, null);
        when(secretMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("secrets.json", seedFile());
        improvementSession(workspace, row);

        assertThatThrownBy(() -> serviceWith(cipherWithKey((byte) 1)).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("key CRM_TOKEN 上一版的值解不开").hasMessageContaining("没法沿用")
                .hasMessageContaining("重新写 value").hasMessageContaining("去掉 CRM_TOKEN");
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 第 4 轮 5：解不开、给了新值是正常的发布路径——整条流程里一条 ERROR 都没有（以前 CredentialCipher.decrypt 每个解不开的 key 都构造一个 "
            + "ServiceException、记一条 ERROR，按 ERROR 告警会误报），WARN 只有「哪些 key 解不开」那一条")
    void anUndecryptableKeyWithANewValueLogsNoError() throws Exception {
        SkillSecret row = previousRow(null, null);                     // 用 zeros 密钥加密的
        CredentialCipher rotated = cipherWithKey((byte) 1);            // 现在配的是另一把
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, row);

        try (LogCapture logs = LogCapture.everything()) {
            serviceWith(rotated).publish(1L);

            assertThat(logs.errors()).isEmpty();
            assertThat(logs.warnings()).singleElement().satisfies(m -> assertThat(m).contains("CRM_TOKEN").contains("5")
                    .doesNotContain("sk-live").doesNotContain(row.getCiphertext()));
        }
    }

    @Test
    @DisplayName("★ 第 4 轮 2（复审探针 C）：上一版解不开的 key 叫 CRM_TOKEN_PROD，这一版某个 key 的值正好是 CRM_TOKEN_PROD——处理器的名字检查拒绝发布；"
            + "「哪些 key 解不开」的 WARN 排在这道检查之后，所以哪一行日志里都没有这个值（以前 WARN 在解密时就打，早于名字检查）")
    void anUndecryptableKeyNamedLikeACurrentValueNeverReachesTheLog() throws Exception {
        SkillSecret oddName = previousRowOf("CRM_TOKEN_PROD", "an-old-secret-9999", "[\"api.customer.com\"]", null, null);   // 用 zeros 密钥加密的
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("secrets.json", "{\"NEW_KEY\":{\"value\":\"CRM_TOKEN_PROD\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, oddName);
        SkillBuilderPublishService rotated = serviceWith(cipherWithKey((byte) 1));   // 现在配的是另一把

        try (LogCapture logs = LogCapture.everything()) {
            assertThatThrownBy(() -> rotated.publish(1L)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("名字").hasMessageNotContaining("CRM_TOKEN_PROD");

            assertThat(logs.all()).noneMatch(m -> m.contains("CRM_TOKEN_PROD"));
        }
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 第 4 轮 2：那条 WARN 整句先过唯一的替换 helper（当前值 ∪ 上一版的值）——两个解不开的 key 的名字连起来（AAA_KEY,BBB_KEY）正好是这一版"
            + "某个 key 的值：处理器逐个名字查，查不出来，发布照常；WARN 里只有 {{名字}}，哪一行日志都没有这个值")
    void theWarningIsScrubbedAsAWholeLine() throws Exception {
        SkillSecret first = previousRowOf("AAA_KEY", "an-old-secret-1111", "[\"api.customer.com\"]", null, null);    // 都用 zeros 密钥加密
        SkillSecret second = previousRowOf("BBB_KEY", "an-old-secret-2222", "[\"api.customer.com\"]", null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("secrets.json", "{\"NEW_KEY\":{\"value\":\"AAA_KEY,BBB_KEY\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, first);
        when(secretMapper.selectList(any())).thenReturn(List.of(first, second));
        SkillBuilderPublishService rotated = serviceWith(cipherWithKey((byte) 1));   // 现在配的是另一把：两个都解不开

        try (LogCapture logs = LogCapture.everything()) {
            rotated.publish(1L);

            assertThat(logs.warnings()).singleElement().satisfies(m -> assertThat(m).contains("skillId=5").contains("{{NEW_KEY}}")
                    .doesNotContain("AAA_KEY,BBB_KEY"));
            assertThat(logs.all()).noneMatch(m -> m.contains("AAA_KEY,BBB_KEY"));
        }
    }

    @Test
    @DisplayName("★ 第 4 轮（续）R33：构建器发布同样在解析之后核对——description 用 YAML 的 \\x4e 拼出这一版 key 的值（原文里没有、替换不掉）："
            + "草稿校验不拦（只给属主看，R21 / R22），处理器拒绝，文案点名 description、不带值；什么都不写，哪一行日志都没有这个值")
    void aValueRebuiltByAYamlEscapeIsRejectedThroughTheRealFlow() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", "---\nname: refund-flow\ndescription: \"key sk-live-\\x4eEW-9876543210\"\n---\n带上 {{CRM_TOKEN}} 调接口\n");
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, previousRow(null, null));
        SkillBuilderPublishService svc = serviceWith(cipher);

        try (LogCapture logs = LogCapture.everything()) {
            assertThatThrownBy(() -> svc.publish(1L)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("description").hasMessageContaining("{{CRM_TOKEN}}")
                    .hasMessageNotContaining("sk-live").hasMessageNotContaining("9876543210");
            assertThat(logs.all()).noneMatch(m -> m.contains("sk-live-NEW") || m.contains("9876543210"));
        }
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：license 用 \\x4e 拼出这一版 key 的值（不入库的字段，转义原样会进 bundle 的 SKILL.md）——构建器发布被拒，"
            + "文案点名 license、不带值；什么都不写")
    void aValueRebuiltInLicenseIsRejectedThroughTheRealFlow() throws Exception {
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\nlicense: \"MIT sk-live-\\x4eEW-9876543210\"\n---\n带上 {{CRM_TOKEN}} 调接口\n");
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, previousRow(null, null));

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("license").hasMessageContaining("CRM_TOKEN")
                .hasMessageNotContaining("sk-live").hasMessageNotContaining("9876543210");
        nothingWasWritten();
    }

    // ---------------------------------------------------------------- 第 4 轮 1：发布时读文件失败、读文件超额

    /**
     * 这个文件草稿读取时读得到，发布那一次读取失败（两次读之间对象被删了、MinIO 抖了一下）。存储报的出错信息里带着完整的对象名——
     * 和 MinIO 真实的报错一样（{@code resource=/bucket/…}）。整个读（旧写法）也一样失败。
     */
    private void failsOnThePublishRead(String rel) throws Exception {
        String name = "ws/1/skill/refund-flow/" + rel;
        byte[] bytes = b("print(1)\n");
        java.io.IOException gone = new java.io.IOException("The specified key does not exist. resource=/skills-bucket/" + name);
        when(storage.readBytesUpTo(eq(name), anyLong()))
                .thenAnswer(inv -> java.util.Arrays.copyOf(bytes, (int) Math.min(bytes.length, (long) inv.getArgument(1) + 1)))
                .thenThrow(gone);
        when(storage.readBytes(name)).thenThrow(gone);
    }

    @Test
    @DisplayName("★ 第 4 轮 1（复审探针 D2）：文件名里带着这一版 key 的值，草稿读取读得到、发布那一次读取失败——异常文案和 ERROR 行里只有抹过值的相对路径"
            + "和异常类名，没有值，也没有存储报的出错信息（它带着完整的对象名 resource=/…/sk-live-…py）")
    void aFailedPublishReadNamesTheFileWithoutItsValue() throws Exception {
        String fresh = "sk-live-NEW-9876543210";
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/" + fresh + ".py", "print(1)\n");
        workspace.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"" + fresh + "\",\"hosts\":[\"api.customer.com\"]}}");
        improvementSession(workspace, previousRow(null, null));
        failsOnThePublishRead("scripts/" + fresh + ".py");
        SkillBuilderPublishService svc = serviceWith(cipher);

        try (LogCapture logs = LogCapture.everything()) {
            assertThatThrownBy(() -> svc.publish(1L)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("读取工作区文件失败 skill/refund-flow/scripts/{{CRM_TOKEN}}.py").hasMessageContaining("IOException")
                    .hasMessageNotContaining("sk-live").hasMessageNotContaining("resource=").hasMessageNotContaining("does not exist");

            assertThat(logs.errors()).singleElement().satisfies(m -> assertThat(m).contains("scripts/{{CRM_TOKEN}}.py")
                    .doesNotContain("sk-live").doesNotContain("resource="));
            assertThat(logs.all()).noneMatch(m -> m.contains("sk-live") || m.contains("resource="));
        }
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 第 4 轮 1：同上，文件名里带的是上一版的值（沿用，工作区里没有它）——发布时抹值的集合并上了上一版的值，文案里同样只有 {{名字}}")
    void aFailedPublishReadAlsoScrubsThePreviousValues() throws Exception {
        SkillSecret row = previousRow(null, null);
        when(secretMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", MD_USING_THE_KEY);
        workspace.put("scripts/" + TOKEN + ".py", "print(1)\n");
        workspace.put("secrets.json", seedFile());   // 不带值：沿用上一版
        improvementSession(workspace, row);
        failsOnThePublishRead("scripts/" + TOKEN + ".py");

        assertThatThrownBy(() -> serviceWith(cipher).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("scripts/{{CRM_TOKEN}}.py").hasMessageNotContaining("sk-live").hasMessageNotContaining("resource=");
        nothingWasWritten();
    }

    @Test
    @DisplayName("★ 第 4 轮 1：发布时读文件同样有界——列举时报的大小在额度内、读的时候文件已经被换大：每个文件最多读「剩余额度 + 1」个字节，超了拒绝发布"
            + "（固定文案，不带文件名）；读进内存的字节一共不超过额度 + 1，从不整个读")
    void thePublishReadIsBoundedByWhatIsLeftOfTheBudget() throws Exception {
        SkillBuilderProperties props = new SkillBuilderProperties();
        props.setPreviewFileMaxBytes(1024);
        props.setPublishMaxBytes(8192);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        workspace.put("references/a.md", "a".repeat(3000));
        workspace.put("references/b.md", "b".repeat(20000));
        improvementSession(workspace, Map.of("references/a.md", 100L, "references/b.md", 100L), previousRow(null, null));   // 列举时一共才两百多字节
        List<long[]> publishReads = new ArrayList<>();   // 发布那一轮的每一次读：{上限, 实际给出的字节数}（草稿读取的上限都是 1024）
        for (Map.Entry<String, String> e : workspace.entrySet()) {
            byte[] bytes = b(e.getValue());
            when(storage.readBytesUpTo(eq("ws/1/skill/refund-flow/" + e.getKey()), anyLong())).thenAnswer(inv -> {
                long max = inv.getArgument(1);
                byte[] given = java.util.Arrays.copyOf(bytes, (int) Math.min(bytes.length, max + 1));
                if (max != props.getPreviewFileMaxBytes()) publishReads.add(new long[]{max, given.length});
                return given;
            });
        }

        assertThatThrownBy(() -> serviceWith(cipher, props).publish(1L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("超过了发布的上限").hasMessageNotContaining("references/");

        assertThat(publishReads).isNotEmpty();
        assertThat(publishReads.stream().mapToLong(r -> r[1]).sum()).isLessThanOrEqualTo(props.getPublishMaxBytes() + 1);
        verify(storage, never()).readBytes(anyString());
        nothingWasWritten();
    }

    @Test
    @DisplayName("R27：解不开的 key 被去掉（文件里也不再用它）——照常发布，新版本里没有这个 key")
    void anUndecryptableKeyThatIsDroppedIsJustGone() throws Exception {
        SkillSecret row = previousRow(null, null);
        Map<String, String> workspace = new LinkedHashMap<>();
        workspace.put("SKILL.md", PLAIN_MD);
        improvementSession(workspace, row);

        serviceWith(cipherWithKey((byte) 1)).publish(1L);

        verify(secretMapper, never()).insert(any(SkillSecret.class));
        verify(storage, atLeastOnce()).putObject(eq("skills/5/4/SKILL.md"), any(byte[].class), anyString());
    }
}

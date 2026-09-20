package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.ParamSpec;
import com.jimeng.dataserver.ai.connector.spi.ParamType;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多敏感参数的连接器在<b>编辑</b>时的凭据合并。
 *
 * <h3>这个用例钉的是一个静默吞凭据的 bug</h3>
 * 原来的实现是<b>一个 boolean 管全部敏感参数</b>，而且是「或」的关系——任意一个留空就跳过整段
 * 写密文的代码。单敏感参数时「有一个留空」== 「全部留空」，行为恰好正确，所以这个 bug 一直躺着；
 * 两个就会出事：用户填了新密码、把 SSL 私钥留空 → 开关被私钥打开 → <b>新密码被静默丢弃</b>。
 *
 * <p><b>而第二道保险拦不住它</b>：保存前的 {@code probeOrThrow} 用的是没被改过的旧密文，
 * 拿旧密码去探测，探测成功、保存返回 200。用户以为密码换了，实际没换，全程零信号。
 *
 * <p>今天线上的两个连接器（MySQL / HTTP）都只声明一个敏感参数，所以这个 bug <b>打不到</b>。
 * 本用例用一个两敏感参数的假连接器把它暴露出来——否则写完也验证不了。
 * 前端的 {@code SecretField} 是逐字段独立的「已保存 / 更换中」状态，
 * 「只换其中一个」正是它鼓励的用法，所以这条路迟早会被走到。
 */
class ConnectorMultiSecretMergeTest {

    private ConnectionMapper connectionMapper;
    private ConnectorRegistry registry;
    private CredentialCipher cipher;
    private ConnectorService service;

    /** 记录最后一次真正交给 cipher 加密的明文——断言合并结果的唯一窗口。 */
    private ArgumentCaptor<String> encrypted;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        registry = mock(ConnectorRegistry.class);
        cipher = mock(CredentialCipher.class);
        ConnectorInstanceLoader loader = mock(ConnectorInstanceLoader.class);
        ConnectorProbeService probeService = mock(ConnectorProbeService.class);

        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenReturn("new-cipher-text");
        // 旧密文解开之后是这两条。合并必须以它打底。
        when(cipher.decrypt(anyString(), anyInt()))
                .thenReturn("{\"password\":\"old-pwd\",\"clientKey\":\"old-key\"}");

        Connector twoSecrets = mock(Connector.class);
        when(twoSecrets.kind()).thenReturn("FAKE2");
        when(twoSecrets.paramSpec()).thenReturn(ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true, null),
                ParamField.secret("password", "密码", true, null),
                ParamField.secret("clientKey", "客户端私钥", true, null)));
        when(registry.supports("FAKE2")).thenReturn(true);
        when(registry.require("FAKE2")).thenReturn(twoSecrets);
        when(registry.find("FAKE2")).thenReturn(Optional.of(twoSecrets));

        when(loader.load(any(Connection.class))).thenReturn(
                new ConnectorInstance(1L, "t1", "FAKE2", "fake", null, Map.of(), "pwd", "direct"));
        when(probeService.probe(any(ConnectorInstance.class))).thenReturn(
                new ConnectorProbeService.ProbeReport(true, null,
                        ReadOnlyVerdict.confirmed("权限拒绝"), Set.of(Capability.QUERY)));
        when(loader.readParams(any(Connection.class))).thenReturn(Map.of("host", "h"));

        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, loader, probeService,
                mock(CustomerDataSourceManager.class), cipher,
                mock(ConnectorSemanticService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConnectorAuditService.class));
        TenantContext.set("t1");
        encrypted = ArgumentCaptor.forClass(String.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 夹具

    private Connection existingRow() {
        Connection r = new Connection();
        r.setId(1L);
        r.setTenantId("t1");
        r.setKind("FAKE2");
        r.setName("fake");
        r.setStatus("ACTIVE");
        r.setTransport("direct");
        r.setCredentialCipher("old-cipher-text");
        r.setEncryptionVersion(1);
        when(connectionMapper.selectById(1L)).thenReturn(r);
        return r;
    }

    /** 编辑请求：host 必填，两个 secret 按需填。传 null 表示留空（= 沿用原值）。 */
    private ConnectorUpsert editReq(String password, String clientKey) {
        ConnectorUpsert r = new ConnectorUpsert();
        r.setName("fake");
        r.setKind("FAKE2");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("host", "h");
        if (password != null) p.put("password", password);
        if (clientKey != null) p.put("clientKey", clientKey);
        r.setParams(p);
        return r;
    }

    /** 解析最后一次加密的明文 JSON。 */
    private Map<?, ?> encryptedPayload() {
        verify(cipher).encrypt(encrypted.capture());
        try {
            return com.jimeng.common.core.utils.CommonUtil.getObjectMapper()
                    .readValue(encrypted.getValue(), Map.class);
        } catch (Exception e) {
            throw new AssertionError("加密的明文不是合法 JSON: " + encrypted.getValue(), e);
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("★ 只换密码、私钥留空：新密码必须落库，留空的私钥沿用旧值")
    void newPasswordSurvivesWhenOtherSecretLeftBlank() {
        existingRow();

        service.update(1L, editReq("NEW-pwd", null));

        Map<?, ?> payload = encryptedPayload();
        assertEquals("NEW-pwd", payload.get("password"),
                "填了的那个必须是新值——原来这里会被静默丢弃，用户以为改了其实没改");
        assertEquals("old-key", payload.get("clientKey"),
                "留空的那个必须沿用旧值——丢了等于一次编辑顺手删掉一个凭据");
    }

    @Test
    @DisplayName("只换私钥、密码留空：对称行为")
    void newClientKeySurvivesWhenPasswordLeftBlank() {
        existingRow();

        service.update(1L, editReq(null, "NEW-key"));

        Map<?, ?> payload = encryptedPayload();
        assertEquals("old-pwd", payload.get("password"));
        assertEquals("NEW-key", payload.get("clientKey"));
    }

    @Test
    @DisplayName("两个都换：不需要解旧密文，直接用新值")
    void bothProvidedDoesNotNeedOldCipher() {
        existingRow();

        service.update(1L, editReq("NEW-pwd", "NEW-key"));

        Map<?, ?> payload = encryptedPayload();
        assertEquals("NEW-pwd", payload.get("password"));
        assertEquals("NEW-key", payload.get("clientKey"));
    }

    @Test
    @DisplayName("两个都留空：整段不动，原密文原封不动，且不解密（这是「留空 = 沿用」的落点）")
    void bothBlankKeepsCipherUntouched() {
        Connection row = existingRow();

        service.update(1L, editReq(null, null));

        verify(cipher, never()).encrypt(anyString());
        verify(cipher, never()).decrypt(anyString(), anyInt());
        assertEquals("old-cipher-text", row.getCredentialCipher(), "原密文必须一个字节都没变");
    }

    @Test
    @DisplayName("空白串等同于留空——前端清空输入框传来的是 \"\"，不是缺字段")
    void blankStringCountsAsLeftBlank() {
        existingRow();

        service.update(1L, editReq("NEW-pwd", "   "));

        Map<?, ?> payload = encryptedPayload();
        assertEquals("NEW-pwd", payload.get("password"));
        assertEquals("old-key", payload.get("clientKey"), "纯空白必须按留空处理，不能把空格存成私钥");
    }

    @Test
    @DisplayName("单敏感参数重填时不去解旧密文——白解一次只会凭空多一条「密钥轮换后连编辑都做不了」的失败路径")
    void singleSecretRewriteDoesNotDecrypt() {
        Connector one = mock(Connector.class);
        when(one.kind()).thenReturn("FAKE1");
        when(one.paramSpec()).thenReturn(ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true, null),
                ParamField.secret("password", "密码", true, null)));
        when(registry.require("FAKE1")).thenReturn(one);
        when(registry.find("FAKE1")).thenReturn(Optional.of(one));

        Connection r = existingRow();
        r.setKind("FAKE1");

        ConnectorUpsert req = new ConnectorUpsert();
        req.setName("fake");
        req.setKind("FAKE1");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("host", "h");
        p.put("password", "NEW-pwd");
        req.setParams(p);

        service.update(1L, req);

        verify(cipher, never()).decrypt(anyString(), anyInt());
        verify(cipher).encrypt(encrypted.capture());
        // 单敏感参数存【裸值】，不套 JSON——与 buildSecretPayload 对称，也是 HTTP 存量行能解出来的前提。
        assertEquals("NEW-pwd", encrypted.getValue());
        assertTrue(!encrypted.getValue().startsWith("{"), "单敏感参数绝不能套成 JSON");
    }
}

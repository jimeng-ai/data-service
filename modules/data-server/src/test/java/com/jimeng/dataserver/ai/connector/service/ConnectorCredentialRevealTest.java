package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.dataserver.ai.connector.spi.Connector;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭据取回（管理台「查看密码」）。
 *
 * <p>这是全系统<b>唯一</b>把凭据明文送出进程的出口，它的每条性质都是「错了不会报错」的那种，
 * 所以逐条钉住：
 *
 * <ul>
 *   <li><b>审计写不进去就不给明文。</b>与 {@code ConnectorAuditService} 的默认信条相反，
 *       是刻意的——凭据离开平台之后，写闸 / 出库档位 / 审计表全都管不着，
 *       那一行审计就是<b>唯一</b>的控制。一次没记上的取回比一次失败的取回坏得多。</li>
 *   <li><b>拆分规则与 {@code buildSecretPayload} 逐字对称。</b>单个敏感参数存裸值、
 *       多个才是 JSON。分叉的表现是取回一段 JSON 原文当密码——不报错，只是填回去连不上。</li>
 *   <li><b>失败也要留痕。</b>一串失败的取回尝试本身就是信号。</li>
 *   <li><b>审计文案里不能出现明文。</b>那一列会被展示在管理台的使用记录里。</li>
 * </ul>
 */
class ConnectorCredentialRevealTest {

    private ConnectionMapper connectionMapper;
    private ConnectorRegistry registry;
    private CredentialCipher cipher;
    private ConnectorAuditService auditService;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        registry = mock(ConnectorRegistry.class);
        cipher = mock(CredentialCipher.class);
        auditService = mock(ConnectorAuditService.class);

        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, mock(ConnectorInstanceLoader.class),
                mock(ConnectorProbeService.class), mock(CustomerDataSourceManager.class), cipher,
                mock(ConnectorSemanticService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                auditService);
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 夹具

    /** 一条已存凭据的连接行。 */
    private Connection row() {
        Connection r = new Connection();
        r.setId(7L);
        r.setTenantId("t1");
        r.setKind("MYSQL");
        r.setName("shop-db");
        r.setCredentialCipher("cipher-text");
        r.setEncryptionVersion(1);
        when(connectionMapper.selectById(7L)).thenReturn(r);
        return r;
    }

    /** 只有一个敏感参数的类型（今天的 MySQL / HTTP 都是这一种）。 */
    private void singleSecretKind() {
        Connector c = mock(Connector.class);
        when(c.paramSpec()).thenReturn(ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true, null),
                ParamField.secret("password", "密码", true, null)));
        when(registry.require("MYSQL")).thenReturn(c);
    }

    /** 两个敏感参数的类型：今天没有，但 ParamSpec 允许，而它正是两侧会分叉的那一种。 */
    private void twoSecretKind() {
        Connector c = mock(Connector.class);
        when(c.paramSpec()).thenReturn(ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true, null),
                ParamField.secret("password", "密码", true, null),
                ParamField.secret("clientKey", "客户端私钥", false, null)));
        when(registry.require("MYSQL")).thenReturn(c);
    }

    // ---------------------------------------------------------------- 正常取回

    @Test
    @DisplayName("单个敏感参数：密文存的是裸值，按参数名原样返回")
    void singleSecretReturnsRawValue() {
        row();
        singleSecretKind();
        when(cipher.decrypt("cipher-text", 1)).thenReturn("s3cr3t");

        assertEquals(Map.of("password", "s3cr3t"), service.revealCredential(7L));
    }

    @Test
    @DisplayName("多个敏感参数：密文是 JSON，按参数名拆回去——与 buildSecretPayload 对称")
    void multipleSecretsAreSplitFromJson() {
        row();
        twoSecretKind();
        when(cipher.decrypt("cipher-text", 1))
                .thenReturn("{\"password\":\"s3cr3t\",\"clientKey\":\"KEY\"}");

        Map<String, String> out = service.revealCredential(7L);

        assertEquals("s3cr3t", out.get("password"));
        assertEquals("KEY", out.get("clientKey"));
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("取回成功要写一条 admin.credential_reveal，且文案里不出现明文")
    void successIsAudited() {
        row();
        singleSecretKind();
        when(cipher.decrypt("cipher-text", 1)).thenReturn("s3cr3t");

        service.revealCredential(7L);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAdminAction(eq(7L), eq("t1"), eq("shop-db"),
                eq(ConnectorAuditService.OP_CREDENTIAL_REVEAL), summary.capture(), eq(true), eq(null));
        // ★ error_detail 那一列会展示在管理台的使用记录里。凭据进了它，等于换个地方泄露一次。
        assertTrue(summary.getValue().contains("password"), "应说清取回了哪几个参数");
        assertTrue(!summary.getValue().contains("s3cr3t"), "审计文案里绝不能出现明文");
    }

    // ---------------------------------------------------------------- 审计失败必须挡住明文

    @Test
    @DisplayName("★ 审计写不进去就不给明文——与「审计失败不拖垮主流程」的默认信条相反，是刻意的")
    void auditFailureBlocksTheReveal() {
        row();
        singleSecretKind();
        when(cipher.decrypt("cipher-text", 1)).thenReturn("s3cr3t");
        doThrow(new RuntimeException("audit table down"))
                .when(auditService).recordAdminAction(any(), any(), any(),
                        eq(ConnectorAuditService.OP_CREDENTIAL_REVEAL), anyString(), eq(true), any());

        assertThrows(RuntimeException.class, () -> service.revealCredential(7L));
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    @DisplayName("密文解不开：写一条失败审计再抛，不吞")
    void decryptFailureIsAuditedThenRethrown() {
        row();
        singleSecretKind();
        when(cipher.decrypt("cipher-text", 1))
                .thenThrow(new ServiceException(
                        com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "连接凭据解密失败"));

        assertThrows(ServiceException.class, () -> service.revealCredential(7L));

        verify(auditService).recordAdminAction(eq(7L), eq("t1"), eq("shop-db"),
                eq(ConnectorAuditService.OP_CREDENTIAL_REVEAL), anyString(), eq(false),
                eq(ConnectorErrorCode.CONFIG_ERROR));
    }

    @Test
    @DisplayName("多敏感参数的密文不是合法 JSON：宁可失败，也不能把整段原文当成某一个参数的值")
    void malformedMultiSecretPayloadFailsInsteadOfGuessing() {
        row();
        twoSecretKind();
        when(cipher.decrypt("cipher-text", 1)).thenReturn("not-json");

        assertThrows(ServiceException.class, () -> service.revealCredential(7L));

        verify(auditService).recordAdminAction(eq(7L), eq("t1"), eq("shop-db"),
                eq(ConnectorAuditService.OP_CREDENTIAL_REVEAL), anyString(), eq(false),
                eq(ConnectorErrorCode.CONFIG_ERROR));
    }

    @Test
    @DisplayName("这条连接没存凭据：报 404，且不写审计——没有钥匙可拿，就没有「谁拿走了钥匙」")
    void missingCredentialIsNotAudited() {
        Connection r = row();
        r.setCredentialCipher(null);
        singleSecretKind();

        assertThrows(ServiceException.class, () -> service.revealCredential(7L));

        verify(auditService, never()).recordAdminAction(any(), any(), any(), anyString(),
                anyString(), anyBoolean(), any());
        // 也不该去碰密钥：没有密文可解。
        verify(cipher, never()).decrypt(anyString(), anyInt());
    }

    @Test
    @DisplayName("该类型没有敏感参数：返回空表，不解密也不写审计")
    void kindWithoutSecretsReturnsEmpty() {
        row();
        Connector c = mock(Connector.class);
        when(c.paramSpec()).thenReturn(ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true, null)));
        when(registry.require("MYSQL")).thenReturn(c);

        assertTrue(service.revealCredential(7L).isEmpty());

        verify(cipher, never()).decrypt(anyString(), anyInt());
        verify(auditService, never()).recordAdminAction(any(), any(), any(), anyString(),
                anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("连接不存在：报错，不解密也不写审计")
    void unknownConnectionFails() {
        when(connectionMapper.selectById(99L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.revealCredential(99L));

        verify(cipher, never()).decrypt(anyString(), anyInt());
    }
}

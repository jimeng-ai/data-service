package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.impl.mysql.MySqlConnector;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试连（dry-run）。
 *
 * <p>它有两条不能破的不变式，都不是「功能对不对」而是「会不会伤到别的东西」：
 * 用完必须销毁连接池，以及绝不能用真实 id。
 */
class ConnectorServiceDryRunTest {

    private ConnectionMapper connectionMapper;
    private CustomerDataSourceManager dataSourceManager;
    private ConnectorProbeService probeService;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private CredentialCipher cipher;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        dataSourceManager = mock(CustomerDataSourceManager.class);
        probeService = mock(ConnectorProbeService.class);
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        cipher = mock(CredentialCipher.class);
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenReturn("cipher-text");

        // 用真实的 MySqlConnector 拿 paramSpec（参数校验是 dryRun 的第一道），其余依赖 mock 掉。
        Connector mysql = new MySqlConnector(dataSourceManager, null, null, null);
        when(registry.supports("MYSQL")).thenReturn(true);
        when(registry.require("MYSQL")).thenReturn(mysql);
        when(registry.find("MYSQL")).thenReturn(java.util.Optional.of(mysql));

        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, loader, probeService, dataSourceManager, cipher,
                mock(ConnectorSemanticService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConnectorUpsert req() {
        ConnectorUpsert r = new ConnectorUpsert();
        r.setName("probe-test");
        r.setKind("MYSQL");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("host", "db.example.com");
        p.put("port", 3306);
        p.put("database", "shop");
        p.put("username", "ro");
        p.put("password", "pwd");
        r.setParams(p);
        return r;
    }

    private void givenProbe(ConnectorProbeService.ProbeReport report) {
        when(loader.load(any(Connection.class))).thenReturn(
                new ConnectorInstance(-1L, "t1", "MYSQL", "probe-test", null, Map.of(), "pwd", "direct"));
        when(probeService.probe(any(ConnectorInstance.class))).thenReturn(report);
    }

    // ================================================================

    /**
     * ★ 池不在事务里，也没人会替它回收。不显式销毁的话，每点一次「测试连接」
     * 就在客户库上留一个池，直到空闲回收（默认 10 分钟）才消失。
     */
    @Test
    @DisplayName("试连结束必须销毁连接池——成功时")
    void 成功时销毁池() {
        givenProbe(new ConnectorProbeService.ProbeReport(true, null,
                ReadOnlyVerdict.confirmed("权限拒绝"), Set.of(Capability.QUERY)));
        ProbeOutcome out = service.dryRun(req(), null);
        assertTrue(out.isOk());
        verify(dataSourceManager, times(1)).invalidate(any());
    }

    @Test
    @DisplayName("试连结束必须销毁连接池——探测失败时")
    void 失败时也销毁池() {
        givenProbe(new ConnectorProbeService.ProbeReport(false, "连不上", null, Set.of()));
        service.dryRun(req(), null);
        verify(dataSourceManager, times(1)).invalidate(any());
    }

    @Test
    @DisplayName("试连结束必须销毁连接池——参数非法抛异常时")
    void 抛异常时也销毁池() {
        when(loader.load(any(Connection.class)))
                .thenThrow(ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "参数坏了"));
        ProbeOutcome out = service.dryRun(req(), null);
        assertFalse(out.isOk());
        // 参数问题本来就是试连要回答的，不该抛成 500
        assertEquals("参数坏了", out.getFailureReason());
        verify(dataSourceManager, times(1)).invalidate(any());
    }

    /**
     * ★ 用真实 id 有两个致命后果：新建时压根没有 id（NPE，踩过一次）；
     * 编辑时会拿【未保存的】参数替换掉正在服务的连接池，一次失败的试连就能打断线上查询。
     */
    @Test
    @DisplayName("池 id 必须是合成的负数，绝不用真实 id")
    void 池id是合成负数() {
        givenProbe(new ConnectorProbeService.ProbeReport(true, null,
                ReadOnlyVerdict.confirmed("ok"), Set.of(Capability.QUERY)));
        service.dryRun(req(), null);

        ArgumentCaptor<Long> cap = ArgumentCaptor.forClass(Long.class);
        verify(dataSourceManager).invalidate(cap.capture());
        // 雪花 id 恒为正，负数保证永不冲突。
        assertTrue(cap.getValue() < 0, "试连用的池 id 必须是负数，实际 " + cap.getValue());
    }

    @Test
    @DisplayName("并发试连之间不共用池 id")
    void 每次试连的池id不同() {
        givenProbe(new ConnectorProbeService.ProbeReport(true, null,
                ReadOnlyVerdict.confirmed("ok"), Set.of(Capability.QUERY)));
        service.dryRun(req(), null);
        service.dryRun(req(), null);

        ArgumentCaptor<Long> cap = ArgumentCaptor.forClass(Long.class);
        verify(dataSourceManager, times(2)).invalidate(cap.capture());
        assertNotEquals(cap.getAllValues().get(0), cap.getAllValues().get(1));
    }

    /** 只读三态要原样带出去：可写要换账号、判不出要查权限，两者动作不同。 */
    @Test
    @DisplayName("只读判定三态原样带出，不压成布尔")
    void 只读三态不被压平() {
        givenProbe(new ConnectorProbeService.ProbeReport(false, "这个账号具备写权限",
                ReadOnlyVerdict.writable("能执行 UPDATE"), Set.of()));
        ProbeOutcome writable = service.dryRun(req(), null);
        assertFalse(writable.isReadonlyVerified());
        assertFalse(writable.isReadonlyUndetermined());

        givenProbe(new ConnectorProbeService.ProbeReport(false, "无法确认",
                ReadOnlyVerdict.unknown("错误码未知"), Set.of()));
        ProbeOutcome unknown = service.dryRun(req(), null);
        assertFalse(unknown.isReadonlyVerified());
        assertTrue(unknown.isReadonlyUndetermined());
    }

    @Test
    @DisplayName("参数校验失败不去连库")
    void 参数非法直接拒() {
        ConnectorUpsert bad = req();
        bad.getParams().remove("host");
        try {
            service.dryRun(bad, null);
        } catch (RuntimeException ignored) {
            // 期望抛业务异常
        }
        verify(probeService, times(0)).probe(any());
    }
}

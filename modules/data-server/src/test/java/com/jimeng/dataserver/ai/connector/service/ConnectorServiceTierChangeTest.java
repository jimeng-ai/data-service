package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
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
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据出库档位变化之后，事情不在 connection 这一行上结束（条目 9 / 契约 K-5）。
 *
 * <p>两个方向都是静默的：降档只改一个字符串，客户的真实取值继续躺在我们库里；升档不派发验证，
 * 在读不到取值时验过的多态外键永远等不到按判别值重探，界面上没有任何提示让人去点。
 */
class ConnectorServiceTierChangeTest {

    private ConnectionMapper connectionMapper;
    private ConnectorProbeService probeService;
    private ConnectorSemanticService semanticService;
    private ConnectorSemanticDeriveService derive;
    private PlatformTransactionManager txManager;
    private TransactionStatus txStatus;
    private ConnectorService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        CustomerDataSourceManager dataSourceManager = mock(CustomerDataSourceManager.class);
        probeService = mock(ConnectorProbeService.class);
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        ConnectorInstanceLoader loader = mock(ConnectorInstanceLoader.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenReturn("cipher-text");

        Connector mysql = new MySqlConnector(dataSourceManager, null, null, null);
        when(registry.supports("MYSQL")).thenReturn(true);
        when(registry.require("MYSQL")).thenReturn(mysql);
        when(registry.find("MYSQL")).thenReturn(Optional.of(mysql));
        when(loader.load(any(Connection.class))).thenReturn(
                new ConnectorInstance(1L, "t1", "MYSQL", "shop-db", null, Map.of(), "pwd", "direct"));
        when(probeService.probe(any(ConnectorInstance.class))).thenReturn(
                new ConnectorProbeService.ProbeReport(true, null,
                        ReadOnlyVerdict.confirmed("权限拒绝"), Set.of(Capability.QUERY)));
        when(loader.readParams(any(Connection.class))).thenReturn(Map.of("host", "db.example.com"));

        semanticService = mock(ConnectorSemanticService.class);
        derive = mock(ConnectorSemanticDeriveService.class);
        ObjectProvider<ConnectorSemanticDeriveService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(derive);
        txManager = mock(PlatformTransactionManager.class);
        txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, loader, probeService, dataSourceManager, cipher,
                semanticService, txManager, provider,
                mock(org.springframework.beans.factory.ObjectProvider.class));
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void existing(String tier) {
        Connection row = new Connection();
        row.setId(1L);
        row.setTenantId("t1");
        row.setKind("MYSQL");
        row.setName("shop-db");
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        row.setSemanticDataTier(tier);
        row.setCredentialCipher("cipher-text");
        when(connectionMapper.selectById(1L)).thenReturn(row);
    }

    private static ConnectorUpsert req(String tier) {
        ConnectorUpsert r = new ConnectorUpsert();
        r.setName("shop-db");
        r.setKind("MYSQL");
        r.setSemanticDataTier(tier);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("host", "db.example.com");
        p.put("port", 3306);
        p.put("database", "shop");
        p.put("username", "ro");
        p.put("password", "pwd");
        r.setParams(p);
        return r;
    }

    /** ★ 需求里点名的：客户收回第 3 档，他的真实取值不能留在我们库里。 */
    @Test
    @DisplayName("★ 从第 3 档降下来：事务里删一遍，提交之后再删一遍（接住事务期间写回的采集结果）")
    void downgradePurgesInsideTransactionAndAgainAfterCommit() {
        existing("SAMPLE_VALUES");

        service.update(1L, req("DERIVED_STATS"));

        InOrder o = inOrder(txManager, semanticService);
        o.verify(txManager).getTransaction(any());
        o.verify(semanticService).purgeSampleValues(1L);
        o.verify(txManager).commit(txStatus);
        o.verify(semanticService).purgeSampleValues(1L);
        verify(derive, never()).validateAsync(any());
    }

    /** 与「省略档位 = 降回默认档」同一条规则：前端漏回填，同样要删。 */
    @Test
    @DisplayName("省略档位（落到默认档）同样算降档、同样删")
    void omittedTierIsADowngradeToo() {
        existing("SAMPLE_VALUES");

        service.update(1L, req(null));

        verify(semanticService, times(2)).purgeSampleValues(1L);
    }

    @Test
    @DisplayName("★ 删不掉就让这次编辑失败并回滚：不存在「界面上已降档、库里取值还在」的中间态")
    void purgeFailureFailsTheEdit() {
        existing("SAMPLE_VALUES");
        doThrow(new IllegalStateException("库抖了")).when(semanticService).purgeSampleValues(1L);

        assertThrows(IllegalStateException.class, () -> service.update(1L, req("DERIVED_STATS")));

        verify(txManager).rollback(txStatus);
        verify(txManager, never()).commit(any());
        verify(derive, never()).validateAsync(any());
    }

    /** ★ 需求里点名的：升到第 3 档，多态外键要按判别值重探，不能等人去点。 */
    @Test
    @DisplayName("★ 调到第 3 档：提交之后派发一轮不限范围的采样验证，不删任何东西")
    void upgradeDispatchesValidationAfterCommit() {
        existing("DERIVED_STATS");

        service.update(1L, req("SAMPLE_VALUES"));

        InOrder o = inOrder(txManager, derive);
        o.verify(txManager).commit(txStatus);
        o.verify(derive).validateAsync(1L);
        verify(semanticService, never()).purgeSampleValues(any());
    }

    @Test
    @DisplayName("探测没过（编辑失败）：不派发")
    void failedEditDoesNotDispatch() {
        existing("DERIVED_STATS");
        when(probeService.probe(any(ConnectorInstance.class))).thenReturn(
                new ConnectorProbeService.ProbeReport(false, "连不上", null, Set.of()));

        assertThrows(ServiceException.class, () -> service.update(1L, req("SAMPLE_VALUES")));

        verify(derive, never()).validateAsync(any());
    }

    @Test
    @DisplayName("派发失败不让一次成功的编辑报错")
    void dispatchFailureIsQuiet() {
        existing("DERIVED_STATS");
        doThrow(new IllegalStateException("队列满")).when(derive).validateAsync(1L);

        ConnectorView v = service.update(1L, req("SAMPLE_VALUES"));

        assertNotNull(v);
        assertEquals("SAMPLE_VALUES", v.getSemanticDataTier());
    }

    @Test
    @DisplayName("一直在第 3 档：不删、不派发")
    void stayingOnSampleValuesDoesNothingExtra() {
        existing("SAMPLE_VALUES");

        service.update(1L, req("SAMPLE_VALUES"));

        verify(semanticService, never()).purgeSampleValues(any());
        verify(derive, never()).validateAsync(any());
    }

    @Test
    @DisplayName("一直不在第 3 档：事务里顺手清一遍残留，提交后不重复、不派发")
    void stayingBelowSampleValuesSweepsOnce() {
        existing("DERIVED_STATS");

        service.update(1L, req("METADATA_ONLY"));

        verify(semanticService, times(1)).purgeSampleValues(1L);
        verify(derive, never()).validateAsync(any());
    }
}

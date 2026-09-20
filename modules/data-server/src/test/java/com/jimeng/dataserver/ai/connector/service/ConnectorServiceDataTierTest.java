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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据出库档位在录入 / 编辑 / 视图这条链路上的行为。
 *
 * <p>{@link SemanticDataTierTest} 锁的是枚举自己的兜底方向；这里锁的是<b>它在接口上的语义</b>，
 * 三条都是「错了不会报错」的那种：
 *
 * <ul>
 *   <li><b>不填 = 默认档（第 2 档），不是「保持原样」。</b>于是编辑一条已开第 3 档的连接时漏传
 *       这个字段，它会被<b>降回</b>第 2 档。这是刻意选的那一半：降错了有人来问（吵闹、能发现），
 *       留错了是真实取值继续出库而没人再决定过一次（安静、发现不了）。</li>
 *   <li><b>第 3 档只能由显式取值到达。</b>不能由默认、由空值、由省略、由脏值到达。</li>
 *   <li><b>入参填错要当场 400，不能悄悄降档。</b>库里的脏值必须兜底（没人可问），
 *       接口入参必须报错（有人可问）。混成一种就会出现「界面显示第 3 档、库里存的是第 1 档」。</li>
 * </ul>
 */
class ConnectorServiceDataTierTest {

    private ConnectionMapper connectionMapper;
    private CustomerDataSourceManager dataSourceManager;
    private ConnectorProbeService probeService;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        dataSourceManager = mock(CustomerDataSourceManager.class);
        probeService = mock(ConnectorProbeService.class);
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenReturn("cipher-text");

        // 用真实的 MySqlConnector 拿 paramSpec（参数校验是录入的第一道），其余依赖 mock 掉。
        Connector mysql = new MySqlConnector(dataSourceManager, null, null, null);
        when(registry.supports("MYSQL")).thenReturn(true);
        when(registry.require("MYSQL")).thenReturn(mysql);
        when(registry.find("MYSQL")).thenReturn(Optional.of(mysql));

        // 探测恒通过：本用例关心的是档位怎么落库，不是探测。
        when(loader.load(any(Connection.class))).thenReturn(
                new ConnectorInstance(1L, "t1", "MYSQL", "shop-db", null, Map.of(), "pwd", "direct"));
        when(probeService.probe(any(ConnectorInstance.class))).thenReturn(
                new ConnectorProbeService.ProbeReport(true, null,
                        ReadOnlyVerdict.confirmed("权限拒绝"), Set.of(Capability.QUERY)));
        // toView 会读参数；不 mock 会 NPE 在一个与本用例无关的地方。
        when(loader.readParams(any(Connection.class))).thenReturn(Map.of("host", "db.example.com"));

        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, loader, probeService, dataSourceManager, cipher,
                mock(ConnectorSemanticService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService.class));
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConnectorUpsert req() {
        ConnectorUpsert r = new ConnectorUpsert();
        r.setName("shop-db");
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

    /** 编辑态的原行。 */
    private Connection existingRow(String tier) {
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
        return row;
    }

    // ================================================================ 新建

    @Nested
    @DisplayName("新建")
    class OnCreate {

        @Test
        @DisplayName("不填档位 = 默认档（第 2 档），不是最严档")
        void 不填落到默认档() {
            Connection row = new Connection();
            when(connectionMapper.insert(any(Connection.class))).thenAnswer(inv -> {
                // insert 时行上的值就要是对的：后台推导线程读的是库里的值，不是 view。
                row.setSemanticDataTier(((Connection) inv.getArgument(0)).getSemanticDataTier());
                return 1;
            });

            ConnectorView v = service.create(req());

            assertEquals("DERIVED_STATS", row.getSemanticDataTier(),
                    "落库的必须是默认档：压到第 1 档会静默把关系推断精确率打对折");
            assertEquals("DERIVED_STATS", v.getSemanticDataTier());
        }

        @Test
        @DisplayName("第 3 档必须显式写出来才拿得到")
        void 显式第3档才落第3档() {
            ConnectorUpsert r = req();
            r.setSemanticDataTier("SAMPLE_VALUES");
            service.create(r);
            assertEquals("SAMPLE_VALUES", capturedInsert().getSemanticDataTier());
        }

        @Test
        @DisplayName("大小写与空白不影响显式开启")
        void 大小写混写也能显式开启() {
            ConnectorUpsert r = req();
            r.setSemanticDataTier("  sample_values  ");
            service.create(r);
            assertEquals("SAMPLE_VALUES", capturedInsert().getSemanticDataTier());
        }

        /**
         * ★ 填错要当场报错，不能悄悄降档。
         *
         * <p>把拼错的档位按 parse 的兜底降一档存下去，界面上显示的还是超管刚选的那个——
         * 一次不报错的配置失效。这类静默降级本仓库反复吃过亏。
         */
        @Test
        @DisplayName("认不出来的档位当场 400，且不落库")
        void 脏档位当场报错() {
            for (String bad : new String[]{"TIER_3", "3", "SAMPLE_VALUE", "FULL", "样本值"}) {
                ConnectorUpsert r = req();
                r.setSemanticDataTier(bad);
                ServiceException e = assertThrows(ServiceException.class, () -> service.create(r),
                        "这个值不该被接受：" + bad);
                assertTrue(e.getMessage().contains("semanticDataTier"),
                        "报错要说清是哪个字段错了，否则超管只能猜：" + e.getMessage());
            }
            verify(connectionMapper, never()).insert(any(Connection.class));
        }

        private Connection capturedInsert() {
            org.mockito.ArgumentCaptor<Connection> cap =
                    org.mockito.ArgumentCaptor.forClass(Connection.class);
            verify(connectionMapper).insert(cap.capture());
            return cap.getValue();
        }
    }

    // ================================================================ 编辑

    @Nested
    @DisplayName("编辑")
    class OnUpdate {

        /**
         * ★ 这一条是本切片最容易被将来「修好」的行为，所以把理由钉在这里。
         *
         * <p>PUT 是整体覆盖，不是局部打补丁。让「省略」等于「沿用」，会造出一个没人审计得到的
         * 粘性状态：此后每一次改显示名的保存都在默默给第 3 档续期，事后谁也说不清当初是谁开的。
         * 与 writePolicy 留空 = FORBIDDEN 是同一条规则。
         *
         * <p>代价也认下来：前端编辑表单必须把详情接口返回的 semanticDataTier 原样回填，
         * 漏了就会把用户没动过的档位改掉。这个代价是吵闹的、能被发现的；反过来那个不是。
         */
        @Test
        @DisplayName("省略档位 = 降回默认档，不是保持原样")
        void 省略档位会把第3档降回第2档() {
            Connection row = existingRow("SAMPLE_VALUES");

            ConnectorUpsert r = req();
            r.setSemanticDataTier(null);   // 前端只想改个显示名，没带档位
            r.setDisplayName("商城主库");

            ConnectorView v = service.update(1L, r);

            assertEquals("DERIVED_STATS", row.getSemanticDataTier(),
                    "省略必须落到默认档：让省略等于沿用，第 3 档就成了一个没人再决定过的粘性状态");
            assertEquals("DERIVED_STATS", v.getSemanticDataTier());
            assertFalse(SemanticDataTier.parse(row.getSemanticDataTier()).allowsSampleValues());
        }

        @Test
        @DisplayName("显式传第 3 档才保得住第 3 档")
        void 显式回填才保住第3档() {
            Connection row = existingRow("SAMPLE_VALUES");
            ConnectorUpsert r = req();
            r.setSemanticDataTier("SAMPLE_VALUES");
            service.update(1L, r);
            assertEquals("SAMPLE_VALUES", row.getSemanticDataTier());
        }

        @Test
        @DisplayName("可以显式降到第 1 档")
        void 可以显式降到最严档() {
            Connection row = existingRow("DERIVED_STATS");
            ConnectorUpsert r = req();
            r.setSemanticDataTier("METADATA_ONLY");
            service.update(1L, r);
            assertEquals("METADATA_ONLY", row.getSemanticDataTier());
            assertFalse(SemanticDataTier.parse(row.getSemanticDataTier()).allowsDerivedStats());
        }

        @Test
        @DisplayName("脏档位当场 400，且不写库、不动原值")
        void 脏档位不落库() {
            Connection row = existingRow("METADATA_ONLY");
            ConnectorUpsert r = req();
            r.setSemanticDataTier("TIER_3");

            assertThrows(ServiceException.class, () -> service.update(1L, r));

            assertEquals("METADATA_ONLY", row.getSemanticDataTier(), "失败的编辑不该改动原值");
            verify(connectionMapper, never()).updateById(any(Connection.class));
        }
    }

    // ================================================================ 读许可 / 视图

    @Nested
    @DisplayName("dataTier：问许可的唯一入口")
    class AskPermission {

        /** 存量行（加列之前建的）这一格是 NULL。NULL = 没选过，不是选了最严的。 */
        @Test
        @DisplayName("NULL 的存量行拿到默认档，派生统计仍然允许")
        void 存量行NULL按默认档() {
            existingRow(null);
            SemanticDataTier t = service.dataTier(1L);
            assertEquals(SemanticDataTier.DERIVED_STATS, t);
            assertTrue(t.allowsDerivedStats(), "存量连接必须还能算包含率，否则升级一次就把 S3 全关了");
            assertFalse(t.allowsSampleValues());
        }

        /** 库里被手工改坏、或被更新版本写了个本版本不认识的值：未知一律当最严。 */
        @Test
        @DisplayName("库里认不出来的值拿到最严档")
        void 脏值按最严档() {
            existingRow("TIER_3");
            SemanticDataTier t = service.dataTier(1L);
            assertEquals(SemanticDataTier.METADATA_ONLY, t);
            assertFalse(t.allowsDerivedStats());
            assertFalse(t.allowsSampleValues());
        }

        @Test
        @DisplayName("显式的第 3 档才拿得到样本值许可")
        void 显式第3档才有样本值许可() {
            existingRow("SAMPLE_VALUES");
            assertTrue(service.dataTier(1L).allowsSampleValues());
        }
    }

    @Nested
    @DisplayName("视图：安全说明要跟着连接一起出去")
    class ViewExposure {

        @Test
        @DisplayName("存量行的 NULL 归一成默认档，界面上不出现 null")
        void 视图归一空值() {
            existingRow(null);
            ConnectorView v = service.get(1L);
            assertEquals("DERIVED_STATS", v.getSemanticDataTier());
            assertNotNull(v.getSemanticDataTierLabel());
            assertFalse(v.getSemanticDataTierLabel().isBlank());
        }

        /**
         * 出库说明必须随连接详情一起返回。只给一个枚举名和一个短名，界面上就只剩三个
         * 人畜无害的词，客户看不出第 3 档和第 2 档差在哪——而那是他唯一真正需要看懂的一次选择。
         */
        @Test
        @DisplayName("第 3 档的连接要带上「真实取值」那句话")
        void 视图带出安全说明() {
            existingRow("SAMPLE_VALUES");
            ConnectorView v = service.get(1L);
            assertEquals("SAMPLE_VALUES", v.getSemanticDataTier());
            assertNotNull(v.getSemanticDataTierEgress());
            assertTrue(v.getSemanticDataTierEgress().contains("真实取值"),
                    "给客户看的那句话必须说出「真实取值」，不许回避：" + v.getSemanticDataTierEgress());
        }
    }
}

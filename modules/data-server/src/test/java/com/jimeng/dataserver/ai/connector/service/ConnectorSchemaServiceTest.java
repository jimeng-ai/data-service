package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 结构漂移检测的纯逻辑部分。 */
class ConnectorSchemaServiceTest {

    private final ConnectorSchemaService service = new ConnectorSchemaService(
            mock(ConnectionMapper.class), mock(ConnectorSchemaMapper.class),
            mock(ConnectorRegistry.class),
            // 下面这几组只跑 diff / 指纹这些纯函数，不碰 refresh()，所以语义层、事务模板与网关
            // 给 mock 就够了。真要跑 refresh() 的那组在 {@link ThroughGateway} 里自己搭一套。
            mock(ConnectorSemanticService.class), mock(PlatformTransactionManager.class),
            mock(ConnectorGateway.class), mock(ObjectProvider.class), mock(org.redisson.api.RedissonClient.class));

    private static ObjectDetail table(List<FieldDetail> fields, String comment) {
        return new ObjectDetail("orders", "BASE TABLE", comment, fields, Map.of());
    }

    private static FieldDetail col(String name, String type) {
        return new FieldDetail(name, type, true, null, null);
    }

    private ConnectorSchema row(String name, ObjectDetail detail) {
        ConnectorSchema s = new ConnectorSchema();
        s.setObjectName(name);
        s.setContentHash(ConnectorSchemaService.structureFingerprint(detail));
        try {
            s.setDetailJson(CommonUtil.getObjectMapper()
                    .writeValueAsString(ConnectorSchemaService.toDetailMap(detail)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    // ================================================================

    @Nested
    @DisplayName("结构指纹")
    class Fingerprint {

        /**
         * ★ 最重要的一条：行数估算这类<b>每次都在变</b>的值绝不能进指纹。
         *
         * <p>目录里的注释带着「约 N 行」（InnoDB 的 TABLE_ROWS 是估算值，同一张表两次查能差几倍）。
         * 把它算进指纹，每次刷新都会报「全部对象都变了」，报警变成噪音之后就没人看了——
         * 一个只会狼来了的报警，等于没有报警。
         */
        @Test
        void 行数估算变化不算结构变化() {
            List<FieldDetail> fields = List.of(col("id", "bigint"), col("amount", "decimal(12,2)"));
            String a = ConnectorSchemaService.structureFingerprint(table(fields, "订单表（约 5 行，InnoDB 估算值）"));
            String b = ConnectorSchemaService.structureFingerprint(table(fields, "订单表（约 8371 行，InnoDB 估算值）"));
            assertEquals(a, b);
        }

        @Test
        void 列类型变化算结构变化() {
            String a = ConnectorSchemaService.structureFingerprint(table(List.of(col("status", "varchar(16)")), null));
            String b = ConnectorSchemaService.structureFingerprint(table(List.of(col("status", "varchar(32)")), null));
            assertNotEquals(a, b);
        }

        @Test
        void 新增列算结构变化() {
            String a = ConnectorSchemaService.structureFingerprint(table(List.of(col("id", "bigint")), null));
            String b = ConnectorSchemaService.structureFingerprint(
                    table(List.of(col("id", "bigint"), col("channel", "varchar(32)")), null));
            assertNotEquals(a, b);
        }

        /** 列注释是业务语义的来源，客户改了注释意味着口径可能变了，必须算变化。 */
        @Test
        void 列注释变化算结构变化() {
            String a = ConnectorSchemaService.structureFingerprint(
                    table(List.of(new FieldDetail("amount", "decimal", true, "订单金额（含税）", null)), null));
            String b = ConnectorSchemaService.structureFingerprint(
                    table(List.of(new FieldDetail("amount", "decimal", true, "订单金额（不含税）", null)), null));
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("对象级差异")
    class ObjectLevel {

        @Test
        void 结构没变时零差异() {
            ObjectDetail d = table(List.of(col("id", "bigint")), null);
            var prev = ConnectorSchemaService.indexByName(List.of(row("orders", d)));
            assertTrue(service.diff(prev, List.of(row("orders", d))).isEmpty());
        }

        @Test
        void 新增表判为ADDED() {
            ObjectDetail d = table(List.of(col("id", "bigint")), null);
            var prev = ConnectorSchemaService.indexByName(List.of(row("orders", d)));
            var diffs = service.diff(prev, List.of(row("orders", d), row("refunds", d)));
            assertEquals(1, diffs.size());
            assertEquals("ADDED", diffs.get(0).getChange());
            assertEquals("refunds", diffs.get(0).getObjectName());
        }

        /** 表被删比加字段严重得多：挂在它上面的指标口径、样例问答会直接失效。 */
        @Test
        void 删表判为REMOVED() {
            ObjectDetail d = table(List.of(col("id", "bigint")), null);
            var prev = ConnectorSchemaService.indexByName(List.of(row("orders", d), row("refunds", d)));
            var diffs = service.diff(prev, List.of(row("orders", d)));
            assertEquals(1, diffs.size());
            assertEquals("REMOVED", diffs.get(0).getChange());
            assertEquals("refunds", diffs.get(0).getObjectName());
        }
    }

    @Nested
    @DisplayName("列级差异")
    class FieldLevel {

        @Test
        void 新增列说清列名与类型() {
            ConnectorSchema before = row("orders", table(List.of(col("id", "bigint")), null));
            ConnectorSchema after = row("orders",
                    table(List.of(col("id", "bigint"), col("channel", "varchar(32)")), null));
            List<String> d = service.fieldDiff(before, after);
            assertEquals(1, d.size());
            assertTrue(d.get(0).contains("新增列") && d.get(0).contains("channel") && d.get(0).contains("varchar(32)"));
        }

        @Test
        void 删除列被识别() {
            ConnectorSchema before = row("orders",
                    table(List.of(col("id", "bigint"), col("legacy", "int")), null));
            ConnectorSchema after = row("orders", table(List.of(col("id", "bigint")), null));
            List<String> d = service.fieldDiff(before, after);
            assertEquals(1, d.size());
            assertTrue(d.get(0).contains("删除列") && d.get(0).contains("legacy"));
        }

        @Test
        void 类型变更说清前后() {
            ConnectorSchema before = row("orders", table(List.of(col("status", "varchar(16)")), null));
            ConnectorSchema after = row("orders", table(List.of(col("status", "varchar(32)")), null));
            List<String> d = service.fieldDiff(before, after);
            assertEquals(1, d.size());
            assertTrue(d.get(0).contains("varchar(16)") && d.get(0).contains("varchar(32)"));
        }

        /** 解析不出旧细节时退化成一句「有变化」，而不是编造具体内容。 */
        @Test
        void 旧细节损坏时不编造() {
            ConnectorSchema before = new ConnectorSchema();
            before.setObjectName("orders");
            before.setDetailJson("{坏掉的");
            ConnectorSchema after = row("orders", table(List.of(col("id", "bigint")), null));
            List<String> d = service.fieldDiff(before, after);
            // 旧的解析不出来 → 全部列都会被当成新增，这是可接受的降级；
            // 关键是不能抛异常让整次刷新失败。
            assertTrue(d.stream().allMatch(x -> x.startsWith("新增列")));
        }
    }

    /**
     * ★ 本仓库实际生效的 jackson-databind 是 2.11.1（由 logstash-logback-encoder:7.4 传递带入），
     * 而 record 的序列化支持是 2.12+ 才有的。这条测试钉住「落库的结构 JSON 不依赖 record 序列化」——
     * 第一版直接序列化 ObjectDetail(record)，线上表现是一句没有线索的「结构序列化失败」。
     */
    @Test
    @DisplayName("落库的结构 JSON 不依赖 record 序列化")
    void 结构JSON可被当前Jackson序列化() throws Exception {
        ObjectDetail d = table(List.of(col("id", "bigint")), "订单表");
        String json = CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(d));
        assertTrue(json.contains("\"name\":\"orders\""));
        assertTrue(json.contains("\"fields\""));
    }

    /**
     * ★ 刷新结构是<b>管理面</b>动作，但它必须走网关。
     *
     * <p>从前这里自己 {@code connector.open()}，注释写着「这是管理面操作，不走网关」——
     * 于是每租户速率、每实例并发闸、{@code connector_audit} 全都没有，
     * 而它一次要往<b>客户的生产库</b>打 1 + N 次查询。这组用例钉住的就是那个洞已经被堵上：
     * 它坏掉的时候<b>一声不吭</b>（结构照样刷新成功，只是没人知道我们打过客户的库），
     * 没有回归保护的话，某次重构把它改回去谁都不会发现。
     */
    @Nested
    @DisplayName("刷新走网关")
    class ThroughGateway {

        private ConnectionMapper connectionMapper;
        private ConnectorSchemaMapper schemaMapper;
        private ConnectorRegistry registry;
        private ConnectorGateway gateway;
        private Connector connector;
        private ConnectorSchemaService refreshService;

        @BeforeEach
        void setUp() {
            connectionMapper = mock(ConnectionMapper.class);
            schemaMapper = mock(ConnectorSchemaMapper.class);
            registry = mock(ConnectorRegistry.class);
            gateway = mock(ConnectorGateway.class);
            refreshService = new ConnectorSchemaService(connectionMapper, schemaMapper,
                    registry, mock(ConnectorSemanticService.class),
                    mock(PlatformTransactionManager.class), gateway, mock(ObjectProvider.class), mock(org.redisson.api.RedissonClient.class));
            // @PostConstruct 在单测里不会被调用，txTemplate 得自己初始化。
            refreshService.initTx();

            Connection row = new Connection();
            row.setId(100L);
            row.setTenantId("t1");
            row.setName("crm");
            row.setKind("MYSQL");
            row.setStatus("ACTIVE");
            row.setTransport("direct");
            when(connectionMapper.selectById(100L)).thenReturn(row);

            connector = mock(Connector.class);
            when(connector.declaredCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE));
            when(registry.require("MYSQL")).thenReturn(connector);

            TenantContext.set("t1");
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        /** 让 mock 的网关真的把 op 跑一遍，这样 refresh 的后两段也能照常走完。 */
        @SuppressWarnings("unchecked")
        private void givenGatewayRuns() {
            ConnectorSession session = mock(ConnectorSession.class,
                    withSettings().extraInterfaces(DescribeCapable.class));
            DescribeCapable describe = (DescribeCapable) session;
            when(describe.catalog()).thenReturn(new CatalogView("TABLE",
                    List.of(new CatalogEntry("orders", "BASE TABLE", "订单表")),
                    false, 1, "按估算行数的数量级降序"));
            when(describe.describe("orders")).thenReturn(
                    new ObjectDetail("orders", "BASE TABLE", "订单表",
                            List.of(new FieldDetail("id", "bigint", false, "主键", null)), Map.of()));
            when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                    .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
        }

        @Test
        @DisplayName("对客户库的那次往返走 executeAsPlatform，不自己 open()")
        void 刷新经过网关() {
            givenGatewayRuns();

            ConnectorSchemaService.SnapshotResult r = refreshService.refresh(100L);

            assertEquals(1, r.getObjectCount());
            verify(gateway).executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any());
            // 自己开连接的那条老路必须是死的：连接器实现连 open() 都不该被碰。
            verify(connector, never()).open(any());
        }

        /**
         * ★ 本组最要紧的一条。客户的 DBA 在他自己的审计里看到这些查询时，
         * 要分得清「平台在剖析这个库」和「Agent 在替人问数」——两者该不该发生、
         * 一次几百条还是一次一条、出问题找谁，答案完全不同。
         * 复用 {@code conn_catalog} 这类模型工具的名字，就等于把两件事混成一摊，
         * 事后没有任何办法从 {@code connector_audit} 里把它们择出来。
         */
        @Test
        @DisplayName("审计动作名一眼看得出是平台干的，不是模型工具那套名字")
        void 审计动作名与Agent面分开() {
            givenGatewayRuns();
            refreshService.refresh(100L);

            ArgumentCaptor<String> op = ArgumentCaptor.forClass(String.class);
            verify(gateway).executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), op.capture(), any());
            assertTrue(op.getValue().startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX),
                    "管理面的审计名必须带 platform. 前缀，实际: " + op.getValue());
            assertNotEquals("conn_catalog", op.getValue());
            assertNotEquals("conn_describe", op.getValue());
        }

        /**
         * ★ 语义层生成按 {@code importance_rank} 切片：先生成最重要的表，生成完一批就能用一批。
         *
         * <p>排名必须是<b>目录顺序</b>，而不是表名字母序、也不是「描述成功的第几张」：
         * <ul>
         *   <li>目录故意给成非字母序（{@code t_ord_mst} 最重要、{@code a_dict} 垫底）——
         *       从前快照读回按字母序，拿字母序当重要性，{@code t_} 开头的核心业务表会整片排到最后；</li>
         *   <li>中间那张描述失败：它在目录里照样占着第 2 位，跳过它会让后面每一张的排名错一位。</li>
         * </ul>
         */
        @Test
        @DisplayName("快照行 importanceRank 从 1 连续，且与目录顺序一致（描述失败的对象也占位）")
        @SuppressWarnings("unchecked")
        void 快照行importanceRank从1连续且按目录顺序() {
            ConnectorSession session = mock(ConnectorSession.class,
                    withSettings().extraInterfaces(DescribeCapable.class));
            DescribeCapable describe = (DescribeCapable) session;
            List<String> catalogOrder = List.of("t_ord_mst", "m_ord_log", "a_dict");
            when(describe.catalog()).thenReturn(new CatalogView("TABLE",
                    catalogOrder.stream().map(n -> new CatalogEntry(n, "BASE TABLE", null)).toList(),
                    false, catalogOrder.size(), "按估算行数的数量级降序"));
            when(describe.describe("t_ord_mst")).thenReturn(new ObjectDetail("t_ord_mst", "BASE TABLE", null,
                    List.of(new FieldDetail("id", "bigint", false, null, null)), Map.of()));
            when(describe.describe("m_ord_log")).thenThrow(
                    ConnectorException.of(ConnectorErrorCode.FORBIDDEN, "没有这张表的权限"));
            when(describe.describe("a_dict")).thenReturn(new ObjectDetail("a_dict", "BASE TABLE", null,
                    List.of(new FieldDetail("code", "varchar(16)", false, null, null)), Map.of()));
            when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                    .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));

            refreshService.refresh(100L);

            ArgumentCaptor<ConnectorSchema> inserted = ArgumentCaptor.forClass(ConnectorSchema.class);
            verify(schemaMapper, times(3)).insert(inserted.capture());
            List<ConnectorSchema> rows = inserted.getAllValues();
            assertEquals(catalogOrder, rows.stream().map(ConnectorSchema::getObjectName).toList(),
                    "快照行按目录顺序插入——旧快照的 id 回退依赖这一点");
            assertEquals(List.of(1, 2, 3), rows.stream().map(ConnectorSchema::getImportanceRank).toList(),
                    "排名从 1 开始连续，等于目录位置；描述失败的 m_ord_log 仍是第 2 名");
        }

        /**
         * 「这种类型压根不提供自描述」要在开连接<b>之前</b>判掉，而且必须继续抛
         * {@code OPERATION_UNSUPPORTED}：语义层靠这个码把「对它不适用」与「真失败」分开，
         * 归错了会让每一条 HTTP 连接在界面上显示成「语义层：失败」。
         */
        @Test
        @DisplayName("类型不支持自描述 → 不惊动网关，且仍是 OPERATION_UNSUPPORTED")
        void 类型不支持自描述时不碰网关() {
            when(connector.declaredCapabilities()).thenReturn(Set.of(Capability.INVOKE, Capability.HEALTH));

            ServiceException e = assertThrows(ServiceException.class, () -> refreshService.refresh(100L));

            assertEquals(ExceptionCode.OPERATION_UNSUPPORTED.getResultCode(), e.getRespCode());
            verify(gateway, never()).executeAsPlatform(any(), any(), anyString(), any());
        }

        /**
         * 网关拒绝（限流、并发闸满、凭据解不开……）必须变成管理台看得懂的一句话。
         * {@code ConnectorException} 原样穿出去就是一个没有 handler 的 5000。
         */
        @Test
        @DisplayName("网关拒绝 → 转成业务异常，脱敏文案原样带给管理台")
        void 网关拒绝被转成业务异常() {
            when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                    .thenThrow(ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                            "连接「crm」当前并发查询已达上限，请稍后重试"));

            ServiceException e = assertThrows(ServiceException.class, () -> refreshService.refresh(100L));

            assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
            assertTrue(e.getRespMsg().contains("并发查询已达上限"));
        }
    }

    /**
     * 截断说明。快照上限（{@code MAX_OBJECTS=200}）本身没得商量——500 张表就是 500 次
     * 打在客户生产库上的 {@code information_schema} 查询；真正要命的是
     * <b>「留下的是哪 200 个」这件事没人说</b>。
     *
     * <p>从前的答案是「表名字典序最靠前的 200 个」，中文业务库里的实际效果是
     * {@code t_} 开头的核心业务表整片没有说明书，而现象只是「模型答不上来」。
     * 现在按重要性截断了，就更得把排序依据写进消息里：否则读的人会拿旧直觉去读新行为。
     */
    @Nested
    @DisplayName("截断说明")
    class TruncationNote {

        private CatalogView catalog(int total, String ordering) {
            return new CatalogView("TABLE",
                    List.of(new CatalogEntry("t_ord_mst", "BASE TABLE", null)),
                    true, total, ordering);
        }

        @Test
        @DisplayName("说清共几个、留了几个、按什么留的")
        void 说明包含排序依据() {
            String note = ConnectorSchemaService.truncationNote(catalog(500, "按估算行数的数量级降序"), 200);
            assertTrue(note.contains("500"), "总数要出现，否则看不出漏了多少");
            assertTrue(note.contains("200"), "覆盖数要出现");
            assertTrue(note.contains("按估算行数的数量级降序"), "排序依据是这条消息存在的理由");
        }

        /**
         * ★ 连接器没声明排序依据时<b>不许编</b>。「不知道按什么排」和「按行数排」
         * 是两件完全不同的事，混成后者等于在截断消息里写一句看起来很具体的假话——
         * 而这条消息的全部作用就是防止误读。
         */
        @Test
        @DisplayName("连接器没声明顺序时如实说不知道")
        void 顺序未知时不编造() {
            String note = ConnectorSchemaService.truncationNote(catalog(500, null), 200);
            assertTrue(note.contains("未声明排序依据"));
            assertFalse(note.contains("行数"), "不能替连接器编一个它并没有承诺的顺序");

            // 空串和 null 是同一回事，别让一个空字符串拼出「本次只覆盖了的前 200 个」。
            assertEquals(note, ConnectorSchemaService.truncationNote(catalog(500, "  "), 200));
        }

        /** 没覆盖到的那批会怎样，也要说——沉默会被读成「都检查过了，没有变化」。 */
        @Test
        @DisplayName("说清没覆盖的那批不在漂移检测范围内")
        void 说明未覆盖对象的后果() {
            String note = ConnectorSchemaService.truncationNote(catalog(500, "按估算行数的数量级降序"), 200);
            assertTrue(note.contains("漂移"));
        }
    }
}

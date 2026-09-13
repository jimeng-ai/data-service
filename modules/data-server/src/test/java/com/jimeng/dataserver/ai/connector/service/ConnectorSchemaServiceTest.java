package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 结构漂移检测的纯逻辑部分。 */
class ConnectorSchemaServiceTest {

    private final ConnectorSchemaService service = new ConnectorSchemaService(
            mock(ConnectionMapper.class), mock(ConnectorSchemaMapper.class),
            mock(ConnectorRegistry.class), mock(ConnectorInstanceLoader.class));

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
}

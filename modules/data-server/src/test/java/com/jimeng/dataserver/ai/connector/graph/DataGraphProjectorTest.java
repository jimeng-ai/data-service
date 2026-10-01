package com.jimeng.dataserver.ai.connector.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SelfReference;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.MAPPER;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.json;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.relationView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.schemas;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.uk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设计文档 §7：关系判定、唯一键与基数、业务文字与兜底、排序，以及「永不出网」。 */
class DataGraphProjectorTest {

    @Nested
    @DisplayName("§5.2 关系判定（按顺序，先命中者为准）")
    class Judgement {

        @Test
        @DisplayName("1. 两端的表或列不在当前快照里：丢弃")
        void 端点缺失() {
            SystemGraph g = system(
                    join("t_item", "no_such_col", "t_order", "id"),
                    join("t_ext", "id", "t_gone", "id"),
                    join("t_region", "parent_id", "t_customer", "no_such_col"));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("2. status=STALE：丢弃，即使采样核对通过")
        void 过期() {
            ConnectorSemantic row = join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of());
            row.setStatus("STALE");
            assertTrue(system(row).getRelations().isEmpty());
        }

        @Test
        @DisplayName("3/4. 业务方的判断压过采样：UNRELATED 压过 CONFIRMED，RELATED 压过 REJECTED")
        void 业务方优先() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of("human_verdict", "UNRELATED")),
                    join("t_order", "shop_code", "t_customer", "code", "REJECTED", Map.of("human_verdict", "RELATED")));
            assertEquals(List.of("t_order.shop_code→t_customer.code"), edges(g));
            Relation r = g.getRelations().get(0);
            assertEquals("CONFIRMED", r.getTier());
            assertEquals("BUSINESS", r.getConfirmedBy());
        }

        @Test
        @DisplayName("★ 业务方写入时 status 就变成 CONFIRMED：没有 human_verdict 的 status=CONFIRMED 不算已确认")
        void status不代表成立() {
            ConnectorSemantic row = join("t_order", "status", "t_customer", "region_id");
            row.setStatus("CONFIRMED");
            assertTrue(system(row).getRelations().isEmpty());
        }

        @Test
        @DisplayName("5. 采样核对通过：实线，来源 DATA")
        void 数据核对() {
            Relation r = system(join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of()))
                    .getRelations().get(0);
            assertEquals("CONFIRMED", r.getTier());
            assertEquals("DATA", r.getConfirmedBy());
        }

        @Test
        @DisplayName("6. REJECTED、WEAK：丢弃，即使终点是主键")
        void 数据否定() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "REJECTED", Map.of()),
                    join("t_ext", "id", "t_order", "id", "WEAK", Map.of()));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("7. 多态关系：未确认不画；确认后画出并带判别列，从不带判别值")
        void 多态() {
            Map<String, Object> poly = Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "status",
                    "discriminator_value", "SECRET_VALUE");
            assertTrue(system(join("t_order", "customer_id", "t_customer", "id", "UNDECIDABLE", poly))
                    .getRelations().isEmpty());
            SystemGraph confirmed = system(join("t_order", "customer_id", "t_customer", "id", "CONFIRMED", poly));
            assertEquals("status", confirmed.getRelations().get(0).getDiscriminatorColumn());
            assertFalse(json(confirmed).contains("SECRET_VALUE"));
        }

        @Test
        @DisplayName("7′. 关系发现里模型那一遍推出的：未确认不画（终点是主键也不画）；核对通过或业务方确认后照常画")
        void 模型那一遍() {
            Map<String, Object> pass = Map.of("origin", "RELATION_PASS");
            assertTrue(system(join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", pass)).getRelations().isEmpty());
            assertTrue(system(join("t_region", "parent_id", "t_region", "id", "NONE", pass)).getTables().stream()
                    .allMatch(t -> t.getSelfReferences().isEmpty()), "自关联同样不画");
            assertEquals("DATA", system(join("t_item", "order_id", "t_order", "id", "CONFIRMED", pass))
                    .getRelations().get(0).getConfirmedBy());
            assertEquals("BUSINESS", system(join("t_item", "order_id", "t_order", "id", "NONE",
                    Map.of("origin", "RELATION_PASS", "human_verdict", "RELATED"))).getRelations().get(0).getConfirmedBy());
            assertEquals("INFERRED", system(join("t_item", "order_id", "t_order", "id", "NONE",
                    Map.of("origin", "NAME_RULE"))).getRelations().get(0).getTier(), "外键、命名规则推出的照旧画虚线");
        }

        @Test
        @DisplayName("8. 未核对 / 判不出来：终点列单独构成唯一键才画虚线（主键或唯一键都算）")
        void 结构推断() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", Map.of()),
                    join("t_order", "customer_code", "t_customer", "code"),
                    join("t_item", "sku_id", "t_sku", "id"),
                    join("t_order", "status", "t_log", "order_id"));
            assertEquals(List.of("t_item.order_id→t_order.id", "t_order.customer_code→t_customer.code"), edges(g));
            for (Relation r : g.getRelations()) {
                assertEquals("INFERRED", r.getTier());
                assertNull(r.getConfirmedBy());
            }
        }

        @Test
        @DisplayName("8. 终点列只是组合键的一部分：不算唯一键，不画")
        void 组合键() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_price", "价格", 7, List.of(pk("sku_id", "region_id")), "sku_id", "region_id", "price"));
            SystemGraph g = project(rows,
                    List.of(join("t_item", "sku_id", "t_price", "sku_id")));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("9. 终点不是唯一键、也没被确认：不画（同名码值列、同维度列都在这里被挡掉）")
        void 非键终点() {
            assertTrue(system(join("t_order", "status", "t_customer", "region_id", "UNDECIDABLE", Map.of()))
                    .getRelations().isEmpty());
        }
    }

    @Nested
    @DisplayName("§5.3 基数与自关联")
    class Cardinality {

        @Test
        @DisplayName("终点唯一：起点不唯一为多对一，起点也唯一为一对一；终点不唯一（只会是已确认关系）为 null")
        void 基数() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_ext", "id", "t_order", "id"),
                    join("t_log", "order_id", "v_sales", "order_id", "CONFIRMED", Map.of()));
            assertEquals("MANY_TO_ONE", relation(g, "t_item.order_id→t_order.id").getCardinality());
            assertEquals("ONE_TO_ONE", relation(g, "t_ext.id→t_order.id").getCardinality());
            assertNull(relation(g, "t_log.order_id→v_sales.order_id").getCardinality());
        }

        @Test
        @DisplayName("自关联进 selfReferences、不进 relations；只有自关联的表不算 related")
        void 自关联() {
            SystemGraph g = system(join("t_region", "parent_id", "t_region", "id"));
            assertTrue(g.getRelations().isEmpty());
            TableCard region = card(g, "t_region");
            assertEquals(1, region.getSelfReferences().size());
            assertEquals("parent_id", region.getSelfReferences().get(0).getFromColumn());
            assertEquals("id", region.getSelfReferences().get(0).getToColumn());
            assertFalse(region.isRelated());
        }
    }

    @Nested
    @DisplayName("§5.4 表名、卡片行与线上的字")
    class Naming {

        @Test
        @DisplayName("表名：去掉估算行数后缀；注释像名称才当标题；说明句和空注释为 null；BASE TABLE 归一")
        void 表名() {
            SystemGraph g = system();
            assertEquals("订单表", card(g, "t_order").getDisplayName());
            assertEquals("订单表", card(g, "t_order").getComment());
            assertNull(card(g, "t_ext").getDisplayName());
            assertEquals("扩展表，按 id 一对一拆出", card(g, "t_ext").getComment());
            assertNull(card(g, "t_region").getDisplayName());
            assertNull(card(g, "t_region").getComment());
            assertEquals("TABLE", card(g, "t_order").getObjectType());
            assertEquals("VIEW", card(g, "v_sales").getObjectType());
        }

        @Test
        @DisplayName("「像名称」：16 个字符为界，含句子标点即不算")
        void 像名称() {
            assertTrue(DataGraphProjector.looksLikeName("一二三四五六七八九十一二三四五六"));
            assertFalse(DataGraphProjector.looksLikeName("一二三四五六七八九十一二三四五六七"));
            assertFalse(DataGraphProjector.looksLikeName("无公司代码字段，样本数据"));
            assertFalse(DataGraphProjector.looksLikeName("orders, legacy"));
            assertTrue(DataGraphProjector.looksLikeName("GB5D 公司代码"));
            assertTrue(DataGraphProjector.looksLikeName("客户-财务视图明细"));
            assertFalse(DataGraphProjector.looksLikeName(null));
        }

        @Test
        @DisplayName("非 TABLE / VIEW 的对象不进星图")
        void 对象类型() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            ConnectorSchema seq = table("s_seq", "序列", 0, List.of(), "id");
            seq.setObjectType("SEQUENCE");
            rows.add(seq);
            SystemGraph g = project(rows, List.of());
            assertTrue(g.getTables().stream().noneMatch(t -> t.getName().equals("s_seq")));
        }

        @Test
        @DisplayName("卡片行：主键列；没有主键取列数最少的唯一键；relationColumns 含本表作为起点和终点的列")
        void 卡片行() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "编码", 8, List.of(uk("uk_b", "a", "b"), uk("uk_a", "code")), "a", "b", "code|编码"));
            SystemGraph g = project(rows, List.of(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_order", "customer_code", "t_customer", "code")));
            assertEquals(List.of("id"), names(card(g, "t_order").getKeyColumns()));
            assertEquals("订单主键", card(g, "t_order").getKeyColumns().get(0).getComment());
            assertEquals(List.of("code"), names(card(g, "t_code").getKeyColumns()));
            assertEquals(List.of(), names(card(g, "t_log").getKeyColumns()));
            assertEquals(List.of("customer_code", "id"), names(card(g, "t_order").getRelationColumns()));
            assertEquals(List.of("code"), names(card(g, "t_customer").getRelationColumns()));
            assertTrue(card(g, "t_order").isRelated());
            assertFalse(card(g, "t_sku").isRelated());
            assertEquals(5, card(g, "t_order").getFieldCount());
        }

        @Test
        @DisplayName("没有业务视图时，关系角色名退回起点列注释；为空或与列名相同（忽略大小写）时不写")
        void 标签() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_ext", "id", "t_order", "id"),
                    join("t_item", "sku_id", "t_sku", "id", "CONFIRMED", Map.of()));
            assertEquals("所属订单", relation(g, "t_item.order_id→t_order.id").getRole());
            assertNull(relation(g, "t_ext.id→t_order.id").getRole());
            assertNull(relation(g, "t_item.sku_id→t_sku.id").getRole());
        }
    }

    @Test
    @DisplayName("§5.5 顺序确定：输入顺序打乱，输出的表、关系、id 都不变；id 互不相同")
    void 顺序与id() {
        List<ConnectorSemantic> joins = List.of(
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_item", "order_id", "t_order", "id"),
                join("t_ext", "id", "t_order", "id"));
        SystemGraph a = project(schemas(), joins);
        List<ConnectorSemantic> reversedJoins = new ArrayList<>(joins);
        Collections.reverse(reversedJoins);
        List<ConnectorSchema> reversedSchemas = new ArrayList<>(schemas());
        Collections.reverse(reversedSchemas);
        SystemGraph b = project(reversedSchemas, reversedJoins);

        assertEquals(List.of("t_order", "t_customer", "t_item", "t_ext", "t_region", "t_log", "t_sku", "v_sales"),
                tableNames(a));
        assertEquals(tableNames(a), tableNames(b));
        assertEquals(List.of("t_ext.id→t_order.id", "t_item.order_id→t_order.id",
                "t_order.customer_code→t_customer.code"), edges(a));
        assertEquals(edges(a), edges(b));
        assertEquals(ids(a), ids(b));
        assertEquals(3, new HashSet<>(ids(a)).size());
        ids(a).forEach(id -> assertTrue(id.matches("[0-9a-f]{16}"), id));
    }

    @Test
    @DisplayName("单表详情：字段按快照顺序，标主键 / 唯一键与是否参与关系；只带与本表相关的关系；未知表为 null")
    void 单表详情() {
        List<ConnectorSemantic> joins = List.of(
                join("t_item", "order_id", "t_order", "id"),
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_region", "parent_id", "t_region", "id"),
                join("t_ext", "id", "t_order", "id"));
        TableDetail order = detail(schemas(), joins, "t_order");
        assertEquals(List.of("id", "customer_id", "customer_code", "shop_code", "status"),
                order.getFields().stream().map(Field::getName).toList());
        assertEquals("PRIMARY", field(order, "id").getKey());
        assertTrue(field(order, "id").isInRelation());
        assertTrue(field(order, "customer_code").isInRelation());
        assertFalse(field(order, "shop_code").isInRelation());
        assertEquals("订单主键", field(order, "id").getComment());
        assertNull(field(order, "shop_code").getComment());
        assertEquals(3, order.getRelations().size());

        TableDetail customer = detail(schemas(), joins, "t_customer");
        assertEquals("PRIMARY", field(customer, "id").getKey());
        assertEquals("UNIQUE", field(customer, "code").getKey());
        assertNull(field(customer, "region_id").getKey());
        assertEquals(1, customer.getRelations().size());

        TableDetail region = detail(schemas(), joins, "t_region");
        assertTrue(region.getRelations().isEmpty());
        assertEquals(1, region.getSelfReferences().size());
        assertTrue(field(region, "parent_id").isInRelation());

        assertNull(detail(schemas(), joins, "t_nope"));
    }

    @Nested
    @DisplayName("容易踩到的输入")
    class Edges {

        @Test
        @DisplayName("整份快照都是旧的（没有 extra.unique_keys）：推断关系一条都不画，已确认的照常画、基数为空")
        void 旧快照() {
            List<ConnectorSchema> rows = List.of(
                    table("t_order", "订单表", 1, null, "id", "customer_id"),
                    table("t_customer", "客户表", 2, null, "id"));
            SystemGraph g = project(rows, List.of(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "id", "t_customer", "id", "CONFIRMED", Map.of())));
            assertEquals(List.of("t_order.id→t_customer.id"), edges(g));
            assertNull(g.getRelations().get(0).getCardinality());
            assertEquals(List.of(), names(card(g, "t_order").getKeyColumns()));
        }

        @Test
        @DisplayName("中文、带空格的表名和列名：照常判定，id 是 16 位十六进制，单表详情按原名取到")
        void 中文名() {
            List<ConnectorSchema> rows = List.of(
                    table("采购 单", "采购单", 1, List.of(pk("编号")), "编号", "客户 编号|客户编号"),
                    table("客户", "客户", 2, List.of(pk("编号")), "编号", "名称"));
            List<ConnectorSemantic> joins = List.of(join("采购 单", "客户 编号", "客户", "编号"));
            SystemGraph g = project(rows, joins);
            Relation r = g.getRelations().get(0);
            assertEquals("MANY_TO_ONE", r.getCardinality());
            assertEquals("INFERRED", r.getTier());
            assertEquals("客户编号", r.getRole());
            assertTrue(r.getId().matches("[0-9a-f]{16}"), r.getId());
            assertEquals(1, detail(rows, joins, "采购 单").getRelations().size());
        }

        @Test
        @DisplayName("同一对表之间多条关系（不同列）：各自保留、id 不同，两端的关系列都列出来")
        void 同一对表多条关系() {
            SystemGraph g = system(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "customer_code", "t_customer", "code"));
            assertEquals(List.of("t_order.customer_code→t_customer.code", "t_order.customer_id→t_customer.id"),
                    edges(g));
            assertEquals(2, new HashSet<>(ids(g)).size());
            assertEquals(List.of("code", "id"), names(card(g, "t_customer").getRelationColumns()));
            assertEquals(List.of("customer_code", "customer_id"), names(card(g, "t_order").getRelationColumns()));
        }

        @Test
        @DisplayName("一行 detail_json 坏了：只丢这一行，其余照常")
        void 坏行() {
            ConnectorSemantic broken = join("t_ext", "id", "t_order", "id");
            broken.setDetailJson("{not json");
            SystemGraph g = system(broken, join("t_item", "order_id", "t_order", "id"));
            assertEquals(List.of("t_item.order_id→t_order.id"), edges(g));
        }
    }

    @Test
    @DisplayName("★ 永不出网：语义层写给 Agent 的说明、依据、置信度、判别值、租户都不在输出里")
    void 不出网() {
        ConnectorSemantic row = join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", Map.of(
                "basis", "左侧只采到 1 个非空取值", "note", "验证时先统计 order_id=0 的占比",
                "care_reason", "注意空值", "verify_note", "样本不足", "discriminator_value", "SECRET"));
        row.setGloss("关联 t_order.id。已探查但判不出来");
        row.setConfidence(55);
        row.setEvidence("NAME");
        row.setTenantId("tenant-x");
        SystemGraph g = system(row);
        TableDetail d = detail(schemas(), List.of(row), "t_item");
        for (String out : List.of(json(g), json(d))) {
            for (String banned : List.of("\"gloss\"", "\"confidence\"", "\"basis\"", "\"note\"", "\"care_reason\"",
                    "\"verify_note\"", "\"discriminator_value\"", "\"evidence\"", "\"tenantId\"",
                    "已探查", "左侧只采到", "验证时先统计", "SECRET", "tenant-x")) {
                assertFalse(out.contains(banned), banned + " 出现在 " + out);
            }
        }
    }

    @Test
    @DisplayName("出网对象能被项目的 Jackson（2.11，不认 record）序列化，布尔字段名不带 is")
    void 序列化() throws Exception {
        List<ConnectorSemantic> joins = List.of(join("t_item", "order_id", "t_order", "id"));
        JsonNode g = MAPPER.readTree(json(project(schemas(), joins)));
        assertEquals("7", g.get("connectorId").asText());
        assertTrue(g.get("tables").get(0).has("related"));
        JsonNode d = MAPPER.readTree(json(detail(schemas(), joins, "t_item")));
        assertTrue(d.get("fields").get(0).has("inRelation"));
        assertTrue(d.get("fields").get(0).has("nullable"));
    }

    @Nested
    @DisplayName("v3：业务视图与兜底（§6.3、§7）")
    class BusinessView {

        private SystemGraph withViews(List<ConnectorSemantic> joins, com.jimeng.persistence.entity.ConnectorBusinessView... views) {
            return DataGraphProjector.system(connection(), schemas(), joins, List.of(views), "READY");
        }

        @Test
        @DisplayName("★ 标题、说明、领域取业务视图，来源标 BUSINESS_VIEW；客户原注释留给技术信息区")
        void 业务视图优先() {
            TableCard order = card(withViews(List.of(),
                    objectView("t_order", "销售订单", "一条记录是一张销售订单。", "销售")), "t_order");
            assertEquals("销售订单", order.getDisplayName());
            assertEquals("BUSINESS_VIEW", order.getNameSource());
            assertEquals("一条记录是一张销售订单。", order.getSummary());
            assertEquals("销售", order.getDomain());
            assertEquals("订单表", order.getComment());
        }

        @Test
        @DisplayName("没有业务名：退回像名称的表注释（COMMENT）；说明句、空注释、夹着代码或禁用词的注释都退回表名（PHYSICAL）")
        void 标题兜底() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "t_order 的编码", 9, List.of(pk("id")), "id"));
            rows.add(table("t_est", "估算表", 10, List.of(pk("id")), "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(),
                    List.of(objectView("t_customer", null, null, "主数据")), null);
            assertEquals("COMMENT", card(g, "t_order").getNameSource());
            assertEquals("订单表", card(g, "t_order").getDisplayName());
            assertNull(card(g, "t_order").getSummary());
            assertNull(card(g, "t_order").getDomain());
            assertEquals("COMMENT", card(g, "t_customer").getNameSource(), "只有领域、没有业务名的行不算业务名");
            assertEquals("主数据", card(g, "t_customer").getDomain());
            assertEquals("PHYSICAL", card(g, "t_ext").getNameSource());
            assertNull(card(g, "t_ext").getDisplayName());
            assertEquals("PHYSICAL", card(g, "t_region").getNameSource());
            assertEquals("PHYSICAL", card(g, "t_code").getNameSource(), "注释夹着表名，不拿来当业务名");
            assertNull(card(g, "t_code").getDisplayName());
            assertEquals("PHYSICAL", card(g, "t_est").getNameSource(), "注释带禁用词，同样不用");
        }

        @Test
        @DisplayName("★ 关系角色名：业务视图优先；没有就退回起点列注释，注释夹着代码或带禁用词时不用")
        void 角色名() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_pay", "付款", 9, List.of(pk("id")), "id", "order_id|关联 t_order.id"));
            rows.add(table("t_refund", "退款", 10, List.of(pk("id")), "id", "order_id|估算的原订单"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                            join("t_item", "order_id", "t_order", "id"),
                            join("t_order", "customer_code", "t_customer", "code"),
                            join("t_pay", "order_id", "t_order", "id"),
                            join("t_refund", "order_id", "t_order", "id")),
                    List.of(relationView("t_order", "customer_code", "下单客户")), null);
            assertEquals("下单客户", relation(g, "t_order.customer_code→t_customer.code").getRole());
            assertEquals("所属订单", relation(g, "t_item.order_id→t_order.id").getRole());
            assertNull(relation(g, "t_pay.order_id→t_order.id").getRole(), "注释夹着代码");
            assertNull(relation(g, "t_refund.order_id→t_order.id").getRole(), "注释带禁用词");
        }

        @Test
        @DisplayName("自关联带上可信度、来源与角色名（v2 审查 #4）")
        void 自关联() {
            SelfReference confirmed = card(withViews(
                    List.of(join("t_region", "parent_id", "t_region", "id", "CONFIRMED", Map.of())),
                    relationView("t_region", "parent_id", "上级地区")), "t_region").getSelfReferences().get(0);
            assertEquals("CONFIRMED", confirmed.getTier());
            assertEquals("DATA", confirmed.getConfirmedBy());
            assertEquals("上级地区", confirmed.getRole());
            SelfReference inferred = card(system(join("t_region", "parent_id", "t_region", "id")), "t_region")
                    .getSelfReferences().get(0);
            assertEquals("INFERRED", inferred.getTier());
            assertNull(inferred.getConfirmedBy());
            assertEquals("上级地区", inferred.getRole(), "没有业务视图时退回列注释");
        }

        @Test
        @DisplayName("1′. 起点和终点是同一张表的同一列：丢弃（v2 审查 #3）")
        void 同表同列() {
            SystemGraph g = system(join("t_order", "id", "t_order", "id", "CONFIRMED", Map.of()));
            assertTrue(card(g, "t_order").getSelfReferences().isEmpty());
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("快照达到 200 个对象：truncated（v2 审查 #2）；整理状态原样带出")
        void 截断与状态() {
            List<ConnectorSchema> rows = new ArrayList<>();
            for (int i = 0; i < 199; i++) {
                rows.add(table(String.format("t_%03d", i), "表", i, List.of(pk("id")), "id"));
            }
            assertFalse(DataGraphProjector.system(connection(), rows, List.of(), List.of(), null).isTruncated());
            rows.add(table("t_199", "表", 199, List.of(pk("id")), "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(), List.of(), "RUNNING");
            assertTrue(g.isTruncated());
            assertEquals("RUNNING", g.getViewStatus());
        }

        @Test
        @DisplayName("单表详情同样带业务名、说明、领域")
        void 单表详情() {
            TableDetail d = DataGraphProjector.table(schemas(), List.of(),
                    List.of(objectView("t_item", "订单明细", "一条记录是订单里的一行商品。", "销售")), "t_item");
            assertEquals("订单明细", d.getDisplayName());
            assertEquals("BUSINESS_VIEW", d.getNameSource());
            assertEquals("一条记录是订单里的一行商品。", d.getSummary());
            assertEquals("销售", d.getDomain());
        }

        @Test
        @DisplayName("★ 业务视图的输入指纹、模型、提示词版本、来源都不出网")
        void 内部字段不出网() {
            List<ConnectorSemantic> joins = List.of(join("t_item", "order_id", "t_order", "id"));
            SystemGraph g = withViews(joins, objectView("t_order", "销售订单", "说明", "销售"),
                    relationView("t_item", "order_id", "所属订单"));
            TableDetail d = DataGraphProjector.table(schemas(), joins,
                    List.of(objectView("t_order", "销售订单", "说明", "销售")), "t_order");
            for (String out : List.of(json(g), json(d))) {
                for (String banned : List.of("HASH_SECRET", "MODEL_SECRET", "PV_SECRET", "\"inputHash\"",
                        "\"modelCode\"", "\"promptVersion\"", "\"source\"")) {
                    assertFalse(out.contains(banned), banned + " 出现在 " + out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SystemGraph system(ConnectorSemantic... joins) {
        return project(schemas(), List.of(joins));
    }

    private static SystemGraph project(List<ConnectorSchema> rows, List<ConnectorSemantic> joins) {
        return DataGraphProjector.system(connection(), rows, joins, List.of(), null);
    }

    private static TableDetail detail(List<ConnectorSchema> rows, List<ConnectorSemantic> joins, String name) {
        return DataGraphProjector.table(rows, joins, List.of(), name);
    }

    private static List<String> edges(SystemGraph g) {
        return g.getRelations().stream()
                .map(r -> r.getFromTable() + "." + r.getFromColumn() + "→" + r.getToTable() + "." + r.getToColumn())
                .toList();
    }

    private static Relation relation(SystemGraph g, String edge) {
        int i = edges(g).indexOf(edge);
        assertTrue(i >= 0, edge + " 不在 " + edges(g));
        return g.getRelations().get(i);
    }

    private static TableCard card(SystemGraph g, String name) {
        return g.getTables().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Field field(TableDetail d, String name) {
        return d.getFields().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::getName).toList();
    }

    private static List<String> tableNames(SystemGraph g) {
        return g.getTables().stream().map(TableCard::getName).toList();
    }

    private static List<String> ids(SystemGraph g) {
        return g.getRelations().stream().map(Relation::getId).toList();
    }
}

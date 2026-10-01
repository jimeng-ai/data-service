package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.service.RelationCandidates.Candidate;
import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jimeng.dataserver.ai.connector.service.RelationRuleFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationCandidatesTest {

    private static List<String> lines(List<Candidate> candidates) {
        return candidates.stream()
                .map(c -> c.fromTable() + "." + c.fromColumn() + " -> " + c.toTable() + "." + c.toColumn())
                .toList();
    }

    /** 一张表：{@code key} 为单列主键列名，{@code "-"} = 确实没有唯一键，{@code null} = 唯一键未知。 */
    private static ConnectorSchema table(String name, String key, String... columns) {
        ConnectorSchema row = RelationRuleFixtures.schema(name, key == null ? "-" : key, List.of(columns));
        if (key == null) {
            row.setDetailJson(row.getDetailJson().replace(",\"extra\":{\"unique_keys\":[]}", ""));
        }
        return row;
    }

    @Nested
    @DisplayName("同形夹具：本地两个系统换了名字的快照")
    class SameShape {

        @Test
        @DisplayName("★ 形同 klny_erp：恰好 33 条，一条不多一条不少")
        void erpA() {
            assertEquals(RelationRuleFixtures.ERP_A_EXPECTED,
                    lines(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_A))));
        }

        @Test
        @DisplayName("★ 形同 erp_real：恰好 16 条")
        void erpB() {
            assertEquals(RelationRuleFixtures.ERP_B_EXPECTED,
                    lines(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_B))));
        }

        @Test
        @DisplayName("来源都标 NAME_RULE")
        void origin() {
            assertTrue(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_A)).stream()
                    .allMatch(c -> RelationCandidates.ORIGIN_NAME_RULE.equals(c.origin())));
        }
    }

    @Nested
    @DisplayName("命名规则的边界")
    class NamingRule {

        @Test
        @DisplayName("词干等于后缀、或以后缀结尾，都算；取对上的后缀最长的那张表")
        void 等于或结尾() {
            List<ConnectorSchema> s = List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("M_RECEIPTVENDOR", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "VENDORID", "RECEIPTVENDORID", "HEAD_VENDORID"));
            assertEquals(List.of(
                    "T_ORDER.HEAD_VENDORID -> M_VENDOR.OID",
                    "T_ORDER.RECEIPTVENDORID -> M_RECEIPTVENDOR.OID",
                    "T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("最长的对上了两张表：放弃，留给模型那一遍")
        void 并列放弃() {
            List<ConnectorSchema> s = List.of(
                    table("A_ITEM", "OID", "OID"),
                    table("B_ITEM", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "ITEMID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("本列是本表的单列唯一键：不算（它是这张表自己的编号）")
        void 自身唯一键不算() {
            List<ConnectorSchema> s = List.of(
                    table("T_ORDER", "OID", "OID"),
                    table("ORDER_EXT", "ORDERID", "ORDERID", "MEMO"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("只连别的表：只对得上本表时不提候选")
        void 不连本表() {
            List<ConnectorSchema> s = List.of(
                    table("PS_PROJECT", "OID", "OID", "PROJECTID", "STANDARDPROJECTID"),
                    table("T_TASK", "OID", "OID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("后缀不足 4 个字符不算：org 不行，xorg 可以")
        void 短后缀() {
            List<ConnectorSchema> s = List.of(
                    table("X_ORG", "OID", "OID"),
                    table("T_DEPT", "OID", "OID", "PARENTORGID", "XORGID"));
            assertEquals(List.of("T_DEPT.XORGID -> X_ORG.OID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("词干不足 4 个字符不算：OID / SOID / POID 什么都说明不了")
        void 短词干() {
            List<ConnectorSchema> s = List.of(
                    table("T_SO", "OID", "OID"),
                    table("T_ITEM", "OID", "OID", "SOID", "POID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("复数表名：orders 按 order 比；address 本身以 s 结尾，照样按 address 比")
        void 复数() {
            List<ConnectorSchema> s = List.of(
                    table("T_ORDERS", "ID", "ID"),
                    table("T_ADDRESS", "ID", "ID"),
                    table("T_ITEM", "ID", "ID", "ORDER_ID", "ADDRESS_ID"));
            assertEquals(List.of(
                    "T_ITEM.ADDRESS_ID -> T_ADDRESS.ID",
                    "T_ITEM.ORDER_ID -> T_ORDERS.ID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("目标列：没有主键取唯一的单列唯一键；两个单列唯一键、没有唯一键、唯一键未知都放弃")
        void 目标列() {
            ConnectorSchema uniqueOnly = RelationRuleFixtures.schema("M_PRODUCT", "-", List.of("PRODUCT_CODE", "NAME"));
            uniqueOnly.setDetailJson(uniqueOnly.getDetailJson().replace("\"unique_keys\":[]",
                    "\"unique_keys\":[{\"name\":\"uk_code\",\"primary\":false,\"columns\":[\"PRODUCT_CODE\"]}]"));
            ConnectorSchema twoUnique = RelationRuleFixtures.schema("M_OUTLET", "-", List.of("CODE", "NO"));
            twoUnique.setDetailJson(twoUnique.getDetailJson().replace("\"unique_keys\":[]",
                    "\"unique_keys\":[{\"name\":\"uk_a\",\"primary\":false,\"columns\":[\"CODE\"]},"
                            + "{\"name\":\"uk_b\",\"primary\":false,\"columns\":[\"NO\"]}]"));
            List<ConnectorSchema> s = List.of(uniqueOnly, twoUnique,
                    table("M_PLANT", "-", "NAME"),
                    table("M_STORE", null, "ID"),
                    table("T_LINE", "OID", "OID", "PRODUCTID", "OUTLETID", "PLANTID", "STOREID"));
            assertEquals(List.of("T_LINE.PRODUCTID -> M_PRODUCT.PRODUCT_CODE"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("表名后缀：按 _ 切开逐段取后缀，去掉 _、转小写")
        void 后缀() {
            assertEquals(List.of("customercpycodedtl", "cpycodedtl", "eficustomercpycodedtl").stream().sorted().toList(),
                    RelationCandidates.suffixes("EFI_CUSTOMER_CPYCODEDTL").stream().sorted().toList());
        }
    }

    @Nested
    @DisplayName("声明的外键")
    class ForeignKeys {

        private ConnectorSchema withForeignKeys(ConnectorSchema row, String foreignKeysJson) {
            row.setDetailJson(row.getDetailJson().replace("\"unique_keys\":", "\"foreign_keys\":" + foreignKeysJson
                    + ",\"unique_keys\":"));
            return row;
        }

        @Test
        @DisplayName("单列外键进候选、来源 FK；组合外键、指向快照外的表的外键不进")
        void 单列外键() {
            List<ConnectorSchema> s = List.of(
                    table("t_cust", "id", "id"),
                    withForeignKeys(table("t_ord", "id", "id", "buyer", "a", "b", "x"), "["
                            + "{\"name\":\"fk_buyer\",\"columns\":[\"buyer\"],\"ref_table\":\"t_cust\",\"ref_columns\":[\"id\"]},"
                            + "{\"name\":\"fk_ab\",\"columns\":[\"a\",\"b\"],\"ref_table\":\"t_cust\",\"ref_columns\":[\"id\",\"id\"]},"
                            + "{\"name\":\"fk_x\",\"columns\":[\"x\"],\"ref_table\":\"t_gone\",\"ref_columns\":[\"id\"]}]"));

            List<Candidate> out = RelationCandidates.foreignKeys(s);

            assertEquals(List.of("t_ord.buyer -> t_cust.id"), lines(out));
            assertEquals(RelationCandidates.ORIGIN_FK, out.get(0).origin());
        }

        @Test
        @DisplayName("★ 同一列既有外键又有命名规律：外键优先")
        void 外键优先() {
            List<ConnectorSchema> s = List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("M_PARTNER", "OID", "OID"),
                    withForeignKeys(table("T_ORDER", "OID", "OID", "VENDORID"),
                            "[{\"name\":\"fk\",\"columns\":[\"VENDORID\"],\"ref_table\":\"M_PARTNER\",\"ref_columns\":[\"OID\"]}]"));

            List<Candidate> out = RelationCandidates.of(s);

            assertEquals(List.of("T_ORDER.VENDORID -> M_PARTNER.OID"), lines(out));
            assertEquals(RelationCandidates.ORIGIN_FK, out.get(0).origin());
        }

        @Test
        @DisplayName("快照里没有外键信息（旧快照、读不到）：没有外键候选，命名规则照常")
        void 没有外键信息() {
            List<ConnectorSchema> s = new ArrayList<>(List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "VENDORID")));
            assertEquals(List.of(), RelationCandidates.foreignKeys(s));
            assertEquals(List.of("T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.of(s)));
        }
    }

    @Test
    @DisplayName("单张表的快照 JSON 坏了：只有它没有候选，别的表照常")
    void 坏快照() {
        ConnectorSchema broken = table("T_BAD", "OID", "OID", "VENDORID");
        broken.setDetailJson("{not json");
        List<ConnectorSchema> s = List.of(broken,
                table("M_VENDOR", "OID", "OID"),
                table("T_ORDER", "OID", "OID", "VENDORID"));
        assertEquals(List.of("T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.of(s)));
    }

    @Test
    @DisplayName("输出顺序确定：与快照行的顺序无关")
    void 顺序确定() {
        List<ConnectorSchema> s = parse(RelationRuleFixtures.ERP_B);
        List<ConnectorSchema> reversed = new ArrayList<>(s);
        Collections.reverse(reversed);
        assertEquals(lines(RelationCandidates.of(s)), lines(RelationCandidates.of(reversed)));
    }
}

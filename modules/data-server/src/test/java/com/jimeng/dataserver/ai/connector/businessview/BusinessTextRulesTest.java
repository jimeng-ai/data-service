package com.jimeng.dataserver.ai.connector.businessview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessTextRulesTest {

    /** 一个系统：表 T_ORDER / 采购单（中文表名）/ ERP；列 VENDORID、NAME、ID、ORDER_NO。 */
    private static final Set<String> IDS = BusinessTextRules.identifiers(
            List.of("T_ORDER", "采购单", "ERP"), List.of("VENDORID", "NAME", "ID", "ORDER_NO"));

    @Test
    @DisplayName("物理标识符：全部 ASCII 表名 + 长度 ≥ 4 的 ASCII 列名，小写；中文表名本身就是业务叫法，不算")
    void 标识符() {
        assertEquals(Set.of("t_order", "erp", "vendorid", "name", "order_no"), IDS);
    }

    @Test
    @DisplayName("合格的业务文字")
    void 合格() {
        assertNull(BusinessTextRules.problem("采购订单明细", BusinessTextRules.NAME_MAX, IDS));
        assertNull(BusinessTextRules.problem("一张订单可能有多条明细，记录下单客户与金额。", BusinessTextRules.SUMMARY_MAX, IDS));
        assertNull(BusinessTextRules.problem("采购单", BusinessTextRules.NAME_MAX, IDS), "中文表名不是代码");
    }

    @Test
    @DisplayName("空白不合格")
    void 空白() {
        assertNotNull(BusinessTextRules.problem("  ", BusinessTextRules.NAME_MAX, IDS));
        assertNotNull(BusinessTextRules.problem(null, BusinessTextRules.NAME_MAX, IDS));
    }

    @Test
    @DisplayName("按字数算长度（中文一个字算一个）")
    void 长度() {
        assertNull(BusinessTextRules.problem("一二三四五六七八九十一二", 12, IDS));
        String problem = BusinessTextRules.problem("一二三四五六七八九十一二三", 12, IDS);
        assertNotNull(problem);
        assertTrue(problem.contains("12"), problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"疑似供应商", "未经核实的订单", "按采样结果", "估算的行数", "按 innodb 统计", "推测是客户",
            "这是语义层里的对象", "订单主键", "外键指向客户", "客户字段"})
    @DisplayName("含附录 B 的禁用词不合格（InnoDB 不分大小写）")
    void 禁用词(String text) {
        assertNotNull(BusinessTextRules.problem(text, 50, IDS), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"按 VENDORID 关联", "见 t_order 表", "客户 Name", "(ORDER_NO)", "ERP里的订单"})
    @DisplayName("含本系统的物理表名或长度 ≥ 4 的列名不合格（按完整单词、不分大小写）")
    void 代码(String text) {
        String problem = BusinessTextRules.problem(text, 50, IDS);
        assertNotNull(problem, text);
        assertTrue(problem.contains("代码"), problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"身份证 ID", "VENDORIDS 之外", "名字 names"})
    @DisplayName("不是完整单词、或列名不足 4 个字符：不算代码")
    void 不算代码(String text) {
        assertNull(BusinessTextRules.problem(text, 50, IDS), text);
    }

    @Test
    @DisplayName("兜底用的注释：不夹代码、不带禁用词、不为空才用；不看字数")
    void 兜底注释() {
        assertTrue(BusinessTextRules.fitsFallback("供应商", IDS));
        assertTrue(BusinessTextRules.fitsFallback("这是一段超过五十个字的客户注释，说明这张表记录了每一张采购订单的抬头信息以及审批流转的全部状态和时间点", IDS));
        assertFalse(BusinessTextRules.fitsFallback("供应商ID（VENDORID）", IDS));
        assertFalse(BusinessTextRules.fitsFallback("估算金额", IDS));
        assertFalse(BusinessTextRules.fitsFallback("  ", IDS));
        assertFalse(BusinessTextRules.fitsFallback(null, IDS));
    }
}

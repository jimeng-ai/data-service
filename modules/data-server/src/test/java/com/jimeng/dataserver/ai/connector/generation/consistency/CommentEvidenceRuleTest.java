package com.jimeng.dataserver.ai.connector.generation.consistency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.field;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.object;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 5 {@link CommentEvidenceRule}：evidence=COMMENT 时对应注释必须非空。
 *
 * <p>最容易漏的是表注释：MySQL 快照的表注释末尾带着平台追加的「（约 N 行，InnoDB 估算值……）」，
 * 一张客户根本没写注释的表在快照里看起来是「有注释」的。{@code t_rowonly} 就是这种表。
 */
class CommentEvidenceRuleTest {

    private final CommentEvidenceRule rule = new CommentEvidenceRule();

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("注释在就通过；不是 COMMENT 的依据不管；关系两端有一端有注释即可")
        void 通过() {
            assertTrue(run(rule, field(0, "t_ord", "amt", "gloss", "金额", "evidence", "COMMENT")).isEmpty());
            assertTrue(run(rule, field(0, "t_ord", "cust_id", "gloss", "客户", "evidence", "NAME")).isEmpty(),
                    "NAME 不要求注释");
            assertTrue(run(rule, object("t_cust", "gloss", "客户表", "evidence", "COMMENT")).isEmpty());
            assertTrue(run(rule, object("t_ord", "gloss", "订单", "evidence", "COMMENT")).isEmpty(),
                    "表注释去掉估算行数后仍有「订单主表」");
            assertTrue(run(rule, join(0, "cust_id", "t_cust", "id", "evidence", "COMMENT")).isEmpty(),
                    "左列没注释、右列 t_cust.id 有「客户主键」：至少一端即可");
        }

        @Test
        @DisplayName("★ 字段、表用途、关系三种都能退回，文案点名是哪一条并给出改标 NAME / DATA 的出路")
        void 退回且原因文案可读() {
            List<RuleViolation> f = run(rule, field(0, "t_ord", "cust_id", "gloss", "客户 id", "evidence", "COMMENT"));
            assertEquals(1, f.size());
            assertEquals(RuleViolation.Severity.REJECT, f.get(0).severity());
            String m = f.get(0).message();
            assertTrue(m.contains("字段 t_ord.cust_id 标了 evidence=COMMENT，但快照里对应的注释是空的"), m);
            assertTrue(m.contains("依据若是名字，请改标 NAME；若是类型或约束，改标 DATA；否则不要提交"), m);

            List<RuleViolation> o = run(rule, object("t_shop", "gloss", "门店表", "evidence", "COMMENT"));
            assertEquals(1, o.size());
            assertTrue(o.get(0).message().startsWith("表 t_shop 的用途 标了 evidence=COMMENT"), o.get(0).message());

            List<RuleViolation> j = run(rule, join(0, "shop_code", "t_shop", "code", "evidence", "COMMENT"));
            assertEquals(1, j.size(), "两端列注释都空");
            assertTrue(j.get(0).message().startsWith("关系 t_ord.shop_code → t_shop.code 标了 evidence=COMMENT"));
        }

        @Test
        @DisplayName("★ 只有估算行数的表注释算空；纯空白注释算空；evidence 小写照样归一；名字对不上不在这里退回")
        void 大小写与反引号边界() {
            assertEquals(1, run(rule, object("t_rowonly", "gloss", "x", "evidence", "COMMENT")).size(),
                    "（约 5 行，InnoDB 估算值……）是平台写的，不是客户的注释");
            assertEquals(1, run(rule, field(0, "t_ord", "created_at", "gloss", "创建时间", "evidence", "COMMENT")).size(),
                    "注释是两个空格");
            assertEquals(1, run(rule, field(0, "t_ord", "cust_id", "gloss", "客户", "evidence", "comment")).size(),
                    "evidence 小写归一成 COMMENT");
            assertTrue(run(rule, field(0, "t_ord", "AMT", "gloss", "金额", "evidence", "COMMENT")).isEmpty(),
                    "列名大小写对不上：toRows 报 NAME_NOT_IN_SNAPSHOT，这里不重复");
            assertTrue(run(rule, object("`t_shop`", "gloss", "门店", "evidence", "COMMENT")).isEmpty());
        }
    }
}

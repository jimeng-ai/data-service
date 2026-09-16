package com.jimeng.dataserver.ai.connector.generation.consistency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.col;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ctx;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.key;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.run;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 4 {@link JoinCompositeMemberRule}：「1」端只是组合唯一键的一员时，不能当单列关系提交。
 *
 * <p>钉「看哪一端」：没写基数按右端看（这是与规则 3 不同的地方——规则 3 没写基数不判），N:N 没有「1」端。
 */
class JoinCompositeMemberRuleTest {

    private final JoinCompositeMemberRule rule = new JoinCompositeMemberRule();

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("指向单列唯一键、指向主键、N:N、唯一键未知、1:N 时右端是组合键成员（右端不是「1」端）：都通过")
        void 通过() {
            assertTrue(run(rule, join(0, "shop_code", "t_cust", "code")).isEmpty(), "uk_code(code) 单列唯一");
            assertTrue(run(rule, join(0, "cust_id", "t_shop", "id", "cardinality", "N:1")).isEmpty());
            assertTrue(run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "N:N")).isEmpty());
            assertTrue(run(rule, join(0, "shop_code", "t_legacy", "ref")).isEmpty(), "唯一键未知不判组合键");
            assertTrue(run(rule, join(0, "id", "t_shop", "code", "cardinality", "1:N")).isEmpty());
        }

        @Test
        @DisplayName("★ 没写基数按右端看：t_shop.code 只是 (tenant_id, code) 的一员，退回并引导写进本表列的字段说明")
        void 退回且原因文案可读() {
            List<RuleViolation> vs = run(rule, join(0, "shop_code", "t_shop", "code"));
            assertEquals(1, vs.size());
            assertEquals(RuleViolation.Severity.REJECT, vs.get(0).severity());
            String m = vs.get(0).message();
            assertTrue(m.contains("t_shop.code 只是组合唯一键 (tenant_id, code) 的一员"), m);
            assertTrue(m.contains("单独用这一列 join 会一行连出多行，金额被放大且不报错"), m);
            assertTrue(m.contains("写进 t_ord.shop_code 的字段说明，说明要同时匹配 tenant_id 和 code"), m);

            assertEquals(1, run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "N:1")).size());

            RuleContext three = ctx(List.of(
                    table("t_ord", null, List.of(key("PRIMARY", true, "id")), col("line_no", "int", null)),
                    table("t_line", null, List.of(key("PRIMARY", true, "tenant_id", "order_id", "line_no")),
                            col("tenant_id", "varchar(64)", null), col("order_id", "bigint", null), col("line_no", "int", null))),
                    List.of());
            List<RuleViolation> v3 = rule.check(join(0, "line_no", "t_line", "line_no"), three);
            assertEquals(1, v3.size());
            assertTrue(v3.get(0).message().contains("同时匹配 tenant_id、order_id 和 line_no"), v3.get(0).message());
        }

        @Test
        @DisplayName("基数小写照样认端；唯一键列名大小写不同照样认成员；1:1 两端都看；端点对不上不重复退回")
        void 大小写与反引号边界() {
            assertEquals(1, run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "n:1")).size());

            RuleContext upperKey = ctx(List.of(
                    table("t_ord", null, List.of(key("PRIMARY", true, "id")), col("shop_code", "varchar(32)", null)),
                    table("t_shop", null, List.of(key("uk", false, "TENANT_ID", "CODE")),
                            col("tenant_id", "varchar(64)", null), col("code", "varchar(32)", null))), List.of());
            List<RuleViolation> vs = rule.check(join(0, "shop_code", "t_shop", "code"), upperKey);
            assertEquals(1, vs.size(), "compositeKeyFor 按列名大小写不敏感判成员");
            assertTrue(vs.get(0).message().contains("(TENANT_ID, CODE)"), "组合键按快照里的写法原样列出：" + vs.get(0).message());

            assertTrue(run(rule, join(0, "shop_code", "`t_shop`", "code")).isEmpty());
        }
    }
}

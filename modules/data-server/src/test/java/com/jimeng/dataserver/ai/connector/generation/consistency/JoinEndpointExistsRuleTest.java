package com.jimeng.dataserver.ai.connector.generation.consistency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 1 {@link JoinEndpointExistsRule}：关系两端原样存在、目标表有列结构。
 *
 * <p>toRows 对同样的输入也会丢，但只会说「名字对不上结构」。这条规则的价值全在文案：是哪一端、快照里怎么写、
 * 还是目标表根本没有列——agent 要据此决定是改拼写还是换目标，所以文案断言是这一组的主体。
 */
class JoinEndpointExistsRuleTest {

    private final JoinEndpointExistsRule rule = new JoinEndpointExistsRule();

    private static void assertOneReject(List<RuleViolation> vs, String... fragments) {
        assertEquals(1, vs.size(), "期望恰好一条违规，实际 " + vs);
        assertEquals(RuleViolation.Severity.REJECT, vs.get(0).severity());
        for (String f : fragments) {
            assertTrue(vs.get(0).message().contains(f), "文案里没有「" + f + "」：" + vs.get(0).message());
        }
    }

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("两端原样存在即通过；缺必填项不在这里重复退回（归 toRows 的 MISSING_REQUIRED）")
        void 通过() {
            assertTrue(run(rule, join(0, "cust_id", "t_cust", "id")).isEmpty());
            assertTrue(run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "N:1")).isEmpty());
            assertTrue(run(rule, join(0, "cust_id", "t_cust", null)).isEmpty());
            assertTrue(run(rule, join(0, null, "t_cust", "id")).isEmpty());
        }

        @Test
        @DisplayName("★ 四种对不上各有各的话：右列没有、右表没有、右表只有表名、左列没有；两端都错时各报一条")
        void 退回且原因文案可读() {
            assertOneReject(run(rule, join(0, "cust_id", "t_cust", "nope")),
                    "关系 t_ord.cust_id → t_cust.nope 被退回", "快照里没有列 t_cust.nope", "get_table_metadata 查看 t_cust 的列");
            assertOneReject(run(rule, join(0, "cust_id", "t_ghost", "id")),
                    "快照里没有表 t_ghost", "list_tables");
            assertOneReject(run(rule, join(0, "cust_id", "t_nocols", "id")),
                    "t_nocols 只有表名，没有列结构，不能作为关系目标");
            assertOneReject(run(rule, join(0, "customer", "t_cust", "id")),
                    "快照里没有列 t_ord.customer", "get_table_metadata 查看 t_ord 的列");

            List<RuleViolation> both = run(rule, join(0, "customer", "t_cust", "nope"));
            assertEquals(2, both.size(), "左右两端各自的问题都要一次说全：" + both);
            // 完全对不上时不瞎给建议
            assertTrue(both.stream().noneMatch(v -> v.message().contains("快照里的写法是")));
        }

        @Test
        @DisplayName("★ 只差大小写或反引号：仍然退回（判定大小写敏感，与 toRows 一致），但文案给出快照里的写法")
        void 大小写与反引号边界() {
            assertOneReject(run(rule, join(0, "cust_id", "t_cust", "ID")),
                    "快照里没有列 t_cust.ID", "快照里的写法是 t_cust.id，表名列名必须原样照抄");
            assertOneReject(run(rule, join(0, "cust_id", "`t_cust`", "id")),
                    "快照里没有表 `t_cust`", "快照里的写法是 t_cust");
            assertOneReject(run(rule, join(0, "cust_id", "T_CUST", "id")),
                    "快照里的写法是 t_cust");
            assertOneReject(run(rule, join(0, "`CUST_ID`", "t_cust", "id")),
                    "快照里没有列 t_ord.`CUST_ID`", "快照里的写法是 t_ord.cust_id");
        }
    }
}

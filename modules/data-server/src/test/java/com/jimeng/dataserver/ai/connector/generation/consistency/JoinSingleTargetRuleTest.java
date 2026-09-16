package com.jimeng.dataserver.ai.connector.generation.consistency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ctx;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 7 {@link JoinSingleTargetRule}（批内规则）：同一次提交里一列只能指向一个目标，冲突的条目一起退回。
 *
 * <p>「一起退回」是这条规则与 toRows 静默合并的根本区别：每个冲突条目在自己被检查时都要命中，
 * 否则被留下的那一条就等于服务端替 agent 做了选择。
 */
class JoinSingleTargetRuleTest {

    private final JoinSingleTargetRule rule = new JoinSingleTargetRule();

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("一列一个目标、不同列各有目标、同一目标写了两遍、冲突的另一条标了 GUESS：都通过")
        void 通过() {
            ProposedEntry a = join(0, "cust_id", "t_cust", "id");
            ProposedEntry b = join(1, "shop_code", "t_shop", "code");
            RuleContext ctx = ctx(a, b);
            assertTrue(rule.check(a, ctx).isEmpty());
            assertTrue(rule.check(b, ctx).isEmpty());

            ProposedEntry dup = join(2, "cust_id", "t_cust", "id", "confidence", 90);
            assertTrue(rule.check(a, ctx(a, dup)).isEmpty(), "同一目标写两遍是重复，toRows 会合并，不是冲突");

            ProposedEntry guess = join(3, "cust_id", "t_shop", "id", "evidence", "GUESS");
            assertTrue(rule.check(a, ctx(a, guess)).isEmpty(), "GUESS 条目会被 toRows 丢掉，不与谁撞");
        }

        @Test
        @DisplayName("★ 同一列指向两处：两条都退回，文案列出全部目标；左表有判别列时改为多态外键提示")
        void 退回且原因文案可读() {
            ProposedEntry a = join(0, "cust_id", "t_cust", "id", "evidence", "NAME");
            ProposedEntry b = join(1, "cust_id", "t_shop", "id", "evidence", "NAME");
            ProposedEntry c = join(2, "shop_code", "t_shop", "code");
            RuleContext ctx = ctx(a, b, c);

            List<RuleViolation> va = rule.check(a, ctx);
            List<RuleViolation> vb = rule.check(b, ctx);
            assertEquals(1, va.size());
            assertEquals(1, vb.size(), "冲突的条目一起退回，不替 agent 挑一条");
            assertTrue(rule.check(c, ctx).isEmpty(), "别的列不受牵连");
            assertEquals(RuleViolation.Severity.REJECT, va.get(0).severity());
            String m = va.get(0).message();
            assertTrue(m.contains("t_ord.cust_id 同时指向了 t_cust.id 和 t_shop.id。一列只能有一条关系"), m);
            assertTrue(m.contains("否则只保留依据最强的那一条重新提交"), m);
            assertTrue(vb.get(0).message().contains("同时指向了 t_shop.id 和 t_cust.id"), "每条以自己的目标开头：" + vb.get(0).message());

            ProposedEntry p1 = join(0, "buyer_id", "t_cust", "id");
            ProposedEntry p2 = join(1, "buyer_id", "t_shop", "id");
            ProposedEntry p3 = join(2, "buyer_id", "t_legacy", "id");
            List<RuleViolation> poly = rule.check(p1, ctx(p1, p2, p3));
            assertEquals(1, poly.size());
            String pm = poly.get(0).message();
            assertTrue(pm.contains("t_ord.buyer_id 同时指向了 t_cust.id、t_shop.id 和 t_legacy.id，而本表有判别列 buyer_type"), pm);
            assertTrue(pm.contains("这是多态外键"), pm);
            assertTrue(pm.contains("不要提交关系，写进 t_ord.buyer_id 的字段说明"), pm);
        }

        @Test
        @DisplayName("左列按 ciFold 折叠比较（大小写不同也算同一列）；目标只差大小写不算冲突；反引号不归一")
        void 大小写与反引号边界() {
            ProposedEntry lower = join(0, "cust_id", "t_cust", "id");
            ProposedEntry upper = join(1, "CUST_ID", "t_shop", "id");
            assertEquals(1, rule.check(lower, ctx(lower, upper)).size(), "cust_id 与 CUST_ID 在唯一键里是同一个键");

            ProposedEntry sameTarget = join(1, "cust_id", "T_CUST", "ID");
            assertTrue(rule.check(lower, ctx(lower, sameTarget)).isEmpty(), "t_cust.id 与 T_CUST.ID 折叠后相同，是重复不是冲突");

            ProposedEntry quoted = join(1, "cust_id", "`t_cust`", "id");
            assertEquals(1, rule.check(lower, ctx(lower, quoted)).size(),
                    "反引号不归一：算两个目标（反引号写法本身会被规则 1 退回）");

            ProposedEntry guessUpper = join(1, "Cust_Id", "t_shop", "id", "evidence", "guess");
            assertTrue(rule.check(lower, ctx(lower, guessUpper)).isEmpty(), "evidence 小写 guess 归一后仍是 GUESS");
        }
    }
}

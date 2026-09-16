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
 * 规则 2 {@link JoinTypeCompatibleRule}：关系两端的类型要兼容。
 *
 * <p>钉的是兼容表本身：哪些族互通、字符对整数只认 COMMENT、未知一律放行。兼容表多放一种组合，
 * 就多一类「看名字像」的关系被当成事实注入；少放一种，就会退回一批正确的关系而 agent 无从改起。
 */
class JoinTypeCompatibleRuleTest {

    private final JoinTypeCompatibleRule rule = new JoinTypeCompatibleRule();

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("同族、整数对定点、整数对 bit、任一端类型未知、字符对整数且 evidence=COMMENT：都通过")
        void 通过() {
            assertTrue(run(rule, join(0, "cust_id", "t_cust", "id")).isEmpty(), "bigint → bigint");
            assertTrue(run(rule, join(0, "id", "t_cust", "level")).isEmpty(), "bigint unsigned → int");
            assertTrue(run(rule, join(0, "amt", "t_cust", "level")).isEmpty(), "decimal → int");
            assertTrue(run(rule, join(0, "flag", "t_cust", "level")).isEmpty(), "bit → int");
            assertTrue(run(rule, join(0, "shop_code", "t_cust", "code")).isEmpty(), "varchar → varchar");
            assertTrue(run(rule, join(0, "shop_code", "t_cust", "id", "evidence", "COMMENT")).isEmpty(),
                    "varchar → bigint 只在 COMMENT 下放行");

            RuleContext unknownType = ctx(List.of(
                    table("t_ord", null, List.of(key("PRIMARY", true, "id")), col("uid", null, null)),
                    table("t_cust", null, List.of(key("PRIMARY", true, "id")), col("id", "uuid", null))), List.of());
            assertTrue(rule.check(join(0, "uid", "t_cust", "id"), unknownType).isEmpty(),
                    "类型缺失、类型名没见过：放行，不退回一条 agent 无从改对的关系");
        }

        @Test
        @DisplayName("★ 字符对整数（名字像）退回并指明只有注释写明才可提交；时间对整数退回并说连不上")
        void 退回且原因文案可读() {
            List<RuleViolation> vs = run(rule, join(0, "shop_code", "t_cust", "id", "evidence", "NAME"));
            assertEquals(1, vs.size());
            assertEquals(RuleViolation.Severity.REJECT, vs.get(0).severity());
            String m = vs.get(0).message();
            assertTrue(m.contains("关系 t_ord.shop_code（varchar(32)）→ t_cust.id（bigint）类型不兼容"), m);
            assertTrue(m.contains("只有列注释明确写了 t_ord.shop_code 存的是 t_cust.id 时才能提交"), m);
            assertTrue(m.contains("evidence 标 COMMENT 并在 note 引用注释原文"), m);

            List<RuleViolation> temporal = run(rule, join(0, "created_at", "t_cust", "id", "evidence", "COMMENT"));
            assertEquals(1, temporal.size(), "时间对整数：COMMENT 也救不了");
            assertTrue(temporal.get(0).message().contains("t_ord.created_at（datetime）→ t_cust.id（bigint）"));
            assertTrue(temporal.get(0).message().contains("不要提交这条关系"));

            assertEquals(1, run(rule, join(0, "amt", "t_cust", "code")).size(), "decimal → varchar 不在兼容表里");
            assertEquals(1, run(rule, join(0, "flag", "t_ord", "amt")).size(), "bit 只和整数互通，和定点不互通");
        }

        @Test
        @DisplayName("类型串的大小写、长度与 unsigned 不影响归族；evidence 小写照样归一成 COMMENT；缺省按 NAME 不放行字符对整数")
        void 大小写与反引号边界() {
            RuleContext upper = ctx(List.of(
                    table("t_ord", null, List.of(key("PRIMARY", true, "id")),
                            col("cust_no", "VARCHAR(32)", "存的是 t_cust.id"), col("cust_id", "BIGINT(20) UNSIGNED", null)),
                    table("t_cust", null, List.of(key("PRIMARY", true, "id")), col("id", "Int(11)", null))), List.of());
            assertTrue(rule.check(join(0, "cust_id", "t_cust", "id"), upper).isEmpty());
            assertTrue(rule.check(join(0, "cust_no", "t_cust", "id", "evidence", "comment"), upper).isEmpty(),
                    "evidence 小写经 ProposedEntry.of 归一后是 COMMENT");
            assertEquals(1, rule.check(join(0, "cust_no", "t_cust", "id"), upper).size(),
                    "没写 evidence：关系缺省按 NAME（与 toRows 同源），字符对整数不放行");

            // 端点对不上（含反引号写法）是规则 1 的事，这里不重复退回
            assertTrue(run(rule, join(0, "shop_code", "`t_cust`", "id")).isEmpty());

            assertEquals(TypeFamily.FLOAT, TypeFamily.of("double precision"));
            assertEquals(TypeFamily.STRING, TypeFamily.of("enum('a','B')"));
            assertEquals(TypeFamily.JSON, TypeFamily.of("  Json "));
            assertEquals(TypeFamily.INTEGER, TypeFamily.of("tinyint(1)"));
            assertEquals(TypeFamily.SPATIAL, TypeFamily.of("POINT"));
            assertEquals(TypeFamily.UNKNOWN, TypeFamily.of("uuid"));
            assertEquals(TypeFamily.UNKNOWN, TypeFamily.of("   "));
            assertEquals(TypeFamily.UNKNOWN, TypeFamily.of(null));
        }
    }
}

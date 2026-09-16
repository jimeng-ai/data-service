package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.persistence.entity.ConnectorSchema;
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
 * 规则 3 {@link JoinOneSideUniqueRule}：声明了「1」端，那一端就必须是单列主键或单列唯一键。
 *
 * <p>重点是唯一键的三态：未知放行、空列表退回、有键按单列判——把「不知道」当「没有」，旧快照上的 N:1 全都交不上；
 * 反过来，一条指向无键表的 N:1 会被当成可以自动接进 join path 的关系。
 */
class JoinOneSideUniqueRuleTest {

    private final JoinOneSideUniqueRule rule = new JoinOneSideUniqueRule();

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("指向单列主键或单列唯一键、N:N、没写或认不出的基数、唯一键未知：都通过")
        void 通过() {
            assertTrue(run(rule, join(0, "cust_id", "t_cust", "id", "cardinality", "N:1")).isEmpty(), "PRIMARY(id)");
            assertTrue(run(rule, join(0, "shop_code", "t_cust", "code", "cardinality", "N:1")).isEmpty(), "uk_code(code)");
            assertTrue(run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "N:N")).isEmpty());
            assertTrue(run(rule, join(0, "shop_code", "t_shop", "code")).isEmpty(), "没写基数不判「1」端");
            assertTrue(run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "many")).isEmpty(),
                    "认不出的基数 toRows 会落成 null，规则按没写处理");
            assertTrue(run(rule, join(0, "shop_code", "t_legacy", "ref", "cardinality", "N:1")).isEmpty(),
                    "旧快照唯一键未知：放行");
            assertTrue(run(rule, join(0, "id", "t_cust", "id", "cardinality", "1:1")).isEmpty(), "两端都是主键的 1:1");
        }

        @Test
        @DisplayName("★ N:1 指向组合键成员、指向无键表、1:N 左端不唯一、1:1 两端都不唯一：退回，括号里的唯一键原样取自渲染文本")
        void 退回且原因文案可读() {
            List<RuleViolation> vs = run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "N:1"));
            assertEquals(1, vs.size());
            assertEquals(RuleViolation.Severity.REJECT, vs.get(0).severity());
            String m = vs.get(0).message();
            assertTrue(m.contains("关系 t_ord.shop_code → t_shop.code 声明为 N:1，但 t_shop.code 不是单列主键或唯一键"), m);
            assertTrue(m.contains("（t_shop 的唯一键：PRIMARY(id)；uk_tenant_code(tenant_id, code)）"), m);
            assertTrue(m.contains("请改为指向 t_shop 的单列唯一键；或把 cardinality 改为 N:N 或不写，并在 note 说明依据"), m);

            List<RuleViolation> nokey = run(rule, join(0, "cust_id", "t_nokey", "id", "cardinality", "N:1"));
            assertEquals(1, nokey.size(), "唯一键是空列表 = 确实没有：退回");
            assertTrue(nokey.get(0).message().contains("（t_nokey：本表没有主键或唯一键）"), nokey.get(0).message());

            List<RuleViolation> left = run(rule, join(0, "cust_id", "t_cust", "id", "cardinality", "1:N"));
            assertEquals(1, left.size(), "1:N 看左端：t_ord.cust_id 不唯一");
            assertTrue(left.get(0).message().contains("「1」端在左边，但 t_ord.cust_id 不是单列主键或唯一键"), left.get(0).message());
            assertTrue(left.get(0).message().contains("（t_ord 的唯一键：PRIMARY(id)）"), left.get(0).message());

            assertEquals(2, run(rule, join(0, "cust_id", "t_nokey", "val", "cardinality", "1:1")).size(),
                    "1:1 两端各自不唯一，各报一条");

            // 括号里那段文本的来源：SemanticTableRenderer 的唯一键三态，与 table-metadata 给模型看的那一行同一段代码
            RuleContext ctx = ctx();
            assertEquals("PRIMARY(id)；uk_tenant_code(tenant_id, code)", ctx.uniqueKeyTextOf("t_shop"));
            assertEquals(SemanticTableRenderer.UNIQUE_KEYS_NONE, ctx.uniqueKeyTextOf("t_nokey"));
            assertEquals(SemanticTableRenderer.UNIQUE_KEYS_UNKNOWN, ctx.uniqueKeyTextOf("t_legacy"));
            assertEquals(SemanticTableRenderer.UNIQUE_KEYS_UNKNOWN, ctx.uniqueKeyTextOf("t_ghost"), "快照里没有的表按未知说");
            List<ConnectorSchema> snapshot = RuleFixtures.shop();
            assertEquals("唯一键（主键在前）：PRIMARY(id)；uk_code(code)", SemanticTableRenderer.uniqueKeyLine(snapshot.get(1)));
            assertEquals("本表没有主键或唯一键", SemanticTableRenderer.uniqueKeyLine(snapshot.get(4)));
            assertEquals("唯一键未知（旧快照，未读到索引）", SemanticTableRenderer.uniqueKeyLine(snapshot.get(3)));
        }

        @Test
        @DisplayName("基数写小写照样认；唯一键里的列名与列只差大小写算同一列；端点对不上不重复退回")
        void 大小写与反引号边界() {
            assertEquals(1, run(rule, join(0, "shop_code", "t_shop", "code", "cardinality", "n:1")).size(),
                    "n:1 归一成 N:1，与 toRows 同口径");

            RuleContext mixed = ctx(List.of(
                    table("t_ord", null, List.of(key("PRIMARY", true, "id")), col("cust_id", "bigint", null)),
                    table("t_cust", null, List.of(key("PRIMARY", true, "ID")), col("id", "bigint", null))), List.of());
            assertTrue(rule.check(join(0, "cust_id", "t_cust", "id", "cardinality", "N:1"), mixed).isEmpty(),
                    "唯一键记成 ID、列是 id：MySQL 列名大小写不敏感，与 compositeKeyFor 同口径");

            assertTrue(run(rule, join(0, "shop_code", "`t_shop`", "code", "cardinality", "N:1")).isEmpty(),
                    "反引号写法对不上快照，是规则 1 的事");
        }
    }
}

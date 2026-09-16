package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ambiguity;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.shop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 8 {@link CaveatAnsweredTermRule}：已答口径不再作为待确认问题提出。严重级别 DROP。
 *
 * <p>已答集合用生产那条 {@link SemanticRowAssembler#answeredTerms} 从 METRIC 行现算，不手写折叠后的字符串：
 * 规则与 toRows 的折叠口径必须是同一个，手写等于把口径复制了一份。
 */
class CaveatAnsweredTermRuleTest {

    private final CaveatAnsweredTermRule rule = new CaveatAnsweredTermRule();

    private static RuleContext answered(String... terms) {
        List<ConnectorSemantic> metrics = Arrays.stream(terms).map(t -> {
            ConnectorSemantic m = new ConnectorSemantic();
            m.setScope(ConnectorSemanticService.SCOPE_METRIC);
            m.setStatus(ConnectorSemanticService.ST_CONFIRMED);
            m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
            m.setTerm(t);
            return m;
        }).toList();
        Set<String> folded = SemanticRowAssembler.answeredTerms(metrics);
        return RuleContext.of("t_ord", shop(), folded, List.of());
    }

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("没答过的口径照常提出")
        void 通过() {
            assertTrue(rule.check(ambiguity(0, "活跃用户"), answered("销售额")).isEmpty());
            assertTrue(rule.check(ambiguity(0, "销售额"), answered()).isEmpty());
        }

        @Test
        @DisplayName("★ 已答口径：DROP（不需要重提），文案点名口径并说明原因")
        void 退回且原因文案可读() {
            List<RuleViolation> vs = rule.check(ambiguity(3, "销售额"), answered("销售额", "GMV"));
            assertEquals(1, vs.size());
            assertEquals(RuleViolation.Severity.DROP, vs.get(0).severity(), "按规则不入库，不是可以修正的错");
            assertEquals(SemanticRowAssembler.STATUS_DROPPED, vs.get(0).severity().status());
            assertEquals("口径『销售额』已由业务方在对话里回答，不再作为待确认问题提出，不需要重提。", vs.get(0).message());
        }

        @Test
        @DisplayName("大小写与首尾空白按 toRows 的折叠口径视为同一口径；反引号与全角字母不归一")
        void 大小写与反引号边界() {
            RuleContext ctx = answered("GMV");
            assertEquals(1, rule.check(ambiguity(0, " gmv "), ctx).size());
            assertTrue(rule.check(ambiguity(0, "`GMV`"), ctx).isEmpty(), "反引号是词条的一部分");
            assertTrue(rule.check(ambiguity(0, "ＧＭＶ"), ctx).isEmpty(), "全角字母不折叠，与 toRows 一致");
        }
    }
}

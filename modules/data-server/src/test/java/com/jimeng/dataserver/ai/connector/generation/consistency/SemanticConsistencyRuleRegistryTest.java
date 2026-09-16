package com.jimeng.dataserver.ai.connector.generation.consistency;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry.Finding;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry.RuleSummary;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ambiguity;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ctx;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.field;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SemanticConsistencyRuleRegistry}：一致性规则的收集、排序、分派与异常隔离（设计文档 7.9）。
 *
 * <p>这里钉的是「加一条规则只加一个类」这件事真的成立：新类被 Spring 自动收进来、按 {@code @Order} 插到正确的位置、
 * 摘要自动出现在 run-scope 里；以及一条写坏的规则不会拖垮整次提交，也不会静默放行它本该审的条目。
 */
class SemanticConsistencyRuleRegistryTest {

    /** 按设计文档 7.9 规则清单的顺序。 */
    private static final List<String> PRODUCTION_CODES = List.of(
            "JOIN_ENDPOINT_MISSING", "JOIN_TYPE_INCOMPATIBLE", "JOIN_ONE_SIDE_NOT_UNIQUE", "JOIN_COMPOSITE_MEMBER",
            "EVIDENCE_COMMENT_WITHOUT_COMMENT", "GLOSS_CODE_VALUE_NOT_IN_COMMENT", "JOIN_MULTIPLE_TARGETS",
            "CAVEAT_TERM_ANSWERED");

    private static List<SemanticConsistencyRule> productionRules() {
        return List.of(new JoinEndpointExistsRule(), new JoinTypeCompatibleRule(), new JoinOneSideUniqueRule(),
                new JoinCompositeMemberRule(), new CommentEvidenceRule(), new CodeValueMappingRule(),
                new JoinSingleTargetRule(), new CaveatAnsweredTermRule());
    }

    private static List<String> codes(List<? extends SemanticConsistencyRule> rules) {
        return rules.stream().map(SemanticConsistencyRule::code).toList();
    }

    // ================================================================ 测试用的假规则（刻意不加 @Component，免得被包扫描收进别的用例）

    /** 按 kinds 适用、执行时把自己的码记进共享列表，返回预设的违规或抛异常。 */
    abstract static class Probe implements SemanticConsistencyRule {
        final List<String> trace;
        final String code;
        final RuleViolation result;
        final RuntimeException boom;

        Probe(List<String> trace, String code, RuleViolation result, RuntimeException boom) {
            this.trace = trace;
            this.code = code;
            this.result = result;
            this.boom = boom;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public Set<String> kinds() {
            return Set.of(SemanticRowAssembler.KIND_FIELDS);
        }

        @Override
        public String summary() {
            return "探针 " + code;
        }

        @Override
        public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
            trace.add(code);
            if (boom != null) {
                throw boom;
            }
            return result == null ? List.of() : List.of(result);
        }
    }

    @Order(1)
    static class First extends Probe {
        First(List<String> trace, RuleViolation result, RuntimeException boom) {
            super(trace, "P_FIRST", result, boom);
        }
    }

    @Order(2)
    static class Second extends Probe {
        Second(List<String> trace, RuleViolation result, RuntimeException boom) {
            super(trace, "P_SECOND", result, boom);
        }
    }

    @Order(3)
    static class Third extends Probe {
        Third(List<String> trace, RuleViolation result, RuntimeException boom) {
            super(trace, "P_THIRD", result, boom);
        }
    }

    /** 模拟「新加的一条规则」：排在组合键成员（400）与注释依据（500）之间。不加 @Component，由用例显式注册成 bean。 */
    @Order(450)
    static class NewlyAddedRule implements SemanticConsistencyRule {
        @Override
        public String code() {
            return "NEWLY_ADDED";
        }

        @Override
        public Set<String> kinds() {
            return Set.of(SemanticRowAssembler.KIND_JOINS);
        }

        @Override
        public String summary() {
            return "新加的规则";
        }

        @Override
        public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
            return List.of(RuleViolation.reject("新规则判的"));
        }
    }

    // ================================================================ 用例

    @Nested
    @DisplayName("收集与排序")
    class Ordering {

        @Test
        @DisplayName("★ 注入顺序打乱也按 @Order 执行；8 条生产规则的顺序与设计 7.9 一致；一个条目的全部违规按规则顺序排列")
        void 全部规则按Order执行() {
            List<String> trace = new ArrayList<>();
            SemanticConsistencyRuleRegistry probes = new SemanticConsistencyRuleRegistry(List.of(
                    new Third(trace, RuleViolation.reject("3"), null),
                    new First(trace, RuleViolation.reject("1"), null),
                    new Second(trace, RuleViolation.drop("2"), null)));
            List<Finding> fs = probes.check(field(0, "t_ord", "amt", "gloss", "x", "evidence", "COMMENT"), ctx());
            assertEquals(List.of("P_FIRST", "P_SECOND", "P_THIRD"), trace, "执行顺序");
            assertEquals(List.of("P_FIRST", "P_SECOND", "P_THIRD"), fs.stream().map(Finding::code).toList(), "结果顺序");
            assertEquals(List.of("rejected", "dropped", "rejected"), fs.stream().map(Finding::status).toList());

            List<SemanticConsistencyRule> shuffled = new ArrayList<>(productionRules());
            Collections.reverse(shuffled);
            SemanticConsistencyRuleRegistry registry = new SemanticConsistencyRuleRegistry(shuffled);
            assertEquals(PRODUCTION_CODES, codes(registry.rules()));

            // 一条同时违反五条规则的关系：datetime → int 类型不兼容、声明 N:1 指向非唯一列、标了 COMMENT 却两端无注释、
            // note 里编了码值、同一列还指向了另一张表。收集全部，不在第一条就停。
            ProposedEntry bad = join(0, "created_at", "t_cust", "level",
                    "cardinality", "N:1", "evidence", "COMMENT", "note", "1=普通客户");
            ProposedEntry other = join(1, "created_at", "t_shop", "code");
            List<Finding> all = registry.check(bad, ctx(bad, other));
            assertEquals(List.of("JOIN_TYPE_INCOMPATIBLE", "JOIN_ONE_SIDE_NOT_UNIQUE",
                            "EVIDENCE_COMMENT_WITHOUT_COMMENT", "GLOSS_CODE_VALUE_NOT_IN_COMMENT", "JOIN_MULTIPLE_TARGETS"),
                    all.stream().map(Finding::code).toList());
        }

        @Test
        @DisplayName("★ 新规则只加一个 bean：包扫描收到 8 条生产规则，外加的规则被自动注入注册表并插到 @Order 对应的位置")
        void 新规则类被Spring自动收集() {
            try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext()) {
                spring.scan(SemanticConsistencyRuleRegistry.class.getPackageName());
                spring.register(NewlyAddedRule.class);
                spring.refresh();

                SemanticConsistencyRuleRegistry registry = spring.getBean(SemanticConsistencyRuleRegistry.class);
                List<String> expected = new ArrayList<>(PRODUCTION_CODES);
                expected.add(4, "NEWLY_ADDED");
                assertEquals(expected, codes(registry.rules()));
                assertTrue(registry.summaries().stream().anyMatch(s -> "NEWLY_ADDED".equals(s.getCode())),
                        "摘要随规则自动出现，run-scope 不用改");
                assertEquals(9, spring.getBeansOfType(SemanticConsistencyRule.class).size());
            }
        }

        @Test
        @DisplayName("GUESS 条目不跑任何规则；规则只对自己声明的种类生效")
        void GUESS条目不跑规则且按种类分派() {
            SemanticConsistencyRuleRegistry registry = new SemanticConsistencyRuleRegistry(productionRules());
            ProposedEntry guess = join(0, "shop_code", "t_cust", "id", "evidence", "GUESS");
            assertTrue(registry.check(guess, ctx(guess)).isEmpty(), "toRows 会以 EVIDENCE_GUESS 丢掉它，规则不必再判");

            ProposedEntry answered = ambiguity(0, "销售额");
            RuleContext ctx = RuleContext.of("t_ord", RuleFixtures.shop(), Set.of("销售额"), List.of(answered));
            List<Finding> fs = registry.check(answered, ctx);
            assertEquals(List.of("CAVEAT_TERM_ANSWERED"), fs.stream().map(Finding::code).toList(),
                    "ambiguities 只适用规则 8");
            assertEquals("dropped", fs.get(0).status());
        }
    }

    @Nested
    @DisplayName("异常隔离")
    class Isolation {

        private final Logger logger = (Logger) LoggerFactory.getLogger(SemanticConsistencyRuleRegistry.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        @BeforeEach
        void attach() {
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("★ 中间那条规则抛异常：它记 RULE_ERROR（REJECT）并打 ERROR 日志，前后两条照常出结果；异常原文不进给 agent 的文案")
        void 单条规则抛异常记RULE_ERROR且不影响其余规则() {
            List<String> trace = new ArrayList<>();
            SemanticConsistencyRuleRegistry registry = new SemanticConsistencyRuleRegistry(List.of(
                    new First(trace, RuleViolation.reject("第一条的原因"), null),
                    new Second(trace, null, new IllegalStateException("boom-internal-detail")),
                    new Third(trace, RuleViolation.drop("第三条的原因"), null)));

            List<Finding> fs = registry.check(field(2, "t_ord", "amt", "gloss", "x", "evidence", "NAME"), ctx());

            assertEquals(List.of("P_FIRST", "P_SECOND", "P_THIRD"), trace, "抛异常之后后面的规则照跑");
            assertEquals(List.of("P_FIRST", SemanticConsistencyRuleRegistry.CODE_RULE_ERROR, "P_THIRD"),
                    fs.stream().map(Finding::code).toList());
            Finding err = fs.get(1);
            assertEquals(RuleViolation.Severity.REJECT, err.severity(), "出错的规则不能静默放行");
            assertTrue(err.message().contains("P_SECOND"), err.message());
            assertFalse(err.message().contains("boom-internal-detail"), "内部异常文本不发给外部模型：" + err.message());
            assertEquals("第三条的原因", fs.get(2).message());

            ILoggingEvent event = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElse(null);
            assertNotNull(event, "要有一条 ERROR 日志");
            assertTrue(event.getFormattedMessage().contains("rule=P_SECOND"), event.getFormattedMessage());
            assertTrue(event.getFormattedMessage().contains("entry=fields[2]"), event.getFormattedMessage());
            assertNotNull(event.getThrowableProxy(), "日志带异常栈，便于排查");
        }
    }

    @Nested
    @DisplayName("摘要与启动期校验")
    class Summaries {

        @Test
        @DisplayName("★ run-scope 的 rules 与规则一一对应、顺序一致；同码、种类写错、没有摘要都在构造时失败")
        void rules摘要与规则一一对应() {
            SemanticConsistencyRuleRegistry registry = new SemanticConsistencyRuleRegistry(productionRules());
            List<RuleSummary> summaries = registry.summaries();
            assertEquals(PRODUCTION_CODES, summaries.stream().map(RuleSummary::getCode).toList());
            Set<String> texts = new HashSet<>();
            for (int i = 0; i < summaries.size(); i++) {
                SemanticConsistencyRule rule = registry.rules().get(i);
                assertEquals(rule.summary(), summaries.get(i).getSummary());
                assertFalse(rule.summary().isBlank());
                assertTrue(texts.add(rule.summary()), "两条规则的摘要一模一样，agent 分不清：" + rule.code());
                assertFalse(rule.kinds().isEmpty());
            }

            List<String> trace = new ArrayList<>();
            IllegalStateException dup = assertThrows(IllegalStateException.class, () -> new SemanticConsistencyRuleRegistry(
                    List.of(new First(trace, null, null), new Second(trace, null, null) {
                        @Override
                        public String code() {
                            return "P_FIRST";
                        }
                    })));
            assertTrue(dup.getMessage().contains("P_FIRST"));

            assertThrows(IllegalStateException.class, () -> new SemanticConsistencyRuleRegistry(List.of(
                    new First(trace, null, null) {
                        @Override
                        public Set<String> kinds() {
                            return Set.of("field");   // 少了一个 s：这类条目会静默不审
                        }
                    })));
            assertThrows(IllegalStateException.class, () -> new SemanticConsistencyRuleRegistry(List.of(
                    new First(trace, null, null) {
                        @Override
                        public String summary() {
                            return " ";
                        }
                    })));
        }
    }
}

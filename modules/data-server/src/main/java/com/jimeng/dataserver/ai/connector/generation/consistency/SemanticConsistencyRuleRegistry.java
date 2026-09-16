package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一致性规则注册表（设计文档 7.9）。注入全部 {@link SemanticConsistencyRule} bean，按 {@code @Order} 排好，
 * 提交流水线的 D 步对每个条目调一次 {@link #check}。
 *
 * <p>形状照抄 {@code ConnectorRegistry}：构造器注入 {@code List<接口>}，<b>启动期</b>校验，配错让应用起不来——
 * 两条规则同码、种类写错一个字母，运行期的表现只会是「某条退回原因对不上规则」或「某类条目静默不审」，
 * 排查成本远大于启动失败。
 *
 * <h3>主流程里没有按规则类型的分支</h3>
 * 加一条规则 = 加一个 {@code @Component} 类。本类只负责排序、按种类分派、贴机器码、隔离异常；
 * 判什么、怎么说，全在规则类里。
 */
@Slf4j
@Component
public class SemanticConsistencyRuleRegistry {

    /** 规则自己抛了异常：这一条记 rejected，不影响其余规则。 */
    public static final String CODE_RULE_ERROR = "RULE_ERROR";

    private static final Set<String> KNOWN_KINDS = Set.of(SemanticRowAssembler.KIND_OBJECTS,
            SemanticRowAssembler.KIND_FIELDS, SemanticRowAssembler.KIND_JOINS, SemanticRowAssembler.KIND_AMBIGUITIES);

    private final List<SemanticConsistencyRule> rules;

    /** 启动时读一次并冻结：规则的种类不该在运行期变化，每个条目都去调一次 kinds() 也没有意义。 */
    private final Map<SemanticConsistencyRule, Set<String>> kindsByRule = new LinkedHashMap<>();

    public SemanticConsistencyRuleRegistry(List<SemanticConsistencyRule> rules) {
        List<SemanticConsistencyRule> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        // Spring 注入的 List 本来就按 @Order 排过；这里再排一次，让手工构造（单测、将来别的装配方式）得到同一个顺序。
        AnnotationAwareOrderComparator.sort(sorted);
        Set<String> codes = new HashSet<>();
        for (SemanticConsistencyRule r : sorted) {
            String name = r.getClass().getName();
            String code = r.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("一致性规则 " + name + " 的 code() 为空——它会写进退回原因与 last_reject_reason，不能为空");
            }
            if (!codes.add(code)) {
                throw new IllegalStateException("一致性规则机器码重复: code=" + code + "，冲突实现之一 " + name
                        + "。同码会让「是哪条规则退回的」变成不确定，必须在启动期解决。");
            }
            Set<String> kinds = r.kinds();
            if (kinds == null || kinds.isEmpty() || !KNOWN_KINDS.containsAll(kinds)) {
                throw new IllegalStateException("一致性规则 " + code + " 的 kinds()=" + kinds + " 不合法，只能取 " + KNOWN_KINDS
                        + " 的非空子集——写错一个字母的后果是这类条目静默不审");
            }
            if (r.summary() == null || r.summary().isBlank()) {
                throw new IllegalStateException("一致性规则 " + code + " 没有 summary()——run-scope 要把它原样告诉 agent");
            }
            kindsByRule.put(r, Set.copyOf(kinds));
        }
        this.rules = List.copyOf(sorted);
        log.info("语义层一致性规则注册表就绪，共 {} 条: {}", this.rules.size(),
                this.rules.stream().map(SemanticConsistencyRule::code).toList());
    }

    /**
     * 对一个条目跑全部适用规则，<b>收集全部违规</b>，按规则的 {@code @Order} 排列。
     *
     * <p>依据归一后是 GUESS 的条目直接返回空：toRows 会以 EVIDENCE_GUESS 把它丢掉，规则再退回只会让 agent
     * 去修一条注定不入库的东西（设计 7.8 的 D 步「对依据标签归一后不是 GUESS 的条目运行」）。
     *
     * <p>单条规则抛异常时这一条记 {@link #CODE_RULE_ERROR}（REJECT）并打 ERROR 日志，其余规则照跑：
     * 一条写坏的规则不能让整张表的提交失败，也不能静默放行它本该审的条目。
     */
    public List<Finding> check(ProposedEntry entry, RuleContext ctx) {
        if (entry == null || entry.guess()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (SemanticConsistencyRule rule : rules) {
            if (!kindsByRule.get(rule).contains(entry.kind())) {
                continue;
            }
            try {
                List<RuleViolation> violations = rule.check(entry, ctx);
                if (violations == null) {
                    continue;
                }
                for (RuleViolation v : violations) {
                    if (v != null) {
                        out.add(new Finding(rule.code(), v.severity(), v.message()));
                    }
                }
            } catch (RuntimeException e) {
                log.error("语义层一致性规则执行出错，本条按退回处理 rule={} entry={} table={}",
                        rule.code(), entry.path(), ctx == null ? null : ctx.table(), e);
                out.add(new Finding(CODE_RULE_ERROR, RuleViolation.Severity.REJECT,
                        "服务端一致性检查 " + rule.code() + " 执行出错，这一条暂按退回处理。这不是你的提交写错了；"
                                + "如果这张表还有提交次数，可以原样重交一次，仍然出错就跳过这一条。"));
            }
        }
        return out;
    }

    /** run-scope 的 rules：每条规则一行，顺序与判定顺序相同。 */
    public List<RuleSummary> summaries() {
        return rules.stream().map(r -> new RuleSummary(r.code(), r.summary())).toList();
    }

    /** 已排序的规则，只读。 */
    public List<SemanticConsistencyRule> rules() {
        return rules;
    }

    /**
     * 一个条目被某条规则判出的一个违规，机器码由注册表贴上。纯内部形状（record），出网前由回调服务转成 DTO。
     */
    public record Finding(String code, RuleViolation.Severity severity, String message) {

        /** rejected / dropped。 */
        public String status() {
            return severity.status();
        }
    }

    /** run-scope 里的一行规则摘要。会随回调接口出网，所以是 Lombok 类而不是 record（jackson 2.11.1）。 */
    @Value
    public static class RuleSummary {
        String code;
        String summary;
    }
}

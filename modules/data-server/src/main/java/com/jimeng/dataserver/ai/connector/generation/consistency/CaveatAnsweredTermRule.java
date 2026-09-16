package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 规则 8：业务方已经回答过的口径，不再作为待确认问题提出（设计文档 7.9）。严重级别 DROP——不需要重提。
 *
 * <p>toRows 也会丢（同一个码 CAVEAT_TERM_ANSWERED、同一句话），这里在一致性检查阶段先拦一道，让它和其余规则的结果一起、
 * 在第一次提交时就回给 agent；run-scope 的 answeredTerms 是提前告知，这里是兜底。
 * 不拦的后果：conn_catalog 会把<b>答案</b>和<b>同一个问题</b>一起注入，模型只能二选一。飞书定稿 §5。
 *
 * <p>折叠口径必须与 {@link SemanticRowAssembler#answeredTerms} 生成已答集合时一致（trim + 小写），所以用
 * {@link SemanticRowAssembler#fold}，不用重音也折叠的 {@code ciFold}——两边口径不一，就会出现「规则说答过、toRows 说没答过」。
 */
@Component
@Order(800)
public class CaveatAnsweredTermRule implements SemanticConsistencyRule {

    public static final String CODE = "CAVEAT_TERM_ANSWERED";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(SemanticRowAssembler.KIND_AMBIGUITIES);
    }

    @Override
    public String summary() {
        return "业务方已在对话里回答过的口径（run-scope 的 answeredTerms）不再作为待确认问题提出";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String term = entry.str("term");
        if (term == null || !ctx.answeredTerms().contains(SemanticRowAssembler.fold(term))) {
            return List.of();
        }
        return List.of(RuleViolation.drop("口径『" + term + "』已由业务方在对话里回答，不再作为待确认问题提出，不需要重提。"));
    }
}

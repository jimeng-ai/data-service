package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义层生成路径选择器。只做廉价准入，不建批次、不触发生成。
 *
 * <p>六条 {@link AgentPathPrecondition} 按 Spring {@code @Order} 顺序检查，第一个失败就决定
 * 回落原因与中断批次策略。返回值直接携带 verdict/policy，后续门面无需按前置条件类型写分支。
 */
@Component
public class SemanticGeneratorSelector {

    private final List<AgentPathPrecondition> preconditions;

    public SemanticGeneratorSelector(List<AgentPathPrecondition> preconditions) {
        List<AgentPathPrecondition> sorted = new ArrayList<>(preconditions == null ? List.of() : preconditions);
        // Spring 注入 List 本来就按 @Order 排好；再排一次使单测和手工装配也等价。
        AnnotationAwareOrderComparator.sort(sorted);
        this.preconditions = List.copyOf(sorted);
    }

    public Selection select(ConnectorView connector) {
        for (AgentPathPrecondition precondition : preconditions) {
            PreconditionVerdict verdict = precondition.check(connector);
            if (!verdict.pass()) {
                return new Selection(GeneratorKind.SINGLE_CALL, verdict, precondition.onInterruptedBatch());
            }
        }
        return new Selection(GeneratorKind.AGENT, PreconditionVerdict.allowed(), null);
    }

    /** 选择器的纯值结果；门面据此选生成器、处理中断批次并传递降级原因。 */
    public record Selection(GeneratorKind kind,
                            PreconditionVerdict verdict,
                            InterruptedBatchPolicy interruptedBatchPolicy) {

        /** silent 失败不是降级，不向单次推导传原因。 */
        public String degradeReason() {
            return kind == GeneratorKind.SINGLE_CALL && !verdict.silent() ? verdict.reason() : null;
        }
    }
}

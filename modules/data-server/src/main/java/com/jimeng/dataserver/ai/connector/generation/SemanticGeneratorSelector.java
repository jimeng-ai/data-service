package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.generation.precondition.AgentConfigPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.AgentSwitchPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.DescribeCapabilityPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SandboxConfiguredPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SandboxHealthPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SemanticEnabledPrecondition;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
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

    private static final List<ExpectedPrecondition> EXPECTED_PRECONDITIONS = List.of(
            new ExpectedPrecondition(SemanticEnabledPrecondition.class, 1),
            new ExpectedPrecondition(DescribeCapabilityPrecondition.class, 2),
            new ExpectedPrecondition(AgentSwitchPrecondition.class, 3),
            new ExpectedPrecondition(SandboxConfiguredPrecondition.class, 4),
            new ExpectedPrecondition(AgentConfigPrecondition.class, 5),
            new ExpectedPrecondition(SandboxHealthPrecondition.class, 6));

    private final List<AgentPathPrecondition> preconditions;

    public SemanticGeneratorSelector(List<AgentPathPrecondition> preconditions) {
        validatePreconditions(preconditions);
        List<AgentPathPrecondition> sorted = new ArrayList<>(preconditions);
        // Spring 注入 List 本来就按 @Order 排好；再排一次使单测和手工装配也等价。
        AnnotationAwareOrderComparator.sort(sorted);
        this.preconditions = List.copyOf(sorted);
    }

    private static void validatePreconditions(List<AgentPathPrecondition> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("agent 前置条件列表不能为 null");
        }
        if (candidates.stream().anyMatch(candidate -> candidate == null)) {
            throw new IllegalArgumentException("agent 前置条件不能包含 null");
        }

        List<Class<?>> actualTypes = candidates.stream()
                .map(AopProxyUtils::ultimateTargetClass)
                .toList();
        for (ExpectedPrecondition expected : EXPECTED_PRECONDITIONS) {
            long count = actualTypes.stream().filter(expected.type()::equals).count();
            if (count != 1) {
                throw new IllegalStateException("agent 前置条件 " + expected.type().getSimpleName()
                        + " 必须且只能有一个，实际=" + count);
            }
            Order order = expected.type().getAnnotation(Order.class);
            if (order == null || order.value() != expected.order()) {
                throw new IllegalStateException("agent 前置条件 " + expected.type().getSimpleName()
                        + " 的 @Order 必须为 " + expected.order());
            }
        }
        if (candidates.size() != EXPECTED_PRECONDITIONS.size()) {
            throw new IllegalStateException("agent 前置条件只能包含预期的六个具体类型，实际=" + actualTypes);
        }
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

    private record ExpectedPrecondition(Class<? extends AgentPathPrecondition> type, int order) {
    }
}

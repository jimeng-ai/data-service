package com.jimeng.dataserver.ai.connector.generation.consistency;

import java.util.List;
import java.util.Set;

/**
 * 语义层生成 agent 提交时的一条<b>确定性</b>一致性规则（设计文档 7.9）。一条规则一个 {@code @Component} 类，
 * 用 {@code @Order} 排先后，由 {@link SemanticConsistencyRuleRegistry} 自动收集——新增规则只加类，不改提交流水线。
 *
 * <h3>规则能做什么、不能做什么</h3>
 * 规则只<b>判</b>，不写：输入是 agent 提交的一个条目和只读的快照上下文，输出是违规清单。租户隔离、CAS、
 * 「只动 INFERRED 行」这些不变式留在提交流水线的骨架里，不开放给规则——所以任何一条写坏了的规则最多多退回几条，
 * 放松不了隔离。
 *
 * <h3>三条约定</h3>
 * <ul>
 *   <li><b>只判自己那件事。</b>必填项缺失、名字对不上快照这些由 {@code SemanticRowAssembler.toRows} 报
 *       （MISSING_REQUIRED / NAME_NOT_IN_SNAPSHOT）；规则遇到它们直接放行，不重复退回同一个问题。
 *       唯一的例外是 {@code JoinEndpointExistsRule}：它的职责就是把「对不上」说成 agent 能照着改的话。</li>
 *   <li><b>收集全部违规。</b>一个条目违反几条就返回几条，agent 一次看到全部问题，不用一轮一轮试。</li>
 *   <li><b>文案给 agent 看。</b>说清楚哪里不对、该怎么改或者不要提交；不回显 token，不引用任何数据值（本期不读数据）。</li>
 * </ul>
 */
public interface SemanticConsistencyRule {

    /** 稳定的机器码，写进 entries[].code 与 generation_table.last_reject_reason。上线后不许改名。 */
    String code();

    /** 适用的条目种类：objects / fields / joins / ambiguities（{@code SemanticRowAssembler.KIND_*}）。 */
    Set<String> kinds();

    /** 一行摘要，进 run-scope 的 rules：工具说明与服务端判定出自同一来源。 */
    String summary();

    /** 返回空列表 = 通过。{@link RuleViolation} 带 severity（REJECT / DROP）与给 agent 看的文案。 */
    List<RuleViolation> check(ProposedEntry entry, RuleContext ctx);
}

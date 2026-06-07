package com.jimeng.dataserver.ai.trace.dto;

import lombok.Data;

/**
 * 回放「执行叙事」中的一个单元（从对话消息还原，专为调优查看）。
 *
 * <p>kind 取值：
 * <ul>
 *   <li>{@code user} — 用户提问（text）</li>
 *   <li>{@code assistant} — 模型某轮输出文本（text）</li>
 *   <li>{@code tool} — 一次工具调用（toolType/toolName/input/output 配对）</li>
 *   <li>{@code answer} — 最终回答（text）</li>
 * </ul>
 */
@Data
public class TraceReplayTurn {

    private String kind;

    /** user / assistant / answer 的文本。 */
    private String text;

    /** kind=tool：工具子类型 kb(知识库) / skill(技能激活) / plugin(插件/工具)。 */
    private String toolType;
    private String toolName;
    /** 工具入参（已解析的 JSON 或原文）。 */
    private Object input;
    /** 工具输出（按 tool_use_id 配对的结果，已解析的 JSON 或文本）。 */
    private Object output;

    /** 该执行单元耗时（ms）：LLM 卡来自 LLM 步、工具卡来自对应工具步，按顺序归属；无则空。 */
    private Integer durationMs;
    /** 该执行单元 token（仅 LLM 卡有）。 */
    private Integer tokens;

    /** 知识库检索卡专属：rerank 信息（{model, kept, candidates}，无 rerank 步则空）。 */
    private Object rerank;
}

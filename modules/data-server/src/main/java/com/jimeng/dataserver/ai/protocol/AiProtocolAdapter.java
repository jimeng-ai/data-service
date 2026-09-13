package com.jimeng.dataserver.ai.protocol;

import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;

import java.util.List;
import java.util.Map;

public interface AiProtocolAdapter {

    // ---- system prompt ----

    void appendSystemContent(Map<String, Object> body, String text);

    // ---- tools ----

    List<Object> getToolsList(Map<String, Object> body);

    void setToolsList(Map<String, Object> body, List<Object> tools);

    Map<String, Object> convertToolDef(SkillToolDefinition def);

    String getToolName(Object toolDef);

    Object buildActivateSkillsToolDef();

    void ensureToolChoiceAuto(Map<String, Object> body);

    void removeToolByName(Map<String, Object> body, String name);

    // ---- response parsing ----

    List<ToolUseCall> extractToolUseCalls(Map<String, Object> responseMap);

    /** Returns [inputTokens, outputTokens] */
    int[] extractUsage(Map<String, Object> responseMap);

    /** 抽出本轮 assistant 的纯文本回复（协议相关：Anthropic content[]、OpenAI choices[]）；无可抽取时返回 null。 */
    String extractAssistantText(Map<String, Object> responseMap);

    // ---- multi-turn message building ----

    void appendToolResultTurn(Map<String, Object> body, Map<String, Object> responseMap,
                               List<ToolExecutionResult> results);

    void appendActivationTurn(Map<String, Object> body, Map<String, Object> responseMap,
                               ActivationResult activation);

    Map<String, Object> buildActivationToolResultBlock(String toolUseId, String toolName,
                                                        Object payload, boolean isError);

    // ---- non-stream aggregated response ----

    Object buildAggregatedResponse(Map<String, Object> responseMap,
                                    int totalInput, int totalOutput, int toolRounds, String traceId);

    // ---- stream ----

    AiStreamAccumulator createStreamAccumulator();

    String getDeltaEventType();

    /** True when this data token signals the end of the stream (e.g. OpenAI's "[DONE]"). */
    boolean isDoneSignal(String data);

    // ================================================================ 入口协议 ≠ 上游协议

    /*
     * 下面三个方法是为「入口协议与上游协议不同」准备的转换点，**默认全部恒等**。
     *
     * 既有的两个 adapter（Claude / OpenAi）入口与上游同协议，一个字节都不受影响。
     * 只有跨协议的 adapter（如 AnthropicOverOpenAiAdapter）才覆写它们。
     *
     * 为什么要有这三个点：AiConversationLoop 全程把 body 和 responseMap 当成【入口协议】的形状
     * 在操作（注工具、拼多轮、抽 tool_use），这是它能保持协议无关的前提。
     * 跨协议时只需要在「发出去之前」「收回来之后」「转发给前端之前」这三处做转换，
     * 循环本身完全不用动——差异被收敛在 adapter 这一层，这正是它存在的意义。
     */

    /** 发给上游前的最后一道转换。 */
    default java.util.Map<String, Object> toUpstreamBody(java.util.Map<String, Object> body) {
        return body;
    }

    /** 上游响应 → 入口协议形状。之后循环里的所有 extract/append 都按入口协议处理。 */
    default java.util.Map<String, Object> fromUpstreamResponse(java.util.Map<String, Object> resp) {
        return resp;
    }

    /**
     * 上游 SSE 帧 → 转发给前端的帧。返回 {@code null} 表示这一帧不转发
     * （上游有些心跳/元数据帧对前端没有意义，转过去只会让它解析失败）。
     */
    default String transformDeltaFrame(String data) {
        return data;
    }
}

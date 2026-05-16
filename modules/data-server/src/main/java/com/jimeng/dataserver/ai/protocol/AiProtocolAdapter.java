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
}

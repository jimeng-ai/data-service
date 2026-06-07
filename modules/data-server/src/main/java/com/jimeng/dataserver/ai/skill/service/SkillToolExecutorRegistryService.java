package com.jimeng.dataserver.ai.skill.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.billing.TraceRecorder;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillToolExecutorRegistryService {

    private final List<SkillToolExecutor> executors;
    private final TraceRecorder traceRecorder;

    public List<ToolExecutionResult> executeAll(List<ToolUseCall> calls) {
        List<ToolExecutionResult> results = new ArrayList<>();
        if (calls == null || calls.isEmpty()) {
            return results;
        }

        for (ToolUseCall call : calls) {
            if (call == null) {
                continue;
            }
            String toolName = call.getToolName();
            SkillToolExecutor executor = findExecutor(toolName);
            if (executor == null) {
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("error", "tool_not_supported");
                errorPayload.put("message", "未找到可执行的tool: " + toolName);
                results.add(new ToolExecutionResult(call.getToolUseId(), toolName, false, errorPayload));
                continue;
            }

            long start = System.currentTimeMillis();
            try {
                Object payload = executor.execute(toolName, call.getInput());
                recordTraceStep(executor, toolName, true, null, System.currentTimeMillis() - start);
                results.add(new ToolExecutionResult(call.getToolUseId(), toolName, true, payload));
            } catch (Exception e) {
                log.warn("tool执行失败, name={}, error={}", toolName, e.getMessage());
                recordTraceStep(executor, toolName, false, e.getMessage(), System.currentTimeMillis() - start);
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("error", "tool_execution_failed");
                errorPayload.put("message", e.getMessage());
                results.add(new ToolExecutionResult(call.getToolUseId(), toolName, false, errorPayload));
            }
        }
        return results;
    }

    /**
     * 把一次工具执行记入调用链路 Trace。步骤类型由执行器声明（{@link SkillToolExecutor#traceStepType()}）：
     * PLUGIN_TRIGGER → 插件步骤；TOOL_CALL → 普通工具步骤；null → 跳过（RAG 自行埋点）。
     */
    private void recordTraceStep(SkillToolExecutor executor, String toolName,
                                 boolean ok, String errorMsg, long durationMs) {
        String type = executor.traceStepType();
        if (type == null) {
            return;
        }
        if ("PLUGIN_TRIGGER".equals(type)) {
            traceRecorder.recordPlugin(toolName, null, durationMs, ok, errorMsg);
        } else {
            traceRecorder.recordTool(toolName, null, null, durationMs, ok, errorMsg);
        }
    }

    private SkillToolExecutor findExecutor(String toolName) {
        if (StrUtil.isBlank(toolName) || executors == null || executors.isEmpty()) {
            return null;
        }
        for (SkillToolExecutor executor : executors) {
            if (executor != null && executor.supports(toolName)) {
                return executor;
            }
        }
        return null;
    }
}

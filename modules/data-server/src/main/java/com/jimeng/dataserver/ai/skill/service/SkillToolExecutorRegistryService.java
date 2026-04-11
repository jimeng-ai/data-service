package com.jimeng.dataserver.ai.skill.service;

import cn.hutool.core.util.StrUtil;
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

            try {
                Object payload = executor.execute(toolName, call.getInput());
                results.add(new ToolExecutionResult(call.getToolUseId(), toolName, true, payload));
            } catch (Exception e) {
                log.warn("tool执行失败, name={}, error={}", toolName, e.getMessage());
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("error", "tool_execution_failed");
                errorPayload.put("message", e.getMessage());
                results.add(new ToolExecutionResult(call.getToolUseId(), toolName, false, errorPayload));
            }
        }
        return results;
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

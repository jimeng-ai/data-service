package com.jimeng.dataserver.ai.agent.exec.controller;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentExecRequest;
import com.jimeng.dataserver.ai.agent.exec.service.AgentExecService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 代码执行 / 文件处理 Agent 入口。镜像 RagAnswerController 的 SSE 模式：
 * 请求线程做权限校验，工作转交 streamExecutor 并用 MdcAsyncSupport 透传租户/用户上下文。
 */
@Tag(name = "Agent-代码执行", description = "上传文件 + 代码执行 Agent（沙箱边车），SSE 流式返回")
@RestController
@RequestMapping("/data/agent")
@RequiredArgsConstructor
public class AgentExecController {

    private final SseServiceUtil sseServiceUtil;
    private final AgentExecService agentExecService;
    private final PermissionResolver permissionResolver;
    private final AgentSandboxProperties props;

    private final ThreadPoolTaskExecutor streamExecutor;

    @Operation(summary = "代码执行 / 文件处理流式",
            description = "把用户输入与已上传文件交给沙箱 Agent 处理，SSE 推送 "
                    + "file_status / progress / code_output / claude-delta / tool_result / artifact / summary 事件。")
    @PostMapping("/exec")
    public SseEmitter exec(@RequestBody AgentExecRequest req, HttpServletRequest request) {
        if (StrUtil.isNotBlank(req.getAgentId())) {
            permissionResolver.assertCurrentAccess(ResourceType.AGENT, Long.parseLong(req.getAgentId().trim()));
        }
        String connectionId = UUID.randomUUID().toString();
        String traceId = request.getHeader("x-trace-id");
        long timeoutMs = (props.getWallClockSec() + 60L) * 1000L;
        SseEmitter emitter = sseServiceUtil.getConnection(connectionId, timeoutMs);
        streamExecutor.execute(MdcAsyncSupport.wrap(connectionId,
                () -> agentExecService.streamExec(req, connectionId, traceId)));
        return emitter;
    }
}

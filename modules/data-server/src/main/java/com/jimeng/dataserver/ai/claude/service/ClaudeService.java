package com.jimeng.dataserver.ai.claude.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 协议入口：把 /data/claude/messages 请求委托给当前激活 provider 的 chat client。
 * provider 的 chat.protocol 必须是 anthropic，否则 fail-fast 400 并指引到 /data/openai/...。
 *
 * <p>支持可选的 {@code agent_id}：若请求体里带了 agent_id，
 * 加载 {@link AgentRuntimeView} 并注入到 system_prompt + 模型默认值，
 * 同时把 Agent 上下文塞进 {@link AgentContext}（供下游 SkillRuntime / HttpPluginToolExecutor 读取）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeService {

    private static final String EXPECTED_PROTOCOL = "anthropic";

    private final ProviderRegistry providerRegistry;
    private final AgentRuntimeService agentRuntimeService;

    public Object messages(Map<String, Object> requestBody) {
        ChatClient client = requireAnthropicChat();
        applyAgentContext(requestBody);
        return client.chat(requestBody, extractTraceId());
    }

    public void messagesStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        ChatClient client = requireAnthropicChat();
        // 注意：对于流式请求，Controller 已在主线程调用 prepareAgentContext 设置好 AgentContext + body。
        // 这里只兜底（异步线程被 MdcAsyncSupport 继承的情况下 AgentContext 已有值）。
        requestBody.put("stream", true);
        client.chatStream(requestBody, connectionId, traceId);
    }

    /**
     * 给 ClaudeController 调用：在主线程预处理 Agent 上下文，再 dispatch 到 async 线程。
     * 调用方：非流式直接走 messages()；流式走 prepareAgentContext + 异步 messagesStream()。
     */
    public void prepareAgentContext(Map<String, Object> requestBody) {
        applyAgentContext(requestBody);
    }

    // ------------------------------------------------------------------ internals

    private void applyAgentContext(Map<String, Object> body) {
        if (body == null) return;
        Object agentIdObj = body.remove("agent_id");
        if (agentIdObj == null) return;
        Long agentId = parseLong(agentIdObj);
        if (agentId == null) return;

        AgentRuntimeView agent = agentRuntimeService.byId(agentId);
        AgentContext.set(agent);
        log.info("Agent 上下文已加载: id={}, code={}, allowedPlugins={}",
                agent.getAgentId(), agent.getCode(), agent.getAllowedPluginCodes());

        // 注入 system_prompt（追加到现有 system 之前）
        if (StringUtils.hasText(agent.getSystemPrompt())) {
            prependSystemPrompt(body, agent.getSystemPrompt());
        }

        // 默认 model / model_params（请求体显式给的优先）
        if (!body.containsKey("model") && StringUtils.hasText(agent.getDefaultModel())) {
            body.put("model", agent.getDefaultModel());
        }
        if (agent.getDefaultModelParams() != null) {
            for (Map.Entry<String, Object> e : agent.getDefaultModelParams().entrySet()) {
                body.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void prependSystemPrompt(Map<String, Object> body, String prompt) {
        Object existing = body.get("system");
        if (existing == null) {
            body.put("system", prompt);
            return;
        }
        if (existing instanceof String s) {
            body.put("system", prompt + "\n\n" + s);
            return;
        }
        if (existing instanceof List) {
            List<Object> newList = new ArrayList<>();
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", prompt);
            newList.add(textBlock);
            newList.addAll((List) existing);
            body.put("system", newList);
            return;
        }
        // 兜底：当字符串拼
        body.put("system", prompt + "\n\n" + String.valueOf(existing));
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "agent_id 必须是数字");
        }
    }

    private ChatClient requireAnthropicChat() {
        ChatClient client = providerRegistry.chat();
        ChatCapabilities caps = client.capabilities();
        if (!EXPECTED_PROTOCOL.equals(caps.protocol())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "当前 ai.provider=" + caps.providerName()
                            + " 的 chat.protocol=" + caps.protocol()
                            + "，与 /data/claude/messages 期望的 anthropic 协议不匹配，请改用 /data/openai/chat/completions");
        }
        return client;
    }

    private String extractTraceId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String traceId = req.getHeader("trace-id");
            return StrUtil.isNotBlank(traceId) ? traceId : req.getHeader("x-trace-id");
        } catch (Exception e) {
            log.warn("提取trace-id失败: {}", e.getMessage());
            return null;
        }
    }
}

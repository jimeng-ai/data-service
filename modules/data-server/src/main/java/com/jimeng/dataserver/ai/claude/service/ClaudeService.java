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
        // preview=调试台读实时草稿；缺省/false=对话端只读已发布快照。两者都从 body 取出后移除，避免透传给上游。
        Object previewObj = body.remove("agent_preview");
        // 调试台显式 kbId 已前置强制检索时，由 RagAnswerService 置位：抑制绑定 KB 的检索护栏，避免双套检索指令冲突。
        boolean suppressKbGrounding = Boolean.TRUE.equals(body.remove("__suppress_kb_grounding__"));
        if (agentIdObj == null) return;
        Long agentId = parseLong(agentIdObj);
        if (agentId == null) return;

        boolean preview = previewObj instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(previewObj));
        AgentRuntimeView agent = agentRuntimeService.byId(agentId, preview);
        AgentContext.set(agent);
        log.info("Agent 上下文已加载: id={}, code={}, allowedPlugins={}",
                agent.getAgentId(), agent.getCode(), agent.getAllowedPluginCodes());

        // 注入 system_prompt（追加到现有 system 之前）
        if (StringUtils.hasText(agent.getSystemPrompt())) {
            prependSystemPrompt(body, agent.getSystemPrompt());
        }

        // 绑定了知识库的 Agent：注入「检索护栏」。这是把原强制检索路（RagAnswerService）专属的 grounding 约束
        // 搬到工具式按需检索路上——使「该检索时必检索 + 检索不到就明说 + 不污染正文 + kb_id 已给定免 rag.kb.list」
        // 对所有绑定 KB 的 Agent 一致生效，取代「绑定即每轮无条件前置检索」。
        if (agent.getKbIds() != null && !agent.getKbIds().isEmpty() && !suppressKbGrounding) {
            prependSystemPrompt(body, buildKbGroundingPrompt(agent.getKbIds()));
        }

        // 默认 model / model_params（请求体显式给的优先）
        if (!body.containsKey("model") && StringUtils.hasText(agent.getDefaultModel())) {
            body.put("model", agent.getDefaultModel());
        }
        if (agent.getDefaultModelParams() != null) {
            for (Map.Entry<String, Object> e : agent.getDefaultModelParams().entrySet()) {
                // 把前端 modelParams 的驼峰 key 归一为各 API 认的蛇形 key，否则参数会被模型忽略。
                body.putIfAbsent(normalizeModelParamKey(e.getKey()), e.getValue());
            }
        }
    }

    /** modelParams key 归一：topP → top_p、maxTokens → max_tokens；其余原样（temperature 等已一致）。 */
    private String normalizeModelParamKey(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "topP" -> "top_p";
            case "maxTokens" -> "max_tokens";
            default -> key;
        };
    }

    /** 绑定知识库的 Agent 的「检索护栏」系统提示：把 kb_id 钉进提示，约束必检索 / 零编造 / 正文不外露引用标号。 */
    private String buildKbGroundingPrompt(java.util.Set<Long> kbIds) {
        StringBuilder ids = new StringBuilder();
        for (Long id : kbIds) {
            if (ids.length() > 0) ids.append(", ");
            ids.append(id);
        }
        return "你已绑定企业知识库（kb_id = " + ids + "）。回答用户的实质性问题前，"
                + "必须先调用 rag_search 工具检索知识库（kb_id 用上面给定的值，无需调用 rag_kb_list）；"
                + "仅当用户是纯寒暄、明显与知识库无关的闲聊时才可不检索。"
                + "若检索结果不足以回答，明确告知「未在知识库中找到相关信息」，不要编造知识库以外的内容。"
                + "不要在回答正文里输出 chunk_id、片段编号或方括号引用标签——来源会由系统在回答下方单独展示。";
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

package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.provider.spi.ChatCapabilities;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用聊天客户端：按 ProviderConfig.chat.protocol 路由到 anthropic 或 openai 协议路径。
 * 每个 provider 由 ProviderBeansConfig 实例化一份并注册为同名 bean。
 *
 * <p>请求体在调用进入时按协议补默认值（model、max_tokens / max_completion_tokens、system 等），
 * 之后委托给 AiConversationLoop 处理多轮 + 流式 + skill 工具循环。
 */
@Slf4j
public class GenericChatClient implements ChatClient {

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    private static final String DEFAULT_ANTHROPIC_PATH = "/messages";
    private static final String DEFAULT_OPENAI_PATH = "/chat/completions";

    private final String providerName;
    /** 构造期快照，只在 allProviders 里查不到本 provider 时兜底（理论上不会发生）。 */
    private final ProviderConfig configSnapshot;
    private final AiSelectionProperties selection;
    private final AiConversationLoop conversationLoop;
    private final AiProtocolAdapter anthropicAdapter;
    private final AiProtocolAdapter openaiAdapter;
    /** 入口 anthropic / 上游 openai 的跨协议适配器。同协议的 provider 用不到它。 */
    private final AiProtocolAdapter crossAdapter;
    private final SseServiceUtil sseServiceUtil;
    /** 全量 provider 配置，仅用于按名解析 ai.chat-fallback-providers 里的备用 provider。 */
    private final AiProviderProperties allProviders;

    public GenericChatClient(String providerName,
                             ProviderConfig config,
                             AiSelectionProperties selection,
                             AiConversationLoop conversationLoop,
                             AiProtocolAdapter anthropicAdapter,
                             AiProtocolAdapter openaiAdapter,
                             AiProtocolAdapter crossAdapter,
                             SseServiceUtil sseServiceUtil,
                             AiProviderProperties allProviders) {
        this.providerName = providerName;
        this.configSnapshot = config;
        this.selection = selection;
        this.conversationLoop = conversationLoop;
        this.anthropicAdapter = anthropicAdapter;
        this.openaiAdapter = openaiAdapter;
        this.crossAdapter = crossAdapter;
        this.sseServiceUtil = sseServiceUtil;
        this.allProviders = allProviders;
    }

    /**
     * 当前生效的 provider 配置。
     *
     * <h3>★ 为什么每次取，而不是持有构造期的那份</h3>
     * 旧实现把 {@link ProviderConfig} 在 bean 创建时就固化进字段，于是改了 Nacos 里的
     * {@code api-key} 之后<b>必须重启</b>才生效——而 Nacos 会照常打出
     * {@code Refresh keys changed}。那条日志说的是「配置变了」，<b>不是</b>「生效了」，
     * 实测表现是刷新后依然 401，让人以为是 key 填错了。
     *
     * <p>{@link AiProviderProperties} 是 {@code @ConfigurationProperties} bean，
     * 配置刷新时由 Spring Cloud 重新绑定。每次从它按名取，无论重绑是「就地改字段」还是
     * 「换掉整个 map」都能拿到新值，也就不需要给这些手工 new 出来的 client 套 {@code @RefreshScope}。
     * 一次 map 查找的开销相对一次 LLM 调用可以忽略。
     */
    private ProviderConfig config() {
        ProviderConfig live = allProviders == null ? null : allProviders.getProviders().get(providerName);
        return live != null ? live : configSnapshot;
    }

    private String systemPrompt() {
        return selection == null ? null : selection.getSystemPrompt();
    }

    @Override
    public Object chat(Map<String, Object> requestBody, String traceId) {
        Map<String, Object> body = prepareBody(requestBody, false);
        return conversationLoop.runBlocking(body, adapter(), buildHeaders(), buildUrl(), traceId, recordConfig());
    }

    @Override
    public void chatStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        Map<String, Object> body;
        try {
            body = prepareBody(requestBody, true);
        } catch (Exception e) {
            log.warn("流式请求体构造失败 provider={} connectionId={} err={}", providerName, connectionId, e.getMessage());
            sendStreamError(connectionId, e);
            sseServiceUtil.complete(connectionId);
            return;
        }
        conversationLoop.runStream(body, streamTargets(), connectionId, traceId);
    }

    /**
     * 平台内部的单轮调用。请求体默认值（prepareBody）、adapter 选择、URL 与鉴权头都与 {@link #chat} 相同，
     * 区别只在委托给 {@link AiConversationLoop#runInternal}：不带工具、不进工具循环、超时按调用给。
     */
    @Override
    public Object chatInternal(Map<String, Object> requestBody, String traceId, Duration readTimeout) {
        Map<String, Object> body = prepareBody(requestBody, false);
        return conversationLoop.runInternal(body, adapter(), buildHeaders(), buildUrl(), traceId, recordConfig(),
                readTimeout);
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ChatCapabilities capabilities() {
        // ★ 对外声明的是【入口协议】：ModelResolver.ensureProtocol 校验的就是这个值。
        // 上游实际说什么协议（config.chat.protocol）对调用方不可见，也不该可见。
        String entry = config().getChat().entryProtocolOrDefault();
        return new ChatCapabilities(entry,
                PROTOCOL_ANTHROPIC.equals(entry),
                providerName,
                config().getChat().getModel());
    }

    private Map<String, Object> prepareBody(Map<String, Object> requestBody, boolean streaming) {
        if (requestBody == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (StrUtil.isBlank(config().getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".api-key 未配置");
        }
        if (StrUtil.isBlank(config().getChat().getModel())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".chat.model 未配置");
        }
        // 按【入口协议】补默认值：requestBody 进来时是入口协议的形状，
        // 转成上游形状是 adapter.toUpstreamBody 的事，不在这里做。
        String protocol = config().getChat().entryProtocolOrDefault();
        if (PROTOCOL_ANTHROPIC.equals(protocol)) {
            return prepareAnthropicBody(requestBody);
        }
        if (PROTOCOL_OPENAI.equals(protocol)) {
            return prepareOpenAiBody(requestBody, streaming);
        }
        throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                "providers." + providerName + ".chat.protocol 仅支持 anthropic / openai，当前=" + protocol);
    }

    private Map<String, Object> prepareAnthropicBody(Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        normalizeAnthropicMaxTokens(body);
        clampAnthropicTemperature(body);
        String sp = systemPrompt();
        if (StrUtil.isNotBlank(sp)) body.putIfAbsent("system", sp);
        body.putIfAbsent("model", config().getChat().getModel());
        body.putIfAbsent("max_tokens", config().getChat().getMaxTokens());

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareOpenAiBody(Map<String, Object> requestBody, boolean streaming) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.putIfAbsent("model", config().getChat().getModel());
        normalizeOpenAiMaxTokens(body);
        if (!body.containsKey("max_tokens") && !body.containsKey("max_completion_tokens")) {
            body.put("max_completion_tokens", config().getChat().getMaxTokens());
        }
        body.putIfAbsent("temperature", 1);
        body.put("stream", streaming || Boolean.TRUE.equals(body.get("stream")));
        if (Boolean.TRUE.equals(body.get("stream"))) {
            body.putIfAbsent("stream_options", Map.of("include_usage", true));
        }

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "messages不能为空");
        }
        prependSystemMessageIfMissing(body);
        return body;
    }

    @SuppressWarnings("unchecked")
    private void prependSystemMessageIfMissing(Map<String, Object> body) {
        String sp = systemPrompt();
        if (StrUtil.isBlank(sp)) return;
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) return;
        List<Object> messages = (List<Object>) rawMessages;
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) continue;
            Object role = msg.get("role");
            if ("system".equals(role) || "developer".equals(role)) return;
        }
        List<Object> copied = new ArrayList<>(messages.size() + 1);
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", sp);
        copied.add(systemMsg);
        copied.addAll(messages);
        body.put("messages", copied);
    }

    private void normalizeAnthropicMaxTokens(Map<String, Object> body) {
        if (body.containsKey("max_tokens")) return;
        Object camelValue = body.remove("maxTokens");
        if (camelValue != null) body.put("max_tokens", camelValue);
    }

    /**
     * Anthropic 的 temperature 合法区间是 [0,1]，超出会被 API 直接拒绝（400）。
     * 前端历史上把温度滑块上限误设为 2，这里兜底夹紧，避免给 Claude 设 >1 时整条请求失败。
     */
    private void clampAnthropicTemperature(Map<String, Object> body) {
        if (!(body.get("temperature") instanceof Number num)) return;
        double v = num.doubleValue();
        if (v < 0) body.put("temperature", 0);
        else if (v > 1) body.put("temperature", 1);
    }

    private void normalizeOpenAiMaxTokens(Map<String, Object> body) {
        Object maxCompletion = body.containsKey("max_completion_tokens")
                ? body.get("max_completion_tokens") : body.remove("maxCompletionTokens");
        Object maxTokens = body.containsKey("max_tokens")
                ? body.remove("max_tokens") : body.remove("maxTokens");
        body.remove("max_output_tokens");
        Object effective = maxCompletion != null ? maxCompletion : maxTokens;
        if (effective != null) body.put("max_completion_tokens", effective);
    }

    /**
     * 选 adapter：入口协议与上游协议<b>不同</b>时用跨协议适配器，相同时用原生的那个。
     *
     * <p>目前只支持「入口 anthropic / 上游 openai」这一个方向——因为平台的对话链路入口
     * 就只有 anthropic 一种。反方向（入口 openai / 上游 anthropic）没有使用场景，
     * 真需要时再加，不提前抽。
     */
    private AiProtocolAdapter adapter() {
        return adapter(providerName, config());
    }

    private AiProtocolAdapter adapter(String name, ProviderConfig cfg) {
        String upstream = cfg.getChat().getProtocol();
        String entry = cfg.getChat().entryProtocolOrDefault();
        if (!entry.equalsIgnoreCase(upstream)) {
            if (PROTOCOL_ANTHROPIC.equals(entry) && PROTOCOL_OPENAI.equals(upstream)) {
                return crossAdapter;
            }
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + name + " 的 entry-protocol=" + entry
                            + " / protocol=" + upstream + " 这个组合没有对应的适配器");
        }
        return PROTOCOL_ANTHROPIC.equals(upstream) ? anthropicAdapter : openaiAdapter;
    }

    private Map<String, String> buildHeaders() {
        return buildHeaders(config());
    }

    private static Map<String, String> buildHeaders(ProviderConfig cfg) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + cfg.getApiKey());
        return headers;
    }

    private String buildUrl() {
        return buildUrl(config());
    }

    private static String buildUrl(ProviderConfig cfg) {
        String path = cfg.getChat().getEndpointPath();
        if (StrUtil.isBlank(path)) {
            // 路径按【上游】协议推导——URL 是发给上游的，与入口协议无关。
            path = PROTOCOL_ANTHROPIC.equals(cfg.getChat().getProtocol())
                    ? DEFAULT_ANTHROPIC_PATH : DEFAULT_OPENAI_PATH;
        }
        return StrUtil.removeSuffix(cfg.getBaseUrl(), "/") + path;
    }

    private AiConversationLoop.CallRecordConfig recordConfig() {
        return new AiConversationLoop.CallRecordConfig(
                providerName, buildUrl(), config().getChat().getModel());
    }

    /**
     * 主 target + {@code ai.chat-fallback-providers} 配出来的备用 target，按顺序。
     *
     * <p><b>备用条目被跳过而不是抛错</b>的三种情况，每一种都打 warn：名字在 {@code providers.*}
     * 下不存在、指向自己、入口协议与主不一致。理由是同一个：备用链是<b>可用性增强</b>，
     * 它配错了不该把本来能用的主链一起拖垮。但也绝不能静默——静默跳过意味着你以为有兜底，
     * 真出事时才发现从来没生效过。
     *
     * <p>入口协议必须一致，是因为技能与内置工具已经按主 adapter 的形状注入进 body 了
     * （详见 {@code AiConversationLoop#runStream}）。协议不一致的备用换上去只会得到一个 400，
     * 那还不如没有。
     */
    private List<AiConversationLoop.UpstreamTarget> streamTargets() {
        List<AiConversationLoop.UpstreamTarget> targets = new ArrayList<>();
        targets.add(new AiConversationLoop.UpstreamTarget(
                providerName, buildUrl(), buildHeaders(), adapter(), recordConfig(), null, null));

        List<String> fallbacks = selection == null ? null : selection.getChatFallbackProviders();
        if (fallbacks == null || fallbacks.isEmpty() || allProviders == null) {
            return targets;
        }
        String entry = config().getChat().entryProtocolOrDefault();
        for (String name : fallbacks) {
            if (StrUtil.isBlank(name) || name.equals(providerName)) {
                continue;
            }
            ProviderConfig cfg = allProviders.getProviders().get(name);
            if (cfg == null) {
                log.warn("ai.chat-fallback-providers 里的 {} 在 providers.* 下没有配置，已跳过（主 provider={}）",
                        name, providerName);
                continue;
            }
            String fbEntry = cfg.getChat().entryProtocolOrDefault();
            if (!entry.equalsIgnoreCase(fbEntry)) {
                log.warn("备用 provider={} 的 entry-protocol={} 与主 provider={} 的 {} 不一致，已跳过"
                        + "（工具定义已按主协议注入 body，换上去只会 400）", name, fbEntry, providerName, entry);
                continue;
            }
            try {
                targets.add(new AiConversationLoop.UpstreamTarget(
                        name, buildUrl(cfg), buildHeaders(cfg), adapter(name, cfg),
                        new AiConversationLoop.CallRecordConfig(name, buildUrl(cfg), cfg.getChat().getModel()),
                        cfg.getChat().getModel(), cfg.getChat().getMaxTokens()));
            } catch (Exception e) {
                log.warn("备用 provider={} 的 target 构造失败，已跳过: {}", name, e.getMessage());
            }
        }
        if (targets.size() > 1) {
            log.debug("chat failover 链: {}", targets.stream()
                    .map(AiConversationLoop.UpstreamTarget::providerName).toList());
        }
        return targets;
    }

    private void sendStreamError(String connectionId, Exception e) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage());
            sseServiceUtil.sendEvent(connectionId, "error", JSONUtil.toJsonStr(err));
        } catch (Exception ignored) {
        }
    }
}

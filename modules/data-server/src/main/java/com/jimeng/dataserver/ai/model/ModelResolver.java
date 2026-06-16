package com.jimeng.dataserver.ai.model;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import com.jimeng.persistence.entity.AiModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * chat 请求的「按模型路由」解析器：把对话入口（Claude/OpenAI 两个 service）与
 * 「该模型实际打哪个 provider、上游叫什么名」解耦。
 *
 * <p>流程：取 {@code requestBody.model}（逻辑 value）→ 查 {@link ModelRegistry}：
 * <ul>
 *   <li><b>命中</b>：校验三方协议一致（入口协议 == 模型协议 == 连接协议）→ 取该 provider 的
 *       chat client → 把 {@code requestBody.model} 改写成 {@code upstream_model} 下发。</li>
 *   <li><b>未命中</b>（embedding/历史值/灰度空表/直连原始调用）：回落全局 active provider，
 *       不改写 model，保持与改造前一致——这把风险归零，也让迁移期 DB 为空时仍可用。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelResolver {

    private final ModelRegistry registry;
    private final ProviderRegistry providerRegistry;

    /**
     * 解析并就地改写 requestBody（把逻辑 value 换成 upstream_model），返回应使用的 chat client。
     *
     * @param requestBody      聊天请求体（含 model 字段；命中时会被就地改写）
     * @param expectedProtocol 入口协议：anthropic（/data/claude/messages）或 openai（/data/openai/chat/completions）
     */
    public ChatClient resolve(Map<String, Object> requestBody, String expectedProtocol) {
        Object rawModel = requestBody == null ? null : requestBody.get("model");
        String value = rawModel == null ? null : String.valueOf(rawModel);
        AiModel model = registry.resolve(value);

        // 未命中：回落全局 active provider，不改写（向后兼容 + 灰度空表保险）。
        if (model == null) {
            ChatClient active = providerRegistry.chat();
            ensureProtocol(active, expectedProtocol, "ai.provider=" + providerRegistry.activeProvider());
            return active;
        }

        // 命中但已下线：彻底停用——运行时也拒绝（不止从下拉隐藏）。
        // 计费历史口径不受影响：ModelPricing.priceOf 仍会解析 disabled 行。
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "模型 [" + value + "] 已下线，请在 Agent 配置中改用其他可用模型");
        }

        // 命中：模型协议必须与入口协议一致。
        if (!expectedProtocol.equalsIgnoreCase(model.getProtocol())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "模型 [" + value + "] 的协议为 " + model.getProtocol()
                            + "，与当前接口期望的 " + expectedProtocol + " 协议不匹配，请改用对应协议的接口");
        }

        ChatClient client = providerRegistry.chat(model.getProvider());
        if (client == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "模型 [" + value + "] 绑定的连接 [" + model.getProvider()
                            + "] 没有可用的 chat client（请检查 Nacos providers." + model.getProvider()
                            + " 配置与 ProviderBeansConfig 注册）");
        }
        // 连接协议也必须一致（否则请求体形状与上游不匹配）。
        ensureProtocol(client, model.getProtocol(),
                "模型 [" + value + "] 绑定的连接 [" + model.getProvider() + "]");

        // 改写为上游模型名下发；逻辑 value 与上游名一致时为无变更。
        if (StrUtil.isNotBlank(model.getUpstreamModel())) {
            requestBody.put("model", model.getUpstreamModel());
        }
        return client;
    }

    private void ensureProtocol(ChatClient client, String expectedProtocol, String who) {
        if (client == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, who + " 无可用 chat client");
        }
        String actual = client.capabilities().protocol();
        if (!expectedProtocol.equalsIgnoreCase(actual)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    who + " 的 chat.protocol=" + actual + "，与期望的 " + expectedProtocol + " 协议不匹配");
        }
    }
}

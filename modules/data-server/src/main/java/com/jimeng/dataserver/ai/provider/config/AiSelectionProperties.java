package com.jimeng.dataserver.ai.provider.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶层 AI 开关：ai.provider 决定 chat / embedding / rerank / contextualization 全部走哪家。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiSelectionProperties {

    /** 激活的 provider 名（必须与 providers.* 下的 key 一致）。 */
    private String provider;

    /** 全局 system prompt（保留现状，被聊天客户端读取）。 */
    private String systemPrompt;

    /**
     * 对话流式调用的<b>备用 provider</b>，按顺序尝试。主 provider 的重试用尽后才轮到它们。
     *
     * <p>典型用法：主用 DeepSeek 官网，官网抽风（503 / 限流 / 连不上）时自动切到火山引擎的
     * DeepSeek——同一个模型、两个供应商，对用户完全透明。
     *
     * <p><b>只影响 chat 流式，刻意不影响 embedding / rerank。</b>换一家 embedding 会换掉整个
     * 向量空间，而 ES 里已入库的向量是按旧模型算的——静默换供应商等于静默改检索结果，
     * 那是「配错了不报错，只是悄悄降级成某种能用但不对的状态」的又一例。
     *
     * <p>备用 provider 只需要在 {@code providers.*} 下配齐 chat 段并注册 {@code <name>-chat} bean，
     * 不必配 embedding / rerank（参见 ProviderBeansConfig 里 deepseek 那一段的说明）。
     *
     * <p>名字不存在的条目会在启动时告警并跳过，<b>不</b>阻止启动：备用链配错不该让主链起不来。
     */
    private List<String> chatFallbackProviders = new ArrayList<>();
}

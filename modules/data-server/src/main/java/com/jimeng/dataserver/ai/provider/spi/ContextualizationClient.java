package com.jimeng.dataserver.ai.provider.spi;

/**
 * 上下文化能力 SPI（RAG 入库阶段使用）。
 *
 * <p>独立于 {@link ChatClient}：本接口需要附加 anthropic ephemeral prompt cache，
 * 不走 skill/tool 循环，是一次性短调用；当 provider 协议为 openai 时优雅降级为普通调用。
 *
 * <p>4xx 返回时抛 {@link ContextualizationClientException}，调用方据此 fail-fast。
 */
public interface ContextualizationClient {

    /**
     * 生成单个 chunk 的上下文。返回文本 + 本次 usage，由调用方按整篇文档汇总计费
     * （client 不再逐次落库，避免一份文档产生上千行记录）。
     */
    ContextualizationResult generateContext(String fullDocument, String chunkContent);

    String describeImage(byte[] imageBytes, String mediaType);

    /** 由 ProviderRegistry 用于按 ai.provider 选 bean。 */
    String providerName();
}

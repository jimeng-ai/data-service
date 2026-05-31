package com.jimeng.dataserver.ai.provider.spi;

import java.util.List;

/**
 * Rerank 能力 SPI。不同 provider 的 rerank 响应结构差异较大（Cohere / Voyage / Qwen3 各异），
 * 协议适配由实现类内部完成，本接口仅暴露稳定结果。
 */
public interface RerankClient {

    /**
     * 重排。返回命中列表 + 本次 usage（供 RerankService 按 token 计费；无 usage 时由其估算）。
     */
    RerankResult rerank(String query, List<String> documents, int topN);

    /** 由 ProviderRegistry 用于按 ai.provider 选 bean。 */
    String providerName();
}

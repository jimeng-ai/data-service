package com.jimeng.dataserver.ai.provider.spi;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;

/**
 * rerank 协议 → 具体 {@link RerankClient} 的工厂。
 *
 * <p>把"按 protocol 选 rerank 实现"从 {@code ProviderBeansConfig} 里的 switch 改成开放-封闭：
 * 新增一种 rerank 协议只需新增一个实现 bean（自带 {@link #protocol()}），无需改任何已有分发代码。
 */
public interface RerankClientFactory {

    /** 该工厂支持的 rerank 协议标识，对应 yml {@code providers.<name>.rerank.protocol}（大小写不敏感）。 */
    String protocol();

    RerankClient create(String providerName, ProviderConfig cfg, RequestService requestService);
}

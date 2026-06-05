package com.jimeng.dataserver.ai.provider.impl;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.spi.RerankClient;
import com.jimeng.dataserver.ai.provider.spi.RerankClientFactory;
import org.springframework.stereotype.Component;

@Component
public class Qwen3RerankClientFactory implements RerankClientFactory {

    @Override
    public String protocol() {
        return "qwen3";
    }

    @Override
    public RerankClient create(String providerName, ProviderConfig cfg, RequestService requestService) {
        return new Qwen3RerankClient(providerName, cfg, requestService);
    }
}

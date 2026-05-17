package com.jimeng.dataserver.ai.provider.spi;

import java.util.List;

/**
 * 嵌入能力 SPI。
 *
 * <p>{@link #dims()} 由 ES 索引初始化代码与 Redis 解码逻辑读取，必须与实际返回向量长度一致；
 * 切换 provider 时若 dims 变化必须先 reindex。
 */
public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);

    int dims();

    String modelId();

    /** 由 ProviderRegistry 用于按 ai.provider 选 bean。 */
    String providerName();
}

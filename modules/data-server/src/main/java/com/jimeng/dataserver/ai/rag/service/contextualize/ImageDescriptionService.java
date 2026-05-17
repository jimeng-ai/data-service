package com.jimeng.dataserver.ai.rag.service.contextualize;

import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片转结构化描述：vision 模型把图片解析成 100-200 字中文描述，作为独立 chunk 索引。
 * 实际 prompt 与 HTTP 调用下沉到 {@link com.jimeng.dataserver.ai.provider.spi.ContextualizationClient#describeImage}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDescriptionService {

    private final ProviderRegistry providerRegistry;

    public String describe(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        try {
            return providerRegistry.contextualization().describeImage(imageBytes, mediaType);
        } catch (Exception e) {
            log.warn("Image description 调用失败: {}", e.getMessage());
            return "";
        }
    }
}

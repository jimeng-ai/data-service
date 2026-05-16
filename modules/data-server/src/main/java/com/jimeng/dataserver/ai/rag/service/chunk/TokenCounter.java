package com.jimeng.dataserver.ai.rag.service.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * Token 计数（cl100k_base）。
 *
 * jtokkit 用 OpenAI 系 cl100k_base，对 Claude/Voyage 是估算，存在 ±10% 偏差。
 * chunk 设计上对 max-size-tokens 留了 20% buffer（target=600/max=800），偏差吃得下。
 */
@Component
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }
}

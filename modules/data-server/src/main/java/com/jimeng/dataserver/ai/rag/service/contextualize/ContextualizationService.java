package com.jimeng.dataserver.ai.rag.service.contextualize;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClientException;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Contextual Retrieval：把整篇文档作为 cached 前缀，对每个 chunk 调 Claude Haiku
 * 生成 50-100 字"该片段在整篇中的定位"，prepend 到 chunk 文本里用于 BM25 / Embedding。
 *
 * <p>实际 HTTP 调用与 prompt 构造下沉到 {@link com.jimeng.dataserver.ai.provider.spi.ContextualizationClient}。
 * 本类只保留业务策略：批量串行调用、4xx 连续失败累计后 fail-fast。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextualizationService {

    private final ProviderRegistry providerRegistry;
    private final RagProperties ragProperties;

    /** 4xx 类错误（鉴权、余额、参数等）连续出现达到此阈值即 fail-fast，避免在已知会失败的请求上空转。 */
    private static final int CLIENT_ERROR_FAIL_FAST_THRESHOLD = 3;

    public String generateContext(String fullDocument, String chunkContent) {
        if (!ragProperties.getContextualization().isEnabled()) return "";
        if (StrUtil.isBlank(fullDocument) || StrUtil.isBlank(chunkContent)) return "";
        return providerRegistry.contextualization().generateContext(fullDocument, chunkContent);
    }

    /**
     * 给一组 chunks 串行生成 context（保证 prompt cache 命中）。
     *
     * <p>遇到 4xx 累计 {@value #CLIENT_ERROR_FAIL_FAST_THRESHOLD} 次即抛出，让 Rabbit retry 接管，
     * 避免一份 1000 chunk 的文档把 401/402 重复请求 1000 遍。
     */
    public List<String> generateContexts(String fullDocument, List<String> chunks) {
        List<String> out = new ArrayList<>(chunks.size());
        int consecutiveClientErrors = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                out.add(generateContext(fullDocument, chunk));
                consecutiveClientErrors = 0;
            } catch (ContextualizationClientException e) {
                consecutiveClientErrors++;
                log.warn("chunk[{}/{}] contextualization 4xx status={}（连续 {} 次）",
                        i + 1, chunks.size(), e.getStatus(), consecutiveClientErrors);
                if (consecutiveClientErrors >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
                    throw new ServiceException(
                            ExceptionCode.SERVICE_UNAVAILABLE,
                            "Contextualization 连续 " + consecutiveClientErrors +
                                    " 次返回 " + e.getStatus() +
                                    "，疑似 provider 鉴权/余额/限流问题，已 fail-fast 终止本次入库。body=" +
                                    StrUtil.maxLength(e.getBody() == null ? "" : e.getBody(), 200));
                }
                out.add("");
            } catch (Exception e) {
                log.warn("chunk[{}/{}] contextualization 失败，使用原文 fallback: {}",
                        i + 1, chunks.size(), e.getMessage());
                out.add("");
            }
        }
        return out;
    }
}

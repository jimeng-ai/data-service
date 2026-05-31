package com.jimeng.dataserver.ai.rag.service.contextualize;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
import com.jimeng.dataserver.ai.claude.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.claude.usage.UsageExtractor;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClientException;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationResult;
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
    private final AiModelCallRecordService recordService;
    private final UsageExtractor usageExtractor;

    /** 4xx 类错误（鉴权、余额、参数等）连续出现达到此阈值即 fail-fast，避免在已知会失败的请求上空转。 */
    private static final int CLIENT_ERROR_FAIL_FAST_THRESHOLD = 3;

    public String generateContext(String fullDocument, String chunkContent) {
        if (!ragProperties.getContextualization().isEnabled()) return "";
        if (StrUtil.isBlank(fullDocument) || StrUtil.isBlank(chunkContent)) return "";
        return providerRegistry.contextualization().generateContext(fullDocument, chunkContent).text();
    }

    /**
     * 给一组 chunks 串行生成 context（保证 prompt cache 命中）。
     *
     * <p>遇到 4xx 累计 {@value #CLIENT_ERROR_FAIL_FAST_THRESHOLD} 次即抛出，让 Rabbit retry 接管，
     * 避免一份 1000 chunk 的文档把 401/402 重复请求 1000 遍。
     *
     * <p>计费：每个 chunk 的 usage 在此累加，整篇文档**只落一行** {@code ai_model_call_log}
     * （biz_type=rag_contextualization），避免上千行写放大。
     */
    public List<String> generateContexts(String fullDocument, List<String> chunks) {
        List<String> out = new ArrayList<>(chunks.size());
        boolean enabled = ragProperties.getContextualization().isEnabled();
        int consecutiveClientErrors = 0;

        // 按文档汇总 usage（各 chunk 之和），循环结束后落一行
        UsageAccumulator agg = new UsageAccumulator();
        long start = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!enabled || StrUtil.isBlank(fullDocument) || StrUtil.isBlank(chunk)) {
                out.add("");
                continue;
            }
            try {
                ContextualizationResult r = providerRegistry.contextualization().generateContext(fullDocument, chunk);
                out.add(r.text() == null ? "" : r.text());
                agg.add(usageExtractor.extract(parseUsage(r.usageJson())), r.model());
                consecutiveClientErrors = 0;
            } catch (ContextualizationClientException e) {
                consecutiveClientErrors++;
                log.warn("chunk[{}/{}] contextualization 4xx status={}（连续 {} 次）",
                        i + 1, chunks.size(), e.getStatus(), consecutiveClientErrors);
                if (consecutiveClientErrors >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
                    // fail-fast 前先把已累计的 usage 落账，避免丢掉已发生的真实开销
                    flush(agg, start);
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
        flush(agg, start);
        return out;
    }

    /** 把累计 usage 落成一行；无任何计费调用时跳过。失败不影响入库主流程。 */
    private void flush(UsageAccumulator agg, long startMs) {
        if (agg.callCount == 0) {
            return;
        }
        try {
            recordService.recordAggregatedCall(
                    providerRegistry.activeProvider(),
                    "rag:contextualization",
                    agg.model,
                    "rag_contextualization",
                    agg.toUsage(),
                    agg.callCount,
                    (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("contextualization 聚合计费落账失败: {}", e.getMessage());
        }
    }

    private static JSONObject parseUsage(String usageJson) {
        if (StrUtil.isBlank(usageJson) || !JSONUtil.isTypeJSON(usageJson)) {
            return null;
        }
        return JSONUtil.parseObj(usageJson);
    }

    /** 整篇文档各 chunk 的 usage 累加器（input/output/cache 求和）。 */
    private static final class UsageAccumulator {
        private int input;
        private int output;
        private int total;
        private int cacheRead;
        private int cacheWrite;
        private boolean cacheReadInInput;
        private String model;
        private int callCount;

        void add(NormalizedUsage u, String model) {
            if (u == null) {
                return;
            }
            input += nz(u.getInputTokens());
            output += nz(u.getOutputTokens());
            total += nz(u.getTotalTokens());
            cacheRead += nz(u.getCacheReadTokens());
            cacheWrite += nz(u.getCacheWriteTokens());
            // 同一篇文档同一 provider，shape 一致，取任一即可
            cacheReadInInput = u.isCacheReadInInput();
            if (this.model == null && StrUtil.isNotBlank(model)) {
                this.model = model;
            }
            callCount++;
        }

        NormalizedUsage toUsage() {
            NormalizedUsage u = new NormalizedUsage();
            u.setInputTokens(input);
            u.setOutputTokens(output);
            u.setTotalTokens(total > 0 ? total : input + output);
            u.setCacheReadTokens(cacheRead);
            u.setCacheWriteTokens(cacheWrite);
            u.setCacheReadInInput(cacheReadInInput);
            JSONObject raw = new JSONObject();
            raw.set("aggregated_calls", callCount);
            raw.set("input_tokens", input);
            raw.set("output_tokens", output);
            raw.set("cache_read_input_tokens", cacheRead);
            raw.set("cache_creation_input_tokens", cacheWrite);
            u.setRawJson(raw.toString());
            return u;
        }

        private static int nz(Integer v) {
            return v == null ? 0 : v;
        }
    }
}

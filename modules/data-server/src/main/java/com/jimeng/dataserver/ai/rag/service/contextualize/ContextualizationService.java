package com.jimeng.dataserver.ai.rag.service.contextualize;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClientException;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationResult;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
        boolean enabled = ragProperties.getContextualization().isEnabled();
        if (!enabled || StrUtil.isBlank(fullDocument) || chunks.isEmpty()) {
            List<String> out = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) out.add("");
            return out;
        }
        int concurrency = Math.max(1, ragProperties.getContextualization().getConcurrency());
        return (concurrency <= 1 || chunks.size() <= 1)
                ? generateContextsSerial(fullDocument, chunks)
                : generateContextsParallel(fullDocument, chunks, concurrency);
    }

    /** 串行版：每次复用上篇文档前缀，最大化 prompt cache 命中。concurrency=1 或单 chunk 时走这里。 */
    private List<String> generateContextsSerial(String fullDocument, List<String> chunks) {
        List<String> out = new ArrayList<>(chunks.size());
        int consecutiveClientErrors = 0;
        UsageAccumulator agg = new UsageAccumulator();
        long start = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (StrUtil.isBlank(chunk)) {
                out.add("");
                continue;
            }
            try {
                ContextualizationResult r = providerRegistry.contextualization().generateContext(fullDocument, chunk);
                out.add(r.text() == null ? "" : r.text());
                agg.add(usageExtractor.extract(parseUsage(r.usageJson())), r.model());
                consecutiveClientErrors = 0;
            } catch (ContextualizationClientException e) {
                if (isTransient(e.getStatus())) {
                    // 429 限流 / 408 超时是瞬时错误：不计入永久错误 fail-fast 阈值，该片降级留空（后续回退原文）
                    log.warn("chunk[{}/{}] contextualization 瞬时错误 status={}，该片降级留空",
                            i + 1, chunks.size(), e.getStatus());
                    out.add("");
                    continue;
                }
                consecutiveClientErrors++;
                log.warn("chunk[{}/{}] contextualization 永久 4xx status={}（连续 {} 次）",
                        i + 1, chunks.size(), e.getStatus(), consecutiveClientErrors);
                if (consecutiveClientErrors >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
                    flush(agg, start);
                    throw new ServiceException(
                            ExceptionCode.SERVICE_UNAVAILABLE,
                            "Contextualization 连续 " + consecutiveClientErrors +
                                    " 次返回 " + e.getStatus() +
                                    "，疑似 provider 鉴权/余额/参数问题，已 fail-fast 终止本次入库。body=" +
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

    /**
     * 有界并发版：用固定线程池并行对各 chunk 生成 context，大文档入库提速数倍。
     *
     * <p>关键点：
     * <ul>
     *   <li>结果写入按下标的数组（各线程写不同下标，无竞争）；usage 在并发结束后<b>串行累加</b>，避免加锁。</li>
     *   <li>用 {@link MdcAsyncSupport#wrap} 把 TenantContext/MDC/userId 捎到 worker 线程——
     *       否则计费 recordService 取不到租户。wrap 在提交时（消费者线程，已 set 租户）捕获。</li>
     *   <li>fail-fast：4xx 累计达阈值即置中止标志，后续任务跳过（不再空转调用），最终抛出由 Rabbit 接管。</li>
     * </ul>
     */
    private List<String> generateContextsParallel(String fullDocument, List<String> chunks, int concurrency) {
        int n = chunks.size();
        String[] out = new String[n];
        ContextualizationResult[] results = new ContextualizationResult[n];
        AtomicInteger clientErrors = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        int poolSize = Math.min(concurrency, n);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final String chunk = chunks.get(i);
                if (StrUtil.isBlank(chunk)) {
                    out[idx] = "";
                    continue;
                }
                // wrap 在此（消费者线程）捕获租户/MDC，再在 worker 线程恢复
                futures.add(pool.submit(MdcAsyncSupport.wrap(null, () -> {
                    if (clientErrors.get() >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
                        out[idx] = ""; // 已判定失败，跳过省去无谓调用
                        return;
                    }
                    try {
                        ContextualizationResult r =
                                providerRegistry.contextualization().generateContext(fullDocument, chunk);
                        out[idx] = r.text() == null ? "" : r.text();
                        results[idx] = r;
                    } catch (ContextualizationClientException e) {
                        if (isTransient(e.getStatus())) {
                            // 429 限流 / 408 超时：瞬时错误，不计入永久错误 fail-fast，该片降级留空
                            log.warn("chunk[{}/{}] contextualization 瞬时错误 status={}，该片降级留空",
                                    idx + 1, n, e.getStatus());
                            out[idx] = "";
                        } else {
                            int cnt = clientErrors.incrementAndGet();
                            log.warn("chunk[{}/{}] contextualization 永久 4xx status={}（累计 {} 次）",
                                    idx + 1, n, e.getStatus(), cnt);
                            out[idx] = "";
                        }
                    } catch (Exception e) {
                        log.warn("chunk[{}/{}] contextualization 失败，使用原文 fallback: {}",
                                idx + 1, n, e.getMessage());
                        out[idx] = "";
                    }
                })));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // 任务内部已捕获并兜底；这里只防御 get 本身的中断/执行异常
                    log.warn("contextualization 任务等待异常: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // fail-fast：永久 4xx 过多（鉴权/余额/参数），落已发生开销后抛出，交给 Rabbit 重试。
        // 429 限流 / 408 超时不计入这里（瞬时错误，对应 chunk 已降级留空），避免限流误判整篇失败。
        if (clientErrors.get() >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
            aggregateAndFlush(results, start);
            throw new ServiceException(
                    ExceptionCode.SERVICE_UNAVAILABLE,
                    "Contextualization 累计 " + clientErrors.get() +
                            " 次返回永久 4xx，疑似 provider 鉴权/余额/参数问题，已终止本次入库。");
        }
        aggregateAndFlush(results, start);

        List<String> list = new ArrayList<>(n);
        for (String s : out) list.add(s == null ? "" : s);
        return list;
    }

    /**
     * 瞬时错误（不计入永久错误 fail-fast）：429 限流、408 请求超时。
     * 这类是 provider 临时压背，单片降级留空即可；只有 400/401/402/403 等永久错误才该 fail-fast。
     */
    private static boolean isTransient(int status) {
        return status == 429 || status == 408;
    }

    /** 并发结束后串行累加各 chunk 的 usage 并落一行账。 */
    private void aggregateAndFlush(ContextualizationResult[] results, long start) {
        UsageAccumulator agg = new UsageAccumulator();
        for (ContextualizationResult r : results) {
            if (r != null) {
                agg.add(usageExtractor.extract(parseUsage(r.usageJson())), r.model());
            }
        }
        flush(agg, start);
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

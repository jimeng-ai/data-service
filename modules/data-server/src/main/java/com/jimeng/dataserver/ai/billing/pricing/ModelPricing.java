package com.jimeng.dataserver.ai.billing.pricing;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型计费：按「美元 / 百万 token」的单价表，算出单次调用的 {@code cost_usd}。
 *
 * <p>计费公式（与产品给定的公式一致，扩展了缓存项）：
 * <pre>
 *   cost = 计费输入/1e6 * 输入价
 *        + 输出/1e6     * 输出价        （输出已含思考 token，不另计）
 *        + 缓存读/1e6   * 缓存读价
 *        + 缓存写/1e6   * 缓存写价
 * </pre>
 * 其中「计费输入」对 OpenAI 形态会先扣掉已含在 prompt 内的 cached_tokens，避免重复计费
 * （见 {@link NormalizedUsage#isCacheReadInInput()}）。
 *
 * <p>当前单价为代码内常量（USD/百万 token，近似值，按模型档位粗分）。
 * TODO 后续迁到 Nacos 配置中心，支持不改代码热更新价格。
 * 人民币换算不在此处固化：{@code cost_usd} 永远存 USD，由报表层按可配置汇率换算。
 */
@Slf4j
@Component
public class ModelPricing {

    /** 单价：USD / 百万 token。 */
    private record Price(double input, double output, double cacheRead, double cacheWrite) {}

    /**
     * 模型前缀 → 单价。按插入顺序匹配（model 全小写后 contains 命中即用），
     * 因此更具体的档位（如 -mini）必须排在更宽泛的前面。
     */
    private static final Map<String, Price> PRICES = new LinkedHashMap<>();

    /** 命中不到时的兜底价（按 sonnet 档位）。 */
    private static final Price DEFAULT_PRICE = new Price(3.0, 15.0, 0.30, 3.75);

    /**
     * embedding 类模型的兜底价（仅按输入计费，无输出/缓存）。
     * 模型名千变万化（text-embedding-* / bge-* / jina-* / 各家私有名），命中不到具体条目时，
     * 只要识别出是 embedding 调用，就用这个低价兜底——绝不能落到 {@link #DEFAULT_PRICE}（sonnet 档），
     * 否则 embedding 会被按对话模型百倍高估。
     */
    private static final Price EMBEDDING_DEFAULT = new Price(0.02, 0.0, 0.0, 0.0);

    /**
     * rerank 类模型的兜底价（仅按输入 token 计费：query + 所有候选文档）。
     * 注意：Cohere 系 rerank 实际按「搜索次数」计费而非 token，本表的 token 价对其只是近似；
     * 当前激活的 302.ai qwen3-rerank 是按 token 的，单价为近似值，TODO 以账单/官方价校准。
     */
    private static final Price RERANK_DEFAULT = new Price(0.10, 0.0, 0.0, 0.0);

    static {
        // Embedding（仅输入计费；放最前，按 contains 命中，具体名在前）
        PRICES.put("text-embedding-3-large", new Price(0.13, 0.0, 0.0, 0.0));
        PRICES.put("text-embedding-3-small", new Price(0.02, 0.0, 0.0, 0.0));
        PRICES.put("text-embedding-ada", new Price(0.10, 0.0, 0.0, 0.0));
        PRICES.put("bge", new Price(0.02, 0.0, 0.0, 0.0));
        PRICES.put("jina-embedding", new Price(0.02, 0.0, 0.0, 0.0));
        // Rerank（仅输入计费；具体名在前，"rerank" 泛匹配兜底见 priceFor）
        PRICES.put("qwen3-rerank", new Price(0.10, 0.0, 0.0, 0.0));
        // Claude
        PRICES.put("claude-opus", new Price(15.0, 75.0, 1.50, 18.75));
        PRICES.put("claude-haiku", new Price(0.80, 4.0, 0.08, 1.0));
        PRICES.put("claude-sonnet", new Price(3.0, 15.0, 0.30, 3.75));
        PRICES.put("claude-3-haiku", new Price(0.25, 1.25, 0.03, 0.30));
        PRICES.put("claude", new Price(3.0, 15.0, 0.30, 3.75));
        // OpenAI（cacheWrite 不适用，置 0）
        PRICES.put("gpt-4o-mini", new Price(0.15, 0.60, 0.075, 0.0));
        PRICES.put("gpt-4o", new Price(2.50, 10.0, 1.25, 0.0));
        PRICES.put("gpt-4.1-mini", new Price(0.40, 1.60, 0.10, 0.0));
        PRICES.put("gpt-4.1", new Price(2.0, 8.0, 0.50, 0.0));
        PRICES.put("o4-mini", new Price(1.10, 4.40, 0.275, 0.0));
        PRICES.put("o3-mini", new Price(1.10, 4.40, 0.55, 0.0));
        PRICES.put("o3", new Price(2.0, 8.0, 0.50, 0.0));
        PRICES.put("o1", new Price(15.0, 60.0, 7.50, 0.0));
        PRICES.put("gpt-4", new Price(2.50, 10.0, 1.25, 0.0));
        PRICES.put("gpt-3.5", new Price(0.50, 1.50, 0.0, 0.0));
    }

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * 计算单次调用成本（USD）。token 全为空时返回 0。
     */
    public BigDecimal compute(String model, NormalizedUsage usage) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        Price p = priceFor(model);

        int input = nz(usage.getInputTokens());
        int output = nz(usage.getOutputTokens());
        int cacheRead = nz(usage.getCacheReadTokens());
        int cacheWrite = nz(usage.getCacheWriteTokens());

        // OpenAI 的 cached_tokens 已含在 prompt 内，按缓存价单独计，需从普通输入里扣掉。
        int billableInput = usage.isCacheReadInInput() ? Math.max(0, input - cacheRead) : input;

        BigDecimal cost = perMillion(billableInput, p.input())
                .add(perMillion(output, p.output()))
                .add(perMillion(cacheRead, p.cacheRead()))
                .add(perMillion(cacheWrite, p.cacheWrite()));

        return cost.setScale(8, RoundingMode.HALF_UP);
    }

    private Price priceFor(String model) {
        if (StrUtil.isBlank(model)) {
            return DEFAULT_PRICE;
        }
        String key = model.toLowerCase().trim();
        for (Map.Entry<String, Price> e : PRICES.entrySet()) {
            if (key.contains(e.getKey())) {
                return e.getValue();
            }
        }
        // embedding 兜底：识别为嵌入模型时用 EMBEDDING_DEFAULT，避免落到 sonnet 档把成本高估百倍。
        if (key.contains("embed")) {
            log.warn("embedding 模型 [{}] 无匹配定价，使用 embedding 兜底价；如需精确请在 ModelPricing 补充单价", model);
            return EMBEDDING_DEFAULT;
        }
        // rerank 兜底：同理避免落到 sonnet 档。
        if (key.contains("rerank")) {
            log.warn("rerank 模型 [{}] 无匹配定价，使用 rerank 兜底价；如需精确请在 ModelPricing 补充单价", model);
            return RERANK_DEFAULT;
        }
        log.warn("模型 [{}] 无匹配定价，使用兜底价(sonnet档)；如需精确请在 ModelPricing 补充单价", model);
        return DEFAULT_PRICE;
    }

    private static BigDecimal perMillion(int tokens, double pricePerMillion) {
        if (tokens <= 0 || pricePerMillion <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(pricePerMillion)
                .multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, 10, RoundingMode.HALF_UP);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}

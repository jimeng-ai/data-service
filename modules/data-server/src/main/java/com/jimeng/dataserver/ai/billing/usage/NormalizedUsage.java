package com.jimeng.dataserver.ai.billing.usage;

import lombok.Data;

/**
 * 归一化后的 token 用量。屏蔽 OpenAI / Anthropic 两套 {@code usage} 字段命名差异。
 *
 * <p>关键语义差异（影响计费，勿删）：
 * <ul>
 *   <li><b>Anthropic</b>：{@code input_tokens} 与缓存 token 互斥——缓存读/写单独计数，不含在 input 内。</li>
 *   <li><b>OpenAI</b>：{@code prompt_tokens} 已包含 {@code cached_tokens}（cached 是 prompt 的子集）。</li>
 * </ul>
 * 因此 {@link #cacheReadInInput} 标记缓存读是否已被算进 {@link #inputTokens}，
 * 供 {@code ModelPricing} 扣减，避免对 OpenAI 重复计费。
 */
@Data
public class NormalizedUsage {

    /** 输入 token（OpenAI prompt_tokens / Anthropic input_tokens），原样保留用于落库展示。 */
    private Integer inputTokens;

    /** 输出 token（OpenAI completion_tokens / Anthropic output_tokens），已含思考 token。 */
    private Integer outputTokens;

    /** 总 token，缺失时由 input+output 推导。 */
    private Integer totalTokens;

    /** 缓存读取 token（命中缓存，单价远低于普通输入）。 */
    private Integer cacheReadTokens;

    /** 缓存写入 token（写入缓存，单价略高于普通输入；仅 Anthropic）。 */
    private Integer cacheWriteTokens;

    /** 推理/思考 token（仅 OpenAI 单列；Anthropic 已并入 output，留空）。仅展示，不重复计费。 */
    private Integer reasoningTokens;

    /** 缓存读取是否已包含在 {@link #inputTokens} 内（OpenAI=true，Anthropic=false）。 */
    private boolean cacheReadInInput;

    /** 供应商返回的原始 usage 对象 JSON，便于将来回溯重算。 */
    private String rawJson;
}

package com.jimeng.dataserver.ai.provider.spi;

/**
 * 单次 contextualization 文本调用的结果：文本 + 该次调用的原始 usage JSON + 模型名。
 *
 * <p>之所以把 usage 返回给调用方而不在 client 内自行记账：contextualization 对一份文档的
 * 每个 chunk 都要调一次（且把整篇文档当 cached 前缀重发），逐次落库会在 ai_model_call_log
 * 里产生上千行。改为把 usage 上抛，由 {@code ContextualizationService} 按整篇文档汇总成一行。
 *
 * @param text      模型返回的上下文文本（失败/降级时为 ""）
 * @param usageJson 响应里的 {@code usage} 对象原文（无则为 null）
 * @param model     本次调用使用的模型名（用于计费命中单价）
 */
public record ContextualizationResult(String text, String usageJson, String model) {

    public static ContextualizationResult empty() {
        return new ContextualizationResult("", null, null);
    }
}

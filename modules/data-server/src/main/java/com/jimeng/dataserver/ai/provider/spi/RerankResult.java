package com.jimeng.dataserver.ai.provider.spi;

import java.util.List;

/**
 * 一次 rerank 调用的结果：命中列表 + 该次 usage 原文 + 模型名。
 *
 * <p>把 usage 抛给 {@code RerankService} 统一计费（client 不自记账）：rerank 多按 token 收费
 * （query + 所有候选文档），部分 provider 响应里带 {@code usage}、部分不带——不带时由 service
 * 用 query+documents 估算 token。
 *
 * @param hits      排序命中（index + relevanceScore）
 * @param usageJson 响应里的 {@code usage} 对象原文（无则为 null，调用方据此决定是否估算）
 * @param model     本次调用使用的模型名（用于计费命中单价）
 */
public record RerankResult(List<RerankHit> hits, String usageJson, String model) {

    public static RerankResult of(List<RerankHit> hits, String usageJson, String model) {
        return new RerankResult(hits, usageJson, model);
    }
}

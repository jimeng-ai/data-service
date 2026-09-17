package com.jimeng.dataserver.ai.connector.generation;

/**
 * 唤醒语义层生成排空器的最小边界。
 *
 * <p>O5 的编排器实现本接口；对账器只负责发现已有到期队列并发出一次唤醒，不复制排空逻辑，也不自动续跑中断批次。
 */
@FunctionalInterface
public interface SemanticGenerationKick {

    void kick();
}

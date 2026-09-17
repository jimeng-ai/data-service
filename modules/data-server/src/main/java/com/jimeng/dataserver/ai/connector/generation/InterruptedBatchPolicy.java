package com.jimeng.dataserver.ai.connector.generation;

/** 前置条件不通过时，已有 {@code INTERRUPTED} 批次的处置策略。 */
public enum InterruptedBatchPolicy {
    /** 不动批次，照常走单次推导。 */
    LEAVE,
    /** 作废旧批次后走单次推导。 */
    SUPERSEDE,
    /** 保留旧批次且不回落，等瞬时故障恢复后续跑。 */
    KEEP
}

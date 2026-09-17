package com.jimeng.dataserver.ai.connector.generation;

/**
 * 语义层生成批次内单表的机器原因码。枚举 {@link #name()} 直接作为拒绝原因前缀落库。
 */
public enum TableReasonCode {
    STRUCTURE_CHANGED,
    STRUCTURE_UNSTABLE,
    SUBMIT_LIMIT,
    DISPATCH_LIMIT,
    AGENT_SKIPPED,
    NOT_DESCRIBED,
    NAME_TOO_LONG
}

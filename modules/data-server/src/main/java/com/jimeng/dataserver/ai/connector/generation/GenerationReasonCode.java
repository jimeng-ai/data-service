package com.jimeng.dataserver.ai.connector.generation;

/**
 * 语义层生成批次的机器原因码。枚举 {@link #name()} 直接写入数据库，不另做字符串映射。
 */
public enum GenerationReasonCode {
    HEARTBEAT_LOST,
    SHUTDOWN,
    INTERNAL_ERROR,
    CONNECTION_DISABLED,
    AGENT_DISABLED,
    TOKEN_CAP,
    CLI_BUDGET,
    GAVE_UP_RATIO,
    SNAPSHOT_CHURN,
    SANDBOX_REJECTED,
    SANDBOX_AUTH,
    SANDBOX_UNAVAILABLE,
    SANDBOX_BUSY,
    NO_CALLBACK,
    NO_PROGRESS,
    CLAIM_BUSY,
    MODEL_MISMATCH,
    NO_OUTPUT,
    EMPTY_REPLACE,
    SNAPSHOT_NOT_APPLICABLE,
    SNAPSHOT_REFRESH_FAILED,
    CONNECTION_DELETED,
    SUPERSEDED
}

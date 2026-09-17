package com.jimeng.dataserver.ai.connector.generation;

/**
 * agent 路径的单条前置条件结论。
 *
 * @param silent 不通过时走单次推导是否不算降级、不写降级原因
 */
public record PreconditionVerdict(boolean pass, boolean silent, String reason) {

    public static PreconditionVerdict allowed() {
        return new PreconditionVerdict(true, false, null);
    }

    public static PreconditionVerdict rejected(boolean silent, String reason) {
        return new PreconditionVerdict(false, silent, reason);
    }
}

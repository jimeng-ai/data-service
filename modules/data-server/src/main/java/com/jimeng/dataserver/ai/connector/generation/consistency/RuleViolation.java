package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;

/**
 * 一条规则判出的一个违规。机器码不在这里——它是规则自己的 {@link SemanticConsistencyRule#code()}，由注册表统一贴上，
 * 规则没有机会写错成别人的码。
 *
 * @param message 给 agent 看的一句中文，能照着改
 */
public record RuleViolation(Severity severity, String message) {

    /** 严重级别，决定这一条记 rejected 还是 dropped（设计文档 7.8 的 D 步）。 */
    public enum Severity {
        /** 违反规则、可以修正：agent 改了之后重交整张表。 */
        REJECT(SemanticRowAssembler.STATUS_REJECTED),
        /** 按规则不入库，不需要重提。 */
        DROP(SemanticRowAssembler.STATUS_DROPPED);

        private final String status;

        Severity(String status) {
            this.status = status;
        }

        /** 回调接口 entries[].status 的字面量，与 {@code SemanticRowAssembler.EntryOutcome#status()} 同一组。 */
        public String status() {
            return status;
        }
    }

    public static RuleViolation reject(String message) {
        return new RuleViolation(Severity.REJECT, message);
    }

    public static RuleViolation drop(String message) {
        return new RuleViolation(Severity.DROP, message);
    }
}

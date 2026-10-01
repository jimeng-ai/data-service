package com.jimeng.dataserver.ai.connector.service;

import java.util.Set;

/**
 * 「这条连接的语义层刚变了，请跑一遍补全链」（数据星图设计 v3 §4）。进程内的 Spring 事件，不出网、不落库。
 *
 * <p>为什么用事件而不是直接调用：补全链要回调推导服务派发采样核对，推导服务又是它的触发方，构造器互相依赖会闭合启动期的环；
 * 事件是单向的。
 *
 * @param deriveNote      推导刚写下的那句结论；采样核对把它当阶段 note 的前缀保留
 * @param validationScope 触发方原本要核对的表（按 {@link SemanticRowAssembler#fold} 折叠）；{@code null} = 全部，空 = 没有
 */
public record SemanticEnrichmentRequest(Long connectorId, String tenantId, Trigger trigger, String deriveNote,
                                        Set<String> validationScope) {

    public enum Trigger {
        /** 规则推导（整体重新生成）成功：原本要核对全部表。 */
        DERIVE,
        /** 增量补写成功：原本只核对这批多出了行的表。 */
        DERIVE_ADDED,
        /** agent 生成定稿：这条路径原来就不做采样核对，补全链同样跳过。 */
        AGENT,
        /** 定时对账发现输入变了：只核对关系发现新补出关系的表。 */
        RECONCILE
    }

    public static SemanticEnrichmentRequest afterDerive(Long connectorId, String tenantId, String note) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.DERIVE, note, null);
    }

    public static SemanticEnrichmentRequest afterAddedDerive(Long connectorId, String tenantId, String note,
                                                             Set<String> scope) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.DERIVE_ADDED, note,
                scope == null ? Set.of() : Set.copyOf(scope));
    }

    public static SemanticEnrichmentRequest afterAgentGeneration(Long connectorId, String tenantId) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.AGENT, null, Set.of());
    }

    public static SemanticEnrichmentRequest reconcile(Long connectorId, String tenantId) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.RECONCILE, null, Set.of());
    }

    /** 这次触发原本会不会派发采样核对（agent 路径不会）。补全链排不上时据此决定要不要自己先把核对派出去。 */
    public boolean validates() {
        return trigger != Trigger.AGENT && (validationScope == null || !validationScope.isEmpty());
    }
}

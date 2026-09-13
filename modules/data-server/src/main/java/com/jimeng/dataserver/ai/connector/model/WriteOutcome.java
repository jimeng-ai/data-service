package com.jimeng.dataserver.ai.connector.model;

/**
 * 一次写请求的<b>两种</b>归宿：已经执行完，或者进了审批队列。
 *
 * <p>把它做成一个显式的两态返回值，而不是「执行了就返回 WriteResult、要审批就抛异常」，
 * 是因为<b>「需要审批」不是失败</b>。若用异常表达，模型收到的是一条错误消息，
 * 它下一步多半会去改写语句重试——而正确的下一步是告诉用户「已提交，等审批」。
 *
 * <p>不作为 HTTP 响应体出网（工具层会展开成 map，管理台走的是 {@code PendingWriteView}），
 * 所以不受 jackson 2.11.1 序列化不了 record 那条限制。
 *
 * @param result     已执行时的结果；进队列时为 null
 * @param approvalId 进队列时的审批单 id；已执行时为 null
 */
public record WriteOutcome(WriteResult result, Long approvalId) {

    public static WriteOutcome done(WriteResult result) {
        return new WriteOutcome(result, null);
    }

    public static WriteOutcome pending(Long approvalId) {
        return new WriteOutcome(null, approvalId);
    }

    public boolean pendingApproval() {
        return approvalId != null;
    }
}

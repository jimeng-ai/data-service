package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 一条待审批写操作的对外视图。
 *
 * <p><b>它不是 record。</b>本仓实际生效的 jackson-databind 是 2.11.1（被
 * logstash-logback-encoder:7.4 传递带入），record 序列化要 2.12+，会报
 * 「No serializer found … no properties discovered」。要出网的 DTO 一律 Lombok。
 *
 * <h3>与实体的两处差别都是刻意的</h3>
 * <ul>
 *   <li><b>没有 tenantId</b>——它不该随响应出网，与 {@link ConnectorAuditView} 同一条纪律。</li>
 *   <li><b>补了 agentName</b>——审批界面要回答的第一个问题是「谁要改这张表」，
 *       一串雪花 id 回答不了。Agent 已被删时留空：审批记录记的是历史事实，
 *       不该跟着 Agent 消失。</li>
 * </ul>
 *
 * <h3>★ 看到这个对象的人，必须先看 {@link #status}</h3>
 * {@code PendingWriteService.approve} <b>不用异常表达「没执行」</b>（并发抢锁失败、已过期、
 * 执行失败都会正常返回），它把结果全部写在这个字段上。
 * 把「approve 返回了」读成「已经批准并执行了」，就会向操作者报一个假的成功。
 */
@Schema(description = "待审批的写操作")
@Data
@Builder
public class PendingWriteView {

    /** 字符串下发：雪花 id 超过 JS 的安全整数范围，走 number 会在前端静默丢精度。 */
    private String id;

    @Schema(description = "提交时间")
    private Date time;

    private String connectorName;

    private String agentId;

    @Schema(description = "Agent 名称。Agent 已删除时为空——审批记录不随 Agent 消失")
    private String agentName;

    @Schema(description = "操作类型：INSERT / UPDATE / DELETE，由护栏解析语句得出")
    private String operation;

    @Schema(description = "目标表名。审批时先看这个：改的是哪张表")
    private String targetTable;

    @Schema(description = "待执行的语句原文。批准时【照这段文本执行】，界面必须完整展示，不要省略中间")
    private String statementText;

    @Schema(description = "★ 发起这次写请求的那轮对话的 trace_id。审批的人要能顺着它回到"
            + "「模型当时为什么要写这一条」——只看一条 SQL 是判不出它该不该执行的")
    private String traceId;

    @Schema(description = "提交时预估会改动多少行。**不是承诺**：估算在提交时、执行在批准时，"
            + "中间数据会变。估不出来为 null（如 INSERT ... SELECT）")
    private Integer estimatedRows;

    @Schema(description = "★ 状态：PENDING | APPROVED | REJECTED | EXPIRED | FAILED。approve 的真实结果看这里，不要看「有没有抛异常」")
    private String status;

    @Schema(description = "实际影响行数，执行后回填。为空 = 还没执行或结果未知")
    private Integer affectedRows;

    @Schema(description = "失败/拒绝/过期原因，已脱敏，可直接展示")
    private String errorDetail;

    @Schema(description = "审批人 user-id。定时过期作废的行为空，表示没有人做过决定")
    private String decidedBy;

    private Date decidedAt;

    @Schema(description = "过期时间。过了就不再允许批准——陈年请求的 WHERE 条件命中的可能已是另一批行")
    private Date expiresAt;
}

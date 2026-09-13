package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 待审批的写操作。对应连接写策略里的 {@code REQUIRE_APPROVAL} 这一档。
 *
 * <h3>这张表存在的意义：把「生成操作」和「执行操作」拆开，中间插一个人</h3>
 * 产品方案第 8 节对「写自动」的风险评注是<b>业务事故</b>，对「写需审批」是<b>有人兜底</b>。
 * 差别不在于哪一档更严，而在于<b>失败是否会被发现</b>：模型直接写错一条 UPDATE，
 * 数据就改了，没人知道；而走审批的写操作，在被执行之前一定被一个人读过一遍。
 *
 * <h3>为什么是「仅追加 + 状态流转」，没有唯一键</h3>
 * 同一个 Agent 在同一毫秒对同一张表提两条一样的写请求是<b>合法的</b>（比如给两个不同的人补同一类备注，
 * 语句碰巧相同）。加唯一键去重会把第二条吞掉，而吞掉的那条正是事后要回溯的证据。
 * 无唯一键也顺带躲开了本仓反复踩的「软删行占着唯一键，第二次插入撞车」。
 *
 * <h3>{@link #statementText} 是执行源，不是展示文案</h3>
 * 批准时<b>就是照这一列的内容去执行</b>的。所以它绝不能被截断落库——截断过的语句要么语法错，
 * 要么 {@code WHERE} 少掉一半、变成范围完全不同的一条更新。超长必须在<b>提交时</b>就拒掉。
 * 同理，它落库之后<b>不再改写</b>：人批准的是这段文本，改掉它就说不清「当时批准的到底是什么」。
 *
 * @TableName connector_pending_write
 */
@Schema(description = "待审批的写操作")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_pending_write")
@Data
public class ConnectorPendingWrite extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    /** 冗余存一份连接名：连接被删掉之后，这条审批记录还得说得清当时要改的是哪个系统。 */
    @Schema(description = "连接名（冗余，连接删除后仍可读）")
    @TableField("connector_name")
    private String connectorName;

    /**
     * 提交这条写请求的 Agent。
     *
     * <p><b>批准执行时要按它重建 Agent 上下文</b>：审批执行的是「当初那个 Agent 的请求」，
     * 权限就该按那个 Agent 判，而不是按点批准的人判。所以这一列不是展示用的冗余字段，
     * 它参与授权，不能为空。
     */
    @Schema(description = "提交写请求的 Agent ID")
    @TableField("agent_id")
    private Long agentId;

    @Schema(description = "链路 ID，与 ai_trace 对齐：可回到「模型当时为什么要写这一条」的完整上下文")
    @TableField("trace_id")
    private String traceId;

    /** INSERT / UPDATE / DELETE，由 {@code WriteSqlGuard} 解析语句类型得出，不是模型自报的。 */
    @Schema(description = "操作类型：INSERT / UPDATE / DELETE")
    @TableField("operation")
    private String operation;

    /** 同样来自护栏的解析结果。审批界面靠它让人一眼看出「改的是哪张表」。 */
    @Schema(description = "目标表名")
    @TableField("target_table")
    private String targetTable;

    @Schema(description = "待执行的语句原文。批准时【照这一列执行】，因此不截断、不改写")
    @TableField("statement_text")
    private String statementText;

    /**
     * PENDING → APPROVED / REJECTED / EXPIRED / FAILED。
     *
     * <p><b>FAILED 是终态，不回退 PENDING。</b>回退会让人以为「还能再点一次」，
     * 而那条语句可能已经部分生效（超时、连接断在提交途中），再点一次就是第二次执行。
     */
    @Schema(description = "状态：PENDING | APPROVED | REJECTED | EXPIRED | FAILED")
    @TableField("status")
    private String status;

    @Schema(description = "实际影响行数，执行后回填。为空说明还没执行或执行结果未知")
    @TableField("affected_rows")
    private Integer affectedRows;

    /**
     * 提交时预估的影响行数。<b>不是承诺</b>——估算在提交时、执行在批准时，中间数据会变。
     * 它存在的唯一理由是：审批的人光看语句判不出范围，而 {@code affectedRows}
     * 要等执行完才有值，那时候批已经批完了。估不出来为 null。
     */
    private Integer estimatedRows;

    /**
     * 失败 / 拒绝 / 过期的原因。
     *
     * <p><b>只放 {@code ConnectorException.getSafeDetail()} 或平台自己写的文案</b>，
     * 绝不放原始异常消息——JDBC 异常常带完整 SQL、主机名、连接参数，而这一列会随审批列表出网。
     */
    @Schema(description = "失败/拒绝/过期原因（已脱敏，可直接展示）")
    @TableField("error_detail")
    private String errorDetail;

    /** 提交时请求头里的 user-id。可空：Agent 跑在异步线程上时不一定捎带得到。 */
    @Schema(description = "提交人 user-id，可空")
    @TableField("submitted_by")
    private String submittedBy;

    @Schema(description = "审批人 user-id")
    @TableField("decided_by")
    private String decidedBy;

    @Schema(description = "审批时间")
    @TableField("decided_at")
    private Date decidedAt;

    /**
     * 有效期。过期未处理即作废。
     *
     * <p>不是为了省地方，是因为<b>写请求会腐烂</b>：模型三天前按当时的数据算出来的
     * {@code WHERE} 条件，今天命中的可能已经是另一批行了。让一条陈年请求还能被点「批准」，
     * 等于允许拿旧判断去改新数据。
     */
    @Schema(description = "过期时间")
    @TableField("expires_at")
    private Date expiresAt;
}

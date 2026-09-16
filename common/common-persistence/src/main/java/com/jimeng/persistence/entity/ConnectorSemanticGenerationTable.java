package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 语义层生成批次内的一张表一行：片号、状态、各种计数、结构指纹、最近一次退回原因。
 *
 * <p>「中断之后再点重新生成只补未覆盖的表」靠的就是这张表：已覆盖 = {@code status=DONE} 且
 * {@link #structureStamp} 与当前快照算出的本表指纹一致。指纹<b>按表</b>比，别的表刷新不会作废整轮。
 *
 * <h3>三个计数分开记，不合成一个 attempts</h3>
 * {@link #dispatchCount} 是「随已开始的运行被派发却没提交」、{@link #submitCount} 是「通过形状与范围校验的提交」、
 * {@link #structureRetries} 是「因结构变化被重新排队」。三者的上限与放弃原因各不相同；
 * 合成一个数，sandbox 繁忙重派会把模型真正交过几次吃掉，放弃的原因也就说不清。
 *
 * <h3>★ 状态迁移全部带「当前状态」条件做 CAS</h3>
 * 同一批次的提交在批次行锁下串行，但编排器的对账与片结算跑在另一条线程上。
 * 先读再 {@code updateById} 会让两边都判定通过、互相覆盖。
 *
 * <h3>★ 只做物理删除</h3>
 * 唯一键 {@code (generation_id, object_name)} 不含 {@code deleted}，理由同 {@link ConnectorSemanticGeneration}。
 *
 * @TableName connector_semantic_generation_table
 */
@Schema(description = "语义层生成批次内的每张表")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_semantic_generation_table")
@Data
public class ConnectorSemanticGenerationTable extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "connector_semantic_generation.id")
    @TableField("generation_id")
    private Long generationId;

    /** 与 {@code connector_schema.object_name} 同宽（255），名字超 191 的表也能原样留下、记成 SKIPPED。 */
    @Schema(description = "表名，与 connector_schema.object_name 原样一致")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "TABLE / VIEW，从快照拷贝")
    @TableField("object_type")
    private String objectType;

    /** 入批时从快照拷贝。本批次内只按这一列排序：之后的刷新重新排名，不该打乱进行中的批次。 */
    @Schema(description = "入批时从 connector_schema 拷贝的重要性排名")
    @TableField("importance_rank")
    private Integer importanceRank;

    @Schema(description = "最近一次被分到第几片（从 1 开始）；从未派发为 NULL")
    @TableField("slice_no")
    private Integer sliceNo;

    @Schema(description = "PENDING / DISPATCHED / DONE / SKIPPED / GAVE_UP / REMOVED")
    @TableField("status")
    private String status;

    @Schema(description = "随已开始的运行被派发却没有提交的次数；达到上限转 GAVE_UP")
    @TableField("dispatch_count")
    private Integer dispatchCount;

    @Schema(description = "通过形状与范围校验的提交次数；达到上限且未 DONE 转 GAVE_UP")
    @TableField("submit_count")
    private Integer submitCount;

    @Schema(description = "因结构变化被重新排队的次数；超过 2 转 GAVE_UP")
    @TableField("structure_retries")
    private Integer structureRetries;

    /** {@code ConnectorSemanticService.tableStamp} 的值，完整 64 位 sha256，不截短。 */
    @Schema(description = "最近一次 DONE 时本表的结构指纹")
    @TableField("structure_stamp")
    private String structureStamp;

    @Schema(description = "最近一次提交写入的行数")
    @TableField("accepted_rows")
    private Integer acceptedRows;

    @Schema(description = "最近一次提交被退回或丢弃的条目数")
    @TableField("dropped_rows")
    private Integer droppedRows;

    /** 格式「代码: 安全文案」，截到 500。代码取自 {@code TableReasonCode} 或一致性规则码。 */
    @Schema(description = "最近一次退回、跳过、放弃或重新排队的原因（机器码 + 安全文案）")
    @TableField("last_reject_reason")
    private String lastRejectReason;

    @Schema(description = "最近一次派发时间")
    @TableField("dispatched_at")
    private Date dispatchedAt;

    @Schema(description = "最近一次 DONE 的时间")
    @TableField("done_at")
    private Date doneAt;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 语义层重新生成（STAGED）的暂存行。
 *
 * <p>已经成功生成过的连接再点「重新生成」时，新一轮先写这里，<b>全部表覆盖完</b>才在一个事务里
 * 删掉主表的机器推断行、把暂存行搬进去（{@code ConnectorSemanticMapper#moveStagedIn}）。
 * 问数的读路径是不加锁的一致性读，所以读者要么看到整份旧版，要么看到整份新版，不会看到半新半旧。
 *
 * <h3>为什么放库里，不放 Redis</h3>
 * push main 即部署。半轮暂存必须能扛过重启，中断后续跑才有东西可接；放 Redis，一次发版就得整轮重来。
 *
 * <h3>为什么没有 source 列、id 要原样搬入</h3>
 * 暂存行一律是 INFERRED，搬入时 SQL 里写字面量。收尾是整批 {@code INSERT ... SELECT}，必须带主键；
 * 而 {@code BaseEntity} 的 id 是 {@code ASSIGN_ID}、在 Java 侧生成，SQL 侧拿不到新的雪花 id。
 * 暂存行插入时已分配过全局唯一的雪花 id，又在同一事务里删除，直接复用。
 *
 * <p>{@code answered_*}、{@code trace_id}、{@code history_json} 只属于 HUMAN 行，这里没有。
 * 只做物理删除（{@code ConnectorSemanticStagedMapper} 的三个 physicalDelete*），理由同 {@link ConnectorSemantic}。
 *
 * @TableName connector_semantic_staged
 */
@Schema(description = "语义层重新生成的暂存行")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_semantic_staged")
@Data
public class ConnectorSemanticStaged extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "connector_semantic_generation.id")
    @TableField("generation_id")
    private Long generationId;

    /**
     * 哪张表的提交产出了这一行。同一张表重交、结构变化作废这张表时，按它删本表的 OBJECT / FIELD / JOIN。
     * CAVEAT 属于整条连接、按 term 合并，这一列只记第一个提交它的表，作留痕，不参与按表删除。
     */
    @Schema(description = "哪张表的提交产出了这一行；CAVEAT 记第一个提交它的表")
    @TableField("owner_object")
    private String ownerObject;

    @Schema(description = "OBJECT / FIELD / JOIN / CAVEAT（agent 永远不产出 METRIC）")
    @TableField("scope")
    private String scope;

    @Schema(description = "同 connector_semantic.object_name；CAVEAT 为空串")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "同 connector_semantic.field_name；JOIN 存左侧列名")
    @TableField("field_name")
    private String fieldName;

    @Schema(description = "同 connector_semantic.term；仅 CAVEAT 使用")
    @TableField("term")
    private String term;

    @Schema(description = "同 connector_semantic.gloss")
    @TableField("gloss")
    private String gloss;

    @Schema(description = "同 connector_semantic.detail_json")
    @TableField("detail_json")
    private String detailJson;

    @Schema(description = "COMMENT / DATA / NAME；GUESS 在提交时已丢弃；CAVEAT 为 NULL")
    @TableField("evidence")
    private String evidence;

    @Schema(description = "0-100")
    @TableField("confidence")
    private Integer confidence;

    @Schema(description = "agent 路径不做采样验证，恒为 NONE")
    @TableField("verified")
    private String verified;

    @Schema(description = "DRAFT；收尾预检发现 JOIN 右端结构已变时改成 STALE")
    @TableField("status")
    private String status;

    @Schema(description = "NONE / FIELD / JOIN，由服务端按快照计算")
    @TableField("anchor_kind")
    private String anchorKind;

    @Schema(description = "写入时的锚点 sha256，由服务端按快照计算")
    @TableField("anchor_hash")
    private String anchorHash;
}

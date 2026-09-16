package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 连接器自描述结果缓存：一个对象一行（一张表 / 一个接口 / 一个目录 / 一个主题）。
 *
 * <p><b>为什么单独一张表、不塞进 {@code connection} 行里：</b>它可能很大（几百张表的结构）、
 * 会独立刷新、而且要能对比前后变化。塞进实例行意味着每次读连接都拖着一坨 longtext。
 *
 * <p><b>为什么带 tenant_id：</b>{@code ai_model_call_content} 那张大 JSON 侧表没有 tenant_id
 * 也不在租户白名单里，照抄它就是零租户过滤。本表必须在 {@code TENANT_AWARE_TABLES} 里。
 *
 * <p>刷新策略是<b>物理删除重插</b>（见 {@code ConnectorSchemaMapper#physicalDeleteByConnector}），
 * 所以唯一键上不需要、也不该带 deleted。
 *
 * @TableName connector_schema
 */
@Schema(description = "连接器自描述结果缓存")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_schema")
@Data
public class ConnectorSchema extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    /** TABLE / VIEW / ENDPOINT / DIR / TOPIC…… 由各连接器自己定义，框架不解释。 */
    @Schema(description = "对象类型：TABLE / VIEW / ENDPOINT / DIR / TOPIC")
    @TableField("object_type")
    private String objectType;

    @Schema(description = "对象名（表名 / 接口路径 / 目录 / 主题名）")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "对象注释，给模型当语义线索用")
    @TableField("object_comment")
    private String objectComment;

    /**
     * 对象细节的完整 JSON（字段列表、类型、注释、索引…）。
     *
     * <p>只在 Agent 明确要看某个对象时才回灌模型——目录级概览走 {@link #objectName} +
     * {@link #objectComment} 就够，全量注入放不下几百张表。
     */
    @Schema(description = "对象细节 JSON")
    @TableField("detail_json")
    private String detailJson;

    /**
     * {@link #detailJson} 的 sha256。
     *
     * <p><b>用途是「客户偷偷加了一个字段，我们应该能知道」：</b>刷新时比对哈希就能挑出变化的对象，
     * 而不是拿两坨 JSON 肉眼对。没有它，结构漂移是静默的——模型照着旧结构写 SQL，报错才发现。
     */
    @Schema(description = "detail_json 的 sha256，用于识别结构漂移")
    @TableField("content_hash")
    private String contentHash;

    /**
     * 本次刷新时这张表在连接器目录里的位置，从 1 开始，越小越重要。
     *
     * <p>从前「重要性」只在连接器拉目录时现算（MySQL：估算行数数量级降序 → 被外键引用次数降序 → 表名升序），
     * 不落库，快照读回按表名字母序。语义层生成要按重要性切片，所以落快照时把位置一起存下来。
     * 只存位置、不存档位与引用数：位置已经完整表达了三级排序的结果，而改目录条目模型的改动面太大。
     *
     * <p><b>NULL = 本列上线之前落的快照</b>，读取时按 id 升序回退（同一份快照单线程依次插入，雪花 id 升序即目录顺序），
     * 下一次刷新自动补上。
     *
     * <p>⚠ 迁移 {@code V20260917__connector_semantic_generation.sql} 必须先于部署映射了本列的后端：
     * 列不存在时，所有读 connector_schema 的查询（结构刷新、conn_catalog、推导）一律 Unknown column。
     */
    @Schema(description = "刷新时在连接器目录里的位置，从 1 开始，越小越重要；NULL 为旧快照")
    @TableField("importance_rank")
    private Integer importanceRank;

    @Schema(description = "本行的同步时间")
    @TableField("synced_at")
    private Date syncedAt;
}

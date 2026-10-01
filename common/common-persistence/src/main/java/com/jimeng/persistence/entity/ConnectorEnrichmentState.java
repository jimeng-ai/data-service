package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 语义层补全链（关系发现 → 采样核对 → 业务文字）在一条连接上的运行状态，每个连接一行。
 *
 * <p>{@link #claimAt} 是认领：非空且未满 30 分钟就是正在跑，跑的过程中每一步续期。「正在跑」不另存一个状态值——
 * 进程在中途被杀掉时，一个写死的 RUNNING 会永远挂着；认领时间过期就自然让出。
 * 租户隔离表（已加入 TENANT_AWARE_TABLES）；不产生软删死行，理由同 {@link ConnectorBusinessView}。
 */
@Schema(description = "数据星图：语义层补全链的运行状态")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_enrichment_state")
@Data
public class ConnectorEnrichmentState extends BaseEntity {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @TableField("tenant_id")
    private String tenantId;

    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "认领时间（秒）；非空且未满 30 分钟 = 正在跑")
    @TableField("claim_at")
    private Date claimAt;

    @Schema(description = "上次结果 READY / FAILED；null = 从没跑完过")
    @TableField("last_status")
    private String lastStatus;

    @Schema(description = "上次尝试时的输入指纹")
    @TableField("input_fingerprint")
    private String inputFingerprint;

    @Schema(description = "上次模型关系那一遍的输入指纹")
    @TableField("relation_pass_fingerprint")
    private String relationPassFingerprint;

    @Schema(description = "上次开始时间；失败后的退避按它算")
    @TableField("last_attempt_at")
    private Date lastAttemptAt;

    @TableField("finished_at")
    private Date finishedAt;

    @Schema(description = "关系发现：新增 / 替换条数、来源分布、失败原因")
    @TableField("relation_note")
    private String relationNote;

    @Schema(description = "业务文字：生成条数、校验退回条数、失败原因")
    @TableField("view_note")
    private String viewNote;
}

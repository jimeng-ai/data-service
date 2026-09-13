package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 连接器使用留痕：每调一次连接器写一行。<b>仅追加，不更新、不删除。</b>
 *
 * <p><b>为什么不寄生在 ai_trace_step 上：</b>{@code TraceRecorder.recordStep} 的 step_index 是
 * 读-改-写无锁（并发记步会重复），且每记一步要 select+insert+update 三次写。审计要的是
 * 「只追加、能按 租户/连接/时间 做区间查询」，形状不一样，硬挤会把两边都拖坏。
 * trace 那边照常记它的步骤，两者通过 {@link #traceId} 对得上。
 *
 * <p><b>为什么无唯一键：</b>同一个 Agent 在同一毫秒重复跑同一条查询是合法的，去重会丢证据。
 * 无唯一键顺带躲开了「软删行占唯一键」那个坑。
 *
 * @TableName connector_audit
 */
@Schema(description = "连接器使用留痕")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_audit")
@Data
public class ConnectorAudit extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    /** 冗余存一份连接名：连接被删掉之后，审计记录还得说得清当时调的是哪一条。 */
    @Schema(description = "连接名（冗余，连接删除后审计仍可读）")
    @TableField("connector_name")
    private String connectorName;

    /**
     * 发起调用的 Agent。
     *
     * <p><b>允许为空，但空是个坏消息：</b>{@code AgentContext} 只在请求体带 agent_id 时才设置，
     * 三个入口的前置鉴权又各不相同。为空说明这次调用没带上 Agent 身份，排查越权时要盯这类行。
     */
    @Schema(description = "Agent ID，可能为空")
    @TableField("agent_id")
    private Long agentId;

    @Schema(description = "链路 ID，与 ai_trace 对齐")
    @TableField("trace_id")
    private String traceId;

    /** QUERY / DESCRIBE / INVOKE / HEALTH，对应 {@code Capability}。 */
    @Schema(description = "能力：QUERY / DESCRIBE / INVOKE / HEALTH")
    @TableField("capability")
    private String capability;

    @Schema(description = "具体操作（工具名 / 方法名 / HTTP method+path）")
    @TableField("operation")
    private String operation;

    /**
     * Agent 写的那条语句原文（SQL / 请求体摘要）。审计的核心证据：出事时全靠它。
     *
     * <p>注意它只该<b>往库里写</b>。回灌模型的结果里不要再带一遍——模型自己写的东西没必要回喂，
     * 白烧 token。
     */
    @Schema(description = "执行的语句原文")
    @TableField("statement_text")
    private String statementText;

    @Schema(description = "返回行数")
    @TableField("row_count")
    private Integer rowCount;

    @Schema(description = "耗时毫秒")
    @TableField("elapsed_ms")
    private Integer elapsedMs;

    @Schema(description = "是否成功")
    @TableField("success")
    private Boolean success;

    /** {@code ConnectorErrorCode} 的名字，不是 HTTP 码也不是数据库厂商错误码。 */
    @Schema(description = "统一错误分类")
    @TableField("error_code")
    private String errorCode;

    /**
     * 失败详情。<b>只放 ConnectorException 的 safeDetail</b>，绝不放原始异常消息——
     * JDBC / HTTP 异常常带 SQL 片段、主机名、连接参数，而审计列表是会出网给人看的。
     */
    @Schema(description = "失败详情（已脱敏的安全文案）")
    @TableField("error_detail")
    private String errorDetail;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 语义层生成批次：一次「生成语义层」一行。
 *
 * <p>生成不再是一次读完整个库，而是 data-service 编排多次短 sandbox 运行、按重要性逐片生成、可续跑。
 * <b>库既是队列也是真相源</b>：批次、表状态、进度都在库里，内存只放当前运行的临时计数——
 * push main 即部署，内存状态每次发版都会清零，放内存里的进度一发版就没了，续跑也无从谈起。
 *
 * <h3>★ 不映射 {@code active_connector_id}</h3>
 * 表上有一个虚拟生成列 {@code active_connector_id}（未结束时等于 {@code connector_id}，终态为 NULL），
 * 唯一键 {@code (tenant_id, active_connector_id)} 靠它在库层保证「每条连接最多一个未结束的批次」。
 * 本实体<b>刻意不声明这个字段</b>：MyBatis-Plus 一旦往生成列里写值，MySQL 就报 ERROR 3105。
 * 要看它就自己写 SQL 查，不要为了「字段齐全」把它加回来。
 *
 * <h3>★ 只做物理删除</h3>
 * 唯一键不含 {@code deleted}。{@code BaseMapper.delete / deleteById} 在全局 {@code @TableLogic} 下是软删，
 * 死行照样占着唯一键——终态行的派生列虽然是 NULL，但「未结束时被软删」的那一行会让这条连接永远建不出新批次。
 * 本表的行作为运行记录保留，不提供删除入口。
 *
 * <h3>★ 置空要用 {@code LambdaUpdateWrapper.set}</h3>
 * {@code current_run_id}、{@code owner_token}、{@code not_before}、{@code reason_code} 这几列在状态迁移里要写回 NULL
 * （运行结束立即置空 {@code current_run_id}，在途回调才会立刻失效）。本仓库没有配 {@code FieldStrategy}，
 * {@code updateById(实体)} 会<b>静默跳过</b>值为 null 的字段——看起来写成功了，列上还是旧值。
 * 状态迁移一律用带当前状态条件的 {@code LambdaUpdateWrapper}，返回行数就是有没有抢到。
 *
 * <p>取值与状态机见设计文档 {@code docs/superpowers/specs/2026-09-16-semantic-layer-agent-design.md} 的 8.3、8.10。
 *
 * @TableName connector_semantic_generation
 */
@Schema(description = "语义层生成批次")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_semantic_generation")
@Data
public class ConnectorSemanticGeneration extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    /** 模式在建批次时按 {@code semantic_synced_at} 是否为空判定，之后不变。 */
    @Schema(description = "DIRECT=按表直写主表，写完立即可见 / STAGED=写暂存表，全部覆盖完再用一个事务替换")
    @TableField("mode")
    private String mode;

    @Schema(description = "CONNECTOR_CREATED=新建连接后自动触发 / MANUAL_REGENERATE=管理台点「重新生成」")
    @TableField("trigger_kind")
    private String triggerKind;

    /**
     * 触发的超管 {@code sys_user.id}。agent 路径必填：它是回调 token 的 {@code id} claim（签发时 {@code String.valueOf}）。
     * 不能靠 {@code create_user}：编排跑在后台线程上，拿不到请求上下文，那一列是空的。
     */
    @Schema(description = "触发的超管 sys_user.id")
    @TableField("triggered_by")
    private Long triggeredBy;

    @Schema(description = "QUEUED / RUNNING / FINALIZING / INTERRUPTED 为未结束；READY / FAILED / FELL_BACK / CANCELLED 为终态")
    @TableField("status")
    private String status;

    @Schema(description = "最近一次下发给 sandbox 的模型名")
    @TableField("model")
    private String model;

    /** 上游实际出现过的模型名。出现配置外的名字即停批：实测发 opus 的名字，DeepSeek 实际跑 v4-pro 并按 Pro 计费。 */
    @Schema(description = "各片 message_start 里实际出现过的模型名，逗号分隔去重")
    @TableField("models_seen")
    private String modelsSeen;

    @Schema(description = "N：纳入生成范围的表数（不含 SKIPPED / REMOVED）")
    @TableField("total_tables")
    private Integer totalTables;

    @Schema(description = "k：DONE 的表数，即进度的分子")
    @TableField("done_tables")
    private Integer doneTables;

    @Schema(description = "不在范围内的表数：没有列 / 表名超 191 / 描述失败")
    @TableField("skipped_tables")
    private Integer skippedTables;

    @Schema(description = "已放弃的表数")
    @TableField("gave_up_tables")
    private Integer gaveUpTables;

    @Schema(description = "生成期间从结构快照里消失的表数")
    @TableField("removed_tables")
    private Integer removedTables;

    @Schema(description = "M：预计总片数，每片开始时按剩余表数重新估算")
    @TableField("slice_count")
    private Integer sliceCount;

    @Schema(description = "i：当前或最近一次派发的片号，从 1 开始；0 表示还没派发过")
    @TableField("current_slice_no")
    private Integer currentSliceNo;

    /** 回调过滤器以它校验 token 的 {@code rid}。运行结束或取消后立即置 NULL（见类注释「置空」）。 */
    @Schema(description = "正在跑的 sandbox runId；运行结束或取消后立即置 NULL")
    @TableField("current_run_id")
    private String currentRunId;

    @Schema(description = "当前这次运行收到的回调次数；为 0 说明回调没到达")
    @TableField("current_run_callbacks")
    private Integer currentRunCallbacks;

    @Schema(description = "当前持有者（编排器每轮排空生成的随机串），心跳、推进、收尾都以它做 CAS")
    @TableField("owner_token")
    private String ownerToken;

    @Schema(description = "最近一次心跳。RUNNING / FINALIZING 超过 180 秒未更新视为持有者已死")
    @TableField("heartbeat_at")
    private Date heartbeatAt;

    @Schema(description = "与 connection.semantic_claim_at 同值的认领凭据，续期时两边一起换新值")
    @TableField("claim_at")
    private Date claimAt;

    @Schema(description = "首次认领前 connection.semantic_status 的值；STAGED 中断或失败时据此恢复")
    @TableField("prev_semantic_status")
    private String prevSemanticStatus;

    @Schema(description = "被再次触发续跑的次数")
    @TableField("resume_count")
    private Integer resumeCount;

    @Schema(description = "QUEUED 行最早可被排空的时间（连接认领暂时抢不到时用）")
    @TableField("not_before")
    private Date notBefore;

    @Schema(description = "各片 summary.usage 累加：input_tokens")
    @TableField("input_tokens")
    private Long inputTokens;

    @Schema(description = "累加：output_tokens（含推理 token）")
    @TableField("output_tokens")
    private Long outputTokens;

    @Schema(description = "累加：cache_read_input_tokens")
    @TableField("cache_read_tokens")
    private Long cacheReadTokens;

    @Schema(description = "累加：cache_creation_input_tokens")
    @TableField("cache_write_tokens")
    private Long cacheWriteTokens;

    @Schema(description = "拿不到 summary.usage 的片数（被杀、超时），用量因此是下界")
    @TableField("slices_without_usage")
    private Integer slicesWithoutUsage;

    /** 成本闸按<b>本轮</b>用量判定（累计减去它），每次续跑重新计额；否则续跑一次就立刻撞上上一轮用掉的额度。 */
    @Schema(description = "最近一次续跑时 input_tokens + output_tokens + cache_write_tokens 的值")
    @TableField("tokens_at_resume")
    private Long tokensAtResume;

    /** 只作留痕，不参与判定：所有 limits 每片从实时配置读。不含 auth-token。 */
    @Schema(description = "建批次时 connector.semantic.agent.* 的快照（不含 auth-token）")
    @TableField("config_json")
    private String configJson;

    @Schema(description = "中断 / 失败 / 降级 / 取消的机器码（GenerationReasonCode）")
    @TableField("reason_code")
    private String reasonCode;

    /** 会进管理台。只放平台自己写的安全文案，绝不放原始异常（异常消息里可能带着客户库的地址与账号）。 */
    @Schema(description = "给人看的原因，只放平台自己写的安全文案")
    @TableField("note")
    private String note;

    @Schema(description = "第一次进入 RUNNING 的时间")
    @TableField("started_at")
    private Date startedAt;

    @Schema(description = "进入终态的时间")
    @TableField("finished_at")
    private Date finishedAt;
}

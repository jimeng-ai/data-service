package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 连接器实例的对外视图。
 *
 * <p><b>刻意是一个自定义 DTO，而不是直接回传 {@code Connection} 实体。</b>
 * 实体上的 {@code tenantId} 没有 {@code @JsonIgnore}、也不被 {@code stripSecret} 清除，
 * 于是它随每一个连接接口的响应出网——这是既有的一个洞。新接口不继承它：
 * 这里<b>没有 tenantId 字段</b>，也<b>没有任何凭据相关字段</b>，从类型上就不可能漏。
 */
@Schema(description = "连接器实例")
@Data
@Builder
public class ConnectorView {

    private String id;
    private String name;
    private String displayName;

    @Schema(description = "类型标识，如 MYSQL / HTTP")
    private String kind;

    @Schema(description = "类型的中文名，直接展示")
    private String kindLabel;

    @Schema(description = "非敏感参数。敏感参数不会出现在这里（连占位串都不给——"
            + "给了就等于泄露「这个字段配没配」）")
    private Map<String, Object> params;

    private String transport;
    private String status;

    @Schema(description = "写操作开放程度：FORBIDDEN | REQUIRE_APPROVAL | AUTO")
    private String writePolicy;

    @Schema(description = "写策略的中文名，直接展示：只读 / 写需审批 / 写自动")
    private String writePolicyLabel;

    @Schema(description = "探测后回填的实际可用能力")
    private List<String> capabilities;

    @Schema(description = "UNKNOWN | HEALTHY | UNHEALTHY")
    private String healthState;

    private Date healthCheckedAt;

    @Schema(description = "不健康的原因，已脱敏，可直接展示给客户")
    private String healthReason;

    @Schema(description = "是否已通过只读验证。未通过的连接不该被当成安全的")
    private boolean readonlyVerified;

    private Date readonlyVerifiedAt;

    private Date createTime;

    // ── 语义层状态 ────────────────────────────────────────────────────────────
    // 接入时零人工：没有表单、没有确认页，推导是背着人跑的。那就必须有一个地方能回答
    // 「它跑了没有、跑成了没有」，否则「零人工」在界面上等同于「什么都没发生」。
    // 三个字段一起给，才拼得出「语义层：生成中 / 已生成（覆盖 12 张表）/ 失败（原因）/ 不适用」这一行字。

    // 这里把后端能产出的状态【一个不漏地列全】，是因为前端多半写成一张 code→文案 的映射表：
    // 漏掉一个，界面上那一格就是空白——而「空白」和「没跑过」在人眼里是一回事，
    // 于是一条根本推不了语义层的 HTTP 连接，会被当成一条推导卡住了的连接，有人去点重试，永远点不出结果。
    @Schema(description = "语义层推导状态，后端只会产出这五个值之一，且永远不为 null"
            + "（存量行上的 NULL 在 ConnectorService.toView 里归一成 NONE）："
            + "NONE=没跑过 / RUNNING=生成中 / READY=已生成 / FAILED=失败 / "
            + "NOT_APPLICABLE=不适用（这种连接器类型不支持自描述，没有结构可推，重跑也不会变）。"
            + "★ FAILED 和 NOT_APPLICABLE 都不影响连接可用——语义层是叠加的注解，不是连接的前置条件")
    private String semanticStatus;

    @Schema(description = "语义层最近一次【成功生成】的时间。失败与「不适用」都不盖戳，"
            + "所以它回答的是「上次什么时候还是好的」——一次失败的重试不会把它抹掉。"
            + "为空表示从来没有成功生成过。它不能单独用来判断当前状态，要看 semanticStatus")
    private Date semanticSyncedAt;

    @Schema(description = "本次/最近一次推导开始（认领）的时间。semanticStatus=RUNNING 时，"
            + "界面上「生成中」该显示的是它，不是 semanticSyncedAt")
    private Date semanticClaimAt;

    // 失败分支写进来的是异常摘要（类名 + message），没有过统一脱敏这道手续，而推导现在会去连客户的库，
    // 连不上时那句 message 里常常带着客户的主机名和账号。本接口限企业超管，所以留着；
    // 但别把这个字段原样转发到客户侧的任何界面上。
    @Schema(description = "推导结果摘要（覆盖了几张表、丢弃了多少条）、失败原因、或「推导已关闭」。"
            + "失败原因是原始异常摘要，未统一脱敏，仅供超管排查，不要原样转给客户")
    private String semanticNote;

    // ── 数据出库档位 ──────────────────────────────────────────────────────────
    // 三个字段一起给，是因为这一格在界面上不是一个下拉框，而是一句要被人读懂的承诺：
    // 档位值（提交时原样回填）、短名（下拉框显示）、这一档到底什么东西会出库（选中时展开显示）。
    // 只给前两个，界面上就只剩三个人畜无害的词，客户看不出第 3 档和第 2 档差在哪 ——
    // 而那正是他唯一真正需要看懂的一次选择。

    @Schema(description = "数据出库档位，后端只会产出这三个值之一，且永远不为 null"
            + "（存量行的 NULL 在 ConnectorService.toView 里归一成默认档 DERIVED_STATS）："
            + "METADATA_ONLY=第1档 只出结构 / DERIVED_STATS=第2档【默认】可出派生统计 / "
            + "SAMPLE_VALUES=第3档 可出真实取值（默认关闭，须企业超管显式开启）。"
            + "★ 编辑表单必须把这个值原样回填进 ConnectorUpsert.semanticDataTier 再提交："
            + "那边留空等于第2档，不等于「保持原样」，漏回填会把一条已开第3档的连接降档")
    private String semanticDataTier;

    @Schema(description = "档位的中文短名，直接展示：第 1 档 · 纯元数据 / 第 2 档 · 派生统计 / 第 3 档 · 样本值")
    private String semanticDataTierLabel;

    // 这句话是【给客户看的安全说明】，不是开发者注释。它和 DDL 列注释、SemanticDataTier
    // 的 javadoc 是同一句话的三个落点，来源都是 SemanticDataTier.egressStatement()——
    // 分叉了就等于没有安全说明，所以不要在前端另写一份文案。
    //
    // 第 3 档那句刻意把「真实取值」说死了：同类产品（Quick BI 的「维值学习」）索引的就是真实取值，
    // 而它的官方数据安全页回避了这件事。回避换来的是客户在不知情的前提下点了同意，
    // 那不是同意。宁可这句话吓退一部分人，也不要让任何人是被含糊其辞劝进来的。
    @Schema(description = "这一档【具体什么东西会离开客户的数据库】，一句话，可直接展示给客户。"
            + "与 DDL 列注释同源（SemanticDataTier.egressStatement()）——前端不要另写文案，分叉等于没有安全说明")
    private String semanticDataTierEgress;
}

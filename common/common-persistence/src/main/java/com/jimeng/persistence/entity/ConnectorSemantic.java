package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 语义层：跟着一条连接走的「说明书」，一条语义断言一行。
 *
 * <p>客户给的是一个只读账号，库里是 {@code t_ord_mst} / {@code t_ord_dtl} 这样的表名。模型看到的
 * 全部信息就是表名加上可能有也可能没有的注释，于是它只能猜——猜错任何一个，答案就是错的，
 * 而且往往不报错，就是一个看起来很正常的数字。本表存的就是那份用来消除猜测的文字描述。
 *
 * <p><b>为什么不塞进 {@link ConnectorSchema#getDetailJson()}（决策 S2）：</b>那张表的
 * {@code refresh()} 是物理删除重插，而它的 diff 只比 {@code content_hash}——那个哈希算的是
 * <i>结构指纹</i>，不含 detail_json 里的自定义键。人工确认过的口径写进去会被下一次刷新
 * <b>静默</b>抹掉，且 diff 里一条记录都不会有。两者必须分生命周期。
 *
 * <p>还有一条更硬的理由：{@code connector_schema} 每行的主键在每次 refresh 后都是<b>新的雪花
 * id</b>，任何外键指过去在第一次刷新后就悬空。所以本表用
 * {@code (connectorId, objectName, fieldName)} 这组<b>字符串名字</b>定位，绝不引用它的 id。
 *
 * <p><b>本表不产生软删死行</b>，这是 {@code uk_connector_semantic} 不含 deleted 的前提：
 * {@code INFERRED} 行重新推导时物理删除重插，{@code HUMAN} 行永不删除、只原地 UPDATE。
 * 不要给本表加逻辑删除入口。
 *
 * @TableName connector_semantic
 */
@Schema(description = "连接器语义层：表用途 / 字段含义 / 表关系 / 业务口径")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_semantic")
@Data
public class ConnectorSemantic extends BaseEntity {

    @Schema(description = "租户 ID（与 X-Tenant-Id 对齐）")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "connection.id")
    @TableField("connector_id")
    private Long connectorId;

    /**
     * 判别符。刻意<b>一张表 + scope</b>，而不是实体/关系/指标/样例四张表：
     * JOIN 对 HTTP 连接器是「接口调用链」、GLOSSARY 对所有类型同形、FIELD 对没有 QUERY 能力的
     * 连接器天然为空。建四张表等于先赌一个还没被验证的模型。
     * 与 {@code WritePolicy} 做成枚举而不是四个布尔是同一条纪律。
     */
    @Schema(description = "OBJECT=表用途 / FIELD=字段含义 / JOIN=表关系 / METRIC=口径 / CAVEAT=告诫")
    @TableField("scope")
    private String scope;

    @Schema(description = "对象名（表名）。METRIC 行为空串——口径挂在整条连接上，不挂某张表")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "字段名。仅 FIELD/JOIN 用；JOIN 存左侧列名")
    @TableField("field_name")
    private String fieldName;

    /** 确定性改写靠它<b>精确匹配</b>，不做模糊识别与推理。 */
    @Schema(description = "业务词条，如「销售额」。仅 METRIC 用")
    @TableField("term")
    private String term;

    @Schema(description = "给模型看的那一句话。注入 conn_catalog 时截断到 80 字，完整版只在 conn_describe 给")
    @TableField("gloss")
    private String gloss;

    @Schema(description = "按 scope 定形的结构化细节 JSON")
    @TableField("detail_json")
    private String detailJson;

    /** {@code HUMAN} 永不被推断覆盖，只被标 STALE。 */
    @Schema(description = "INFERRED=机器推的 / HUMAN=人在对话里答的 / IMPORTED=客户库注释等一手事实")
    @TableField("source")
    private String source;

    /**
     * 分界线是<b>有没有外部依据</b>，不是<b>能不能被 SQL 验证</b>，也不是模型自己说它确不确定。
     * 模型看到 {@code st tinyint} 取值 0/1/2/3 会非常自信地写出「0=待支付」——那段话完全合理、
     * 完全可能错，而且没有任何验证能发现它错。判据必须是依据的来源。
     */
    @Schema(description = "依据来源：COMMENT=客户库注释 / DATA=数据里查得到 / NAME=名字直白 / GUESS=只能靠常识猜")
    @TableField("evidence")
    private String evidence;

    @Schema(description = "0-100，仅 INFERRED 有意义")
    @TableField("confidence")
    private Integer confidence;

    @Schema(description = "采样验证结论：CONFIRMED / WEAK / REJECTED / UNDECIDABLE / NONE")
    @TableField("verified")
    private String verified;

    @Schema(description = "DRAFT / CONFIRMED / STALE")
    @TableField("status")
    private String status;

    /**
     * 锚点<b>粒度按 scope 分</b>，不能统一按表。{@code connector_schema.content_hash} 是整表指纹
     * （表名|类型 + 每列 name:type:nullable:comment），客户加一列它就变；若 OBJECT 行也锚它，
     * 「这张表是订单主表」会因为一个无关列被标成 STALE——而加一列并不改变这句话。
     *
     * <ul>
     *   <li>{@code NONE}：不锚结构。OBJECT / CAVEAT 用，只有整张表消失才失效。</li>
     *   <li>{@code FIELD}：锚这一列自己的指纹。改别的列不影响它。</li>
     *   <li>{@code JOIN}：锚左右两列指纹的组合。任一端变了这条关系就不可信。</li>
     *   <li>{@code COLUMN_SET}：锚 sql_fragment 引用到的列集合。METRIC 有 SQL 片段时用。</li>
     * </ul>
     */
    @Schema(description = "锚的是什么：NONE / FIELD / JOIN / COLUMN_SET")
    @TableField("anchor_kind")
    private String anchorKind;

    @Schema(description = "写入时对应锚点的 sha256。刷新后对不上 → STALE，而不是悄悄变成假的")
    @TableField("anchor_hash")
    private String anchorHash;

    /**
     * 不能依赖 {@code BaseEntity.createUser}：口径是在<b>对话流</b>里沉淀的，而对话跑在
     * {@code streamExecutor} 上，{@code MdcAsyncSupport.wrap} 只传 {@code TenantContext} 和 MDC，
     * <b>不传 {@code RequestContextHolder}</b>，于是 {@code MyMetaObjectHandler.getCurrentUserId()}
     * 返回 null、create_user 是空的。而设计明确要求「记住是谁、什么时候答的」——
     * 配置期填口径的通常是懂业务的人，对话里回答的可能是任何一个业务方，
     * 他说「扣退款」也许只是他那个场景扣。所以显式存。
     */
    @Schema(description = "在对话里回答这条口径的人（sys_user.id）")
    @TableField("answered_by")
    private String answeredBy;

    /** 快照留存，不实时 join——人离职改名后，「9 月 13 日由张三确认」应该还是张三。 */
    @Schema(description = "回答人显示名（快照）")
    @TableField("answered_name")
    private String answeredName;

    @Schema(description = "回答时间")
    @TableField("answered_at")
    private Date answeredAt;

    @Schema(description = "沉淀这条口径的那次对话。审批页能顺着 trace_id 回到对话，口径也应该能")
    @TableField("trace_id")
    private String traceId;

    /**
     * 只存<b>旧值</b>——BaseEntity 已经免费给了 who/when。
     *
     * <p>平台不提供任何管理台的口径纠正入口，任何能对话的人都能覆盖口径且不做权限区分。
     * 这是那个已知代价的唯一取证材料：等到发现口径被改坏时，没有历史就连
     * 「什么时候开始错的」都查不出来。记录变更不等于重新引入审核。
     */
    @Schema(description = "追加型变更留痕 [{at,by,by_name,from_gloss,from_detail,trace_id}]，保留最近 20 条")
    @TableField("history_json")
    private String historyJson;
}

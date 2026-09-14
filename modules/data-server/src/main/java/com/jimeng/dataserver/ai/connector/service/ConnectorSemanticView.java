package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.ConnectorSemantic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 一条语义断言的对外视图。
 *
 * <p><b>不直接回传 {@code ConnectorSemantic} 实体</b>，理由和 {@link ConnectorSchemaView} 一模一样，
 * 而且这里更重：实体上的 {@code tenantId} 没有 {@code @JsonIgnore}，直接回传就是把
 * {@code Connection} 实体上那个既有的洞在新接口上再开一次；而这张表存的是客户的表名、字段名、
 * 业务口径，比结构快照更敏感（见建表脚本的表头注释）。
 *
 * <p>{@code detailJson} 在这里就解析好。它是一段按 scope 定形的 JSON（JOIN 的两端、METRIC 的
 * 歧义点），丢原始字符串给前端等于让解析逻辑分散到两端，改了形状要同时改两处。
 *
 * <p><b>刻意不回传 {@code anchorHash}。</b>它是一串只对服务端有意义的 sha256，界面上摆一串
 * 十六进制既看不懂也没法用；真正要给人看的是它导出的结论——{@code status=STALE}。
 *
 * <p>不是 record：本仓库实际生效的 jackson-databind 是 2.11.1，record 序列化要 2.12+
 * （见 {@code ConnectorSchemaService.ObjectDiff} 的注释）。
 */
@Slf4j
@Schema(description = "连接器语义层中的一条断言")
@Data
@Builder
public class ConnectorSemanticView {

    private String id;

    @Schema(description = "OBJECT=表用途 / FIELD=字段含义 / JOIN=表关系 / METRIC=口径 / CAVEAT=告诫")
    private String scope;

    @Schema(description = "对象名（表名）。METRIC 行为空串——口径挂在整条连接上，不挂某张表")
    private String objectName;

    @Schema(description = "字段名。仅 FIELD/JOIN 用；JOIN 存左侧列名")
    private String fieldName;

    @Schema(description = "业务词条，如「销售额」。仅 METRIC 用")
    private String term;

    @Schema(description = "给模型看的那一句话")
    private String gloss;

    @Schema(description = "按 scope 定形的结构化细节，已解析。解析不了时为空")
    private Map<String, Object> detail;

    @Schema(description = "INFERRED=机器推的 / HUMAN=人在对话里答的 / IMPORTED=客户库注释等一手事实")
    private String source;

    @Schema(description = "依据来源：COMMENT / DATA / NAME / GUESS。"
            + "★ 界面上必须显示它——「有没有外部依据」是这条断言能不能被当真的唯一判据")
    private String evidence;

    @Schema(description = "0-100，仅 INFERRED 有意义")
    private Integer confidence;

    @Schema(description = "采样验证结论：CONFIRMED / WEAK / REJECTED / UNDECIDABLE / NONE")
    private String verified;

    @Schema(description = "DRAFT / CONFIRMED / STALE。STALE = 它挂的结构已经变了，这句话可能已经不成立")
    private String status;

    @Schema(description = "锚的是什么：NONE / FIELD / JOIN / COLUMN_SET")
    private String anchorKind;

    @Schema(description = "在对话里回答这条口径的人（sys_user.id）")
    private String answeredBy;

    @Schema(description = "回答人显示名（快照，不实时 join）")
    private String answeredName;

    private Date answeredAt;

    @Schema(description = "沉淀这条口径的那次对话，可据此回到现场")
    private String traceId;

    @Schema(description = "口径被覆盖的留痕（只存旧值），最近 20 条。"
            + "任何能对话的人都能覆盖口径且不做权限区分，这是那个已知代价的唯一取证材料")
    private List<Object> history;

    private Date updateTime;

    @SuppressWarnings("unchecked")
    public static ConnectorSemanticView of(ConnectorSemantic r) {
        return ConnectorSemanticView.builder()
                // id 转字符串：雪花 id 超过 JS 的安全整数范围，按数字出网会在前端被悄悄改掉末几位。
                .id(r.getId() == null ? null : String.valueOf(r.getId()))
                .scope(r.getScope())
                .objectName(r.getObjectName())
                .fieldName(r.getFieldName())
                .term(r.getTerm())
                .gloss(r.getGloss())
                .detail(parse(r.getDetailJson(), Map.class, r.getId()))
                .source(r.getSource())
                .evidence(r.getEvidence())
                .confidence(r.getConfidence())
                .verified(r.getVerified())
                .status(r.getStatus())
                .anchorKind(r.getAnchorKind())
                .answeredBy(r.getAnsweredBy())
                .answeredName(r.getAnsweredName())
                .answeredAt(r.getAnsweredAt())
                .traceId(r.getTraceId())
                .history(parse(r.getHistoryJson(), List.class, r.getId()))
                .updateTime(r.getUpdateTime())
                .build();
    }

    /**
     * JSON 坏了只丢这一个字段，不让整张列表打不开。
     *
     * <p>和 {@code ConnectorSchemaService.toView} 同一条取舍：存量行的 JSON 损坏是既成事实，
     * 为它抛异常等于让人连「哪一行坏了」都看不到。
     */
    private static <T> T parse(String json, Class<T> type, Long id) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return CommonUtil.getObjectMapper().readValue(json, type);
        } catch (Exception e) {
            log.warn("解析语义行 JSON 失败 id={} type={}", id, type.getSimpleName(), e);
            return null;
        }
    }
}

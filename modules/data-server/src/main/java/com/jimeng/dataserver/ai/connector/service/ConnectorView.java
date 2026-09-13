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
}

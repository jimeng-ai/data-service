package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 一条连接器使用记录的对外视图。
 *
 * <p>与实体的三处差别都是刻意的：
 * <ul>
 *   <li><b>没有 tenantId</b>——它不该随响应出网（{@code Connection} 实体上那个没加
 *       {@code @JsonIgnore} 的 tenantId 是个既有的洞，新接口不继承）。</li>
 *   <li><b>补了 agentName</b>——审计要给人看，一串雪花 id 没法回答「谁在用」。</li>
 *   <li><b>errorDetail 直接展示</b>——它存的就是 {@code ConnectorException.getSafeDetail()}，
 *       已在抛出点脱敏；原始异常从来只进日志。</li>
 * </ul>
 */
@Schema(description = "连接器使用记录")
@Data
@Builder
public class ConnectorAuditView {

    private String id;

    @Schema(description = "发生时间")
    private Date time;

    private String connectorId;
    private String connectorName;

    private String agentId;

    @Schema(description = "Agent 名称。Agent 已删除时为空——审计记录不随 Agent 消失")
    private String agentName;

    @Schema(description = "用到的能力：QUERY / DESCRIBE / INVOKE …")
    private String capability;

    @Schema(description = "具体操作，即工具名：conn_query / conn_catalog / conn_describe / conn_invoke")
    private String operation;

    @Schema(description = "本次调用所属的 trace，可据此回到完整调用链路")
    private String traceId;

    @Schema(description = "命中行数。非查询类操作为空")
    private Integer rowCount;

    private Integer elapsedMs;

    private Boolean success;

    @Schema(description = "失败时的统一错误码（九类之一）")
    private String errorCode;

    @Schema(description = "失败原因，已脱敏，可直接展示")
    private String errorDetail;

    @Schema(description = "平台【实际执行】的查询语句（可能被护栏改写过，也可能超长被截断并标注）。非查询类为空")
    private String statementText;
}

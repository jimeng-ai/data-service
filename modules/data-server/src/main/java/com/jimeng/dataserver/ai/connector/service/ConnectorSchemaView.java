package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 一个已快照的对象（表 / 视图 / 接口…）。
 *
 * <p><b>不直接回传 {@code ConnectorSchema} 实体的两个理由：</b>
 * 一是实体上有 {@code tenantId}，没有 {@code @JsonIgnore} 就会随响应出网
 * （{@code Connection} 实体上那个洞不该在新接口上再开一次）；
 * 二是 {@code detailJson} 是一段字符串，直接丢给前端等于让它再解析一遍——
 * 解析逻辑分散到两端，将来改了结构 JSON 的形状就要同时改两处。
 *
 * <p>不是 record：本仓库实际生效的 jackson-databind 是 2.11.1，
 * record 序列化要 2.12+（见 {@code ConnectorSchemaService.ObjectDiff} 的注释）。
 */
@Schema(description = "连接器结构快照中的一个对象")
@Data
@Builder
public class ConnectorSchemaView {

    @Schema(description = "对象类型：TABLE / VIEW / ENDPOINT …")
    private String objectType;

    private String objectName;

    @Schema(description = "对象注释，来自客户库本身")
    private String objectComment;

    @Schema(description = "字段列表。取不到结构时为空，此时 error 有值")
    private List<Map<String, Object>> fields;

    @Schema(description = "这个对象的结构没取到时的原因；正常为空")
    private String error;

    @Schema(description = "本行的同步时间")
    private Date syncedAt;
}

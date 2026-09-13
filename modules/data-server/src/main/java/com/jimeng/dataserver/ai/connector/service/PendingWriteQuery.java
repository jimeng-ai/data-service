package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待审批写操作的查询条件。全部可空，都不给就是「本租户的全部写请求，按提交时间倒序」。
 *
 * <p>不是 record：它要从 HTTP 请求体反序列化进来，而 jackson 2.11.1 同样不认 record 的构造器。
 */
@Schema(description = "待审批写操作查询")
@Data
public class PendingWriteQuery {

    private Integer page;

    @Schema(description = "每页条数。服务端会夹取（1 ~ 200）——这张表只增不减，没有上限的列表迟早把页面拖垮")
    private Integer size;

    @Schema(description = "只看某条连接。它是 (tenant_id, status, create_time) 之外的第二条索引")
    private Long connectorId;

    @Schema(description = "只看某个 Agent 提的写请求。排查「这个 Agent 是不是被注入了」时的第一入口")
    private Long agentId;

    @Schema(description = "状态：PENDING | APPROVED | REJECTED | EXPIRED | FAILED。界面默认应当只看 PENDING")
    private String status;
}

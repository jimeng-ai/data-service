package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/** 使用记录的查询条件。全部可空，都不给就是「本租户最近的全部记录」。 */
@Schema(description = "连接器使用记录查询")
@Data
public class ConnectorAuditQuery {

    private Integer page;
    private Integer size;

    @Schema(description = "只看某条连接。它是 (tenant_id, connector_id, create_time) 这条索引的第二列，带上它查询最快")
    private Long connectorId;

    @Schema(description = "只看某个 Agent")
    private Long agentId;

    @Schema(description = "只看成功 / 只看失败。不传则都要——排查时通常先看失败，但「谁查了什么」要看全部")
    private Boolean success;

    @Schema(description = "能力：QUERY / DESCRIBE / INVOKE")
    private String capability;

    @Schema(description = "起始时间（含）")
    private Date start;

    @Schema(description = "截止时间（含）")
    private Date end;
}

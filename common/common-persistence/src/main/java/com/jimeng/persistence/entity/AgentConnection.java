package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 与外部连接的授权。skill 可以在手册里声明需要哪条连接，但只有这张表能授予。
 *
 * @TableName agent_connection
 */
@Schema(description = "Agent 与外部连接的授权")
@EqualsAndHashCode(callSuper = true)
@TableName("agent_connection")
@Data
public class AgentConnection extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("connection_id")
    private Long connectionId;
}

package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型调用日志内容表
 */
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model_call_content")
@Data
public class AiModelCallContent extends BaseEntity {

    @TableField("log_id")
    private Long logId;

    @TableField("req_headers")
    private String reqHeaders;

    @TableField("req_body")
    private String reqBody;

    @TableField("resp_body")
    private String respBody;

    @TableField("stream_events")
    private String streamEvents;
}

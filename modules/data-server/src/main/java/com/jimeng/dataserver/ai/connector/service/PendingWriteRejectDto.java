package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 拒绝一条写请求时填的理由。单字段也走请求体：理由可能很长，放 query string 里会被日志和网关截断。 */
@Schema(description = "拒绝写请求")
@Data
public class PendingWriteRejectDto {

    @Schema(description = "拒绝理由。会原样回给提交这条请求的人，写清楚能省掉一轮来回")
    private String reason;
}

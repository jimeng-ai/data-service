package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IngestionMessage implements Serializable {
    private Long docId;
    private Long kbId;
    private String traceId;
    /**
     * 入队时的租户 ID。入库在 RabbitMQ 异步线程执行，已脱离 HTTP 请求上下文，
     * 靠这个字段把租户透传过去，让 embedding / contextualization 的计费能归到对应租户。
     * 旧消息（JSON 无此字段）反序列化为 null，Consumer 侧按 null 跳过 set。
     */
    private String tenantId;
}

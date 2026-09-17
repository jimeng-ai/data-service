package com.jimeng.dataserver.ai.connector.generation;

/**
 * 语义层生成请求，只在 data-service 进程内流转。
 *
 * @param triggeredBy  发起请求的企业超管 id
 * @param degradeReason 非空表示本次从 agent 路径降级，原因要写进单次推导说明开头
 */
public record GenerationRequest(Long connectorId,
                                String tenantId,
                                Long triggeredBy,
                                GenerationTrigger trigger,
                                String degradeReason) {
}

package com.jimeng.dataserver.ai.connector.generation;

/** 生成请求的立即受理结果，不代表后台生成已完成。 */
public record GenerationAck(GeneratorKind kind,
                            boolean accepted,
                            Long generationId,
                            String note) {
}

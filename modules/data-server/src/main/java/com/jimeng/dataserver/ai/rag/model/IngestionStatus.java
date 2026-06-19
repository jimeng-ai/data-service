package com.jimeng.dataserver.ai.rag.model;

/**
 * 文档入库管道状态机：
 * STAGED → UPLOADED → PARSING → CHUNKING → CONTEXTUALIZING → EMBEDDING → DONE
 *               ↓        ↓             ↓               ↓
 *             FAILED   FAILED       FAILED          FAILED
 *
 * STAGED（待确认）：文件已上传存储，但用户尚未点「确认入库」，不进入流水线、不发 RabbitMQ 消息。
 * 确认后置为 UPLOADED 并入队，后续状态由消费者推进。
 */
public enum IngestionStatus {
    STAGED,
    UPLOADED,
    PARSING,
    CHUNKING,
    CONTEXTUALIZING,
    EMBEDDING,
    DONE,
    FAILED;

    public String code() {
        return name();
    }
}

package com.jimeng.dataserver.ai.rag.model;

/**
 * 文档入库管道状态机：
 * UPLOADED → PARSING → CHUNKING → CONTEXTUALIZING → EMBEDDING → DONE
 *                ↓        ↓             ↓               ↓
 *              FAILED   FAILED       FAILED          FAILED
 */
public enum IngestionStatus {
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

package com.jimeng.dataserver.ai.agent.builder.dto;

import lombok.Data;

/** 构建器会话接口出入参（@Data class，禁用 record）。 */
public final class BuilderSessionDtos {

    private BuilderSessionDtos() {}

    @Data
    public static class StartSessionResponse {
        private Long conversationId;
        private BuilderDraft draft;
    }

    @Data
    public static class TurnRequest {
        private String query;
        /** 本轮上传的输入文件 id（来自 POST /data/agent/files），可空。 */
        private java.util.List<Long> fileIds;
        /** 附件元信息（fileId/filename/contentType，任意 JSON，可空）——落到 user 消息，供前端回显气泡。 */
        private Object attachments;
    }
}

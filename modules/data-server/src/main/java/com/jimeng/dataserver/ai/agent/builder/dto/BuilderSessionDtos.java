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
    }
}

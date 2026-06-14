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

    @Data
    public static class FinalizeRequest {
        /** 最终草稿（含用户在预览里的手改）；为空时后端回退读会话快照。 */
        private BuilderDraft draft;
        /** 用户确认要绑定的插件 id。 */
        private java.util.List<Long> pluginIds;
        /** 用户确认要绑定的知识库 id。 */
        private java.util.List<Long> kbIds;
        /** 知识库检索参数（写入 kb_config）。 */
        private Integer topK;
        private Double scoreThreshold;
        private Boolean rerank;
    }

    @Data
    public static class FinalizeResponse {
        private Long agentId;
    }
}

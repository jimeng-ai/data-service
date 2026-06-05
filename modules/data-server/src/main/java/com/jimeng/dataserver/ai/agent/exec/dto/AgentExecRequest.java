package com.jimeng.dataserver.ai.agent.exec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 前端 -> /data/agent/exec 的请求体。 */
@Data
public class AgentExecRequest {

    @Schema(description = "Agent ID")
    private String agentId;

    @Schema(description = "所属会话 ID（可空）")
    private Long conversationId;

    @Schema(description = "用户输入")
    private String query;

    @Schema(description = "本轮附带的输入文件 ID 列表（来自 /data/agent/files）")
    private List<Long> fileIds;

    @Schema(description = "多轮历史")
    private List<History> history;

    @Schema(description = "是否为调试台预览：true=读 Agent 实时草稿配置；false/缺省=对话端，只读已发布快照（未发布则拒绝）")
    private boolean preview;

    @Data
    public static class History {
        private String role;
        private String content;
    }
}

package com.jimeng.dataserver.ai.plugingen.dto;

import lombok.Data;

import java.util.List;

/** 对话式微调入参：当前草稿 + 一条指令 + 可选历史。 */
@Data
public class RefineRequest {
    private PluginDraft draft;
    private String instruction;
    private List<ChatTurn> history;

    @Data
    public static class ChatTurn {
        /** user | assistant */
        private String role;
        private String text;
    }
}

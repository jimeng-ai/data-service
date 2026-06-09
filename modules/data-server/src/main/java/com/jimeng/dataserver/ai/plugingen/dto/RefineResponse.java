package com.jimeng.dataserver.ai.plugingen.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对话式微调出参：更新后的完整草稿 + 一句面向用户的回复。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefineResponse {
    private PluginDraft draft;
    private String reply;
}

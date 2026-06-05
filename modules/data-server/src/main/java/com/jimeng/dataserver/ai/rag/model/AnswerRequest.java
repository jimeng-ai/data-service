package com.jimeng.dataserver.ai.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "RAG 流式问答请求体")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnswerRequest {
    @Schema(description = "可选：Agent ID。带上后会加载该 Agent 的人设/模型/插件，并注入到本次对话。", example = "1")
    private String agentId;

    @Schema(description = "知识库 ID（可选）。仅当提供时才执行知识检索（RAG）；为空则纯对话，不强制走 RAG。", example = "1")
    private Long kbId;

    @Schema(description = "用户当前轮提问（必填）", example = "高德 POI 一级分类有哪些？")
    private String query;

    @Schema(description = "检索 topK，默认取 rag.retrieval.rerank-top-k", example = "10")
    private Integer topK;

    @Schema(description = "可选：限定只在这些 docId 内检索")
    private List<Long> docIds;

    @Schema(description = "是否启用 reranker 精排，默认 true", example = "true")
    private Boolean rerank;

    @Schema(description = "可选：多轮会话历史（OpenAI/Claude messages 数组）。当前轮的 user message 由本服务自动拼装，无需在 history 中重复传入。")
    private List<java.util.Map<String, Object>> history;

    @Schema(description = "是否为调试台预览：true=读 Agent 实时草稿配置；false/缺省=对话端，只读已发布快照（未发布则拒绝）", example = "false")
    private boolean preview;
}

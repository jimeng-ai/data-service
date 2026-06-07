package com.jimeng.dataserver.ai.trace.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 一条 trace 的「可视化回放」载荷：头部信息 + 有序步骤（含每步当时真实的输入/输出）。
 * 只读，全部来自已落库数据。
 */
@Data
public class TraceReplay {

    private String traceId;
    private String agentName;
    /** 企业名称（运营侧跨租户回填，租户侧为空）。 */
    private String enterpriseName;
    private String userMessage;
    private String status;
    private Date startTime;
    private Date endTime;
    private Integer stepCount;
    private Long totalLatencyMs;
    private Long totalTokens;

    /** 整段对话的 system 提示（取自最后一次 LLM 调用，折叠展示一次）。 */
    private String system;

    /** 模型调用参数（取自最后一次 LLM 调用 req_body：model / temperature / top_p / max_tokens，存在才有）。 */
    private java.util.Map<String, Object> params;

    /** 顶部步骤耗时条用的轻量步骤列表。 */
    private List<TraceReplayStep> steps;

    /** 当前问题之前的对话历史（仅文本气泡，供「查看历史」弹框）。 */
    private List<TraceReplayTurn> history;

    /** 本轮执行叙事：当前用户提问 + 之后模型的一系列动作（输出 / 工具调用 / 最终回答）。 */
    private List<TraceReplayTurn> conversation;
}

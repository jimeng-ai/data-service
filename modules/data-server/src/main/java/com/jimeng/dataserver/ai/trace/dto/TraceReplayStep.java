package com.jimeng.dataserver.ai.trace.dto;

import lombok.Data;

import java.util.Date;

/**
 * 回放顶部「步骤耗时条」用的轻量步骤项（来自 {@code ai_trace_step}）。
 * 真正的输入/输出内容在 {@link TraceReplay#getConversation()} 的执行叙事里。
 */
@Data
public class TraceReplayStep {

    private Integer stepIndex;
    private String stepType;
    private String title;
    private String subTitle;
    private String model;
    private Integer durationMs;
    private Integer totalTokens;
    private String status;
    private String errorMsg;
    private Date stepTime;
    /** 非 LLM 步骤的扩展信息（已解析的 metadata：topK/hits/toolName 等）。 */
    private Object metadata;
}

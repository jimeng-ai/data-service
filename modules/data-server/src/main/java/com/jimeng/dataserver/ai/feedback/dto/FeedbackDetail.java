package com.jimeng.dataserver.ai.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/** 反馈详情。用 @Data class（本项目 Jackson 不序列化 record）。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackDetail {
    private Long id;
    private String tenantId;
    private String tenantName;
    private Integer feedbackType;
    private String content;
    private Date createTime;
    private List<FeedbackImageView> images;
}

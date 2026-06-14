package com.jimeng.dataserver.ai.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 列表项。tenantName 仅运营端回填，租户端为 null。用 @Data class（本项目 Jackson 不序列化 record）。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackListItem {
    private Long id;
    private String tenantId;
    private String tenantName;
    private Integer feedbackType;
    private String content;
    private int imageCount;
    private Date createTime;
}

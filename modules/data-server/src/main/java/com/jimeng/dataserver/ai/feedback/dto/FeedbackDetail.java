package com.jimeng.dataserver.ai.feedback.dto;

import java.util.Date;
import java.util.List;

public record FeedbackDetail(
        Long id,
        String tenantId,
        String tenantName,
        Integer feedbackType,
        String content,
        Date createTime,
        List<FeedbackImageView> images) {
}

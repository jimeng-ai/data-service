package com.jimeng.dataserver.ai.feedback.dto;

import java.util.Date;

/** 列表项。tenantName 仅运营端回填，租户端为 null。 */
public record FeedbackListItem(
        Long id,
        String tenantId,
        String tenantName,
        Integer feedbackType,
        String content,
        int imageCount,
        Date createTime) {
}

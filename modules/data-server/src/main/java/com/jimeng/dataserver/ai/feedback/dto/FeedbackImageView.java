package com.jimeng.dataserver.ai.feedback.dto;

/** 图片元信息（不含字节，字节走 /images/{id} 流式端点）。 */
public record FeedbackImageView(Long imageId, String contentType, Integer sortOrder) {
}

package com.jimeng.dataserver.ai.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图片元信息（不含字节，字节走 /images/{id} 流式端点）。用 @Data class（本项目 Jackson 不序列化 record）。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackImageView {
    private Long imageId;
    private String contentType;
    private Integer sortOrder;
}

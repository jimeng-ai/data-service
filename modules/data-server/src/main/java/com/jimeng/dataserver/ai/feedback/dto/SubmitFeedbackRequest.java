package com.jimeng.dataserver.ai.feedback.dto;

import java.util.List;

/** 提交反馈请求体（application/json）。 */
public class SubmitFeedbackRequest {
    private Integer feedbackType;
    private String content;
    private List<Long> imageIds;

    public Integer getFeedbackType() { return feedbackType; }
    public void setFeedbackType(Integer feedbackType) { this.feedbackType = feedbackType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<Long> getImageIds() { return imageIds; }
    public void setImageIds(List<Long> imageIds) { this.imageIds = imageIds; }
}

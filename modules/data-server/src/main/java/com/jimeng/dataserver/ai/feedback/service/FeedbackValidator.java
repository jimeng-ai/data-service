package com.jimeng.dataserver.ai.feedback.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;

/** 反馈相关纯校验逻辑（无 Spring 依赖，便于单测）。 */
public final class FeedbackValidator {

    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10MB
    public static final int MAX_IMAGE_COUNT = 9;

    private FeedbackValidator() {
    }

    /** 单图校验：非空、image/* 类型、大小上限。违规抛带原因的业务异常。 */
    public static void validateImage(String contentType, long size) {
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "仅支持上传图片文件");
        }
        if (size <= 0) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "图片内容为空");
        }
        if (size > MAX_IMAGE_BYTES) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "单张图片不能超过 10MB");
        }
    }

    /** 提交校验：类型必填合法、描述非空、图片数上限。 */
    public static void validateSubmit(Integer feedbackType, String content, int imageCount) {
        if (feedbackType == null || (feedbackType != 1 && feedbackType != 2)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请选择反馈类型");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请填写反馈内容");
        }
        if (imageCount > MAX_IMAGE_COUNT) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "最多上传 " + MAX_IMAGE_COUNT + " 张图片");
        }
    }
}

package com.jimeng.common.core.enums;

/**
 * @Author Moonlight
 * @Description 文件类型枚举
 * @Date 2024/8/4 11:09
 */

public enum FileTypeEnum {

    TEXT_HTML("text/html"),
    APPLICATION_PDF("application/pdf"),
    IMAGE_JPEG("image/jpeg"),
    IMAGE_PNG("image/png");

    private final String code;

    FileTypeEnum(String contentType) {
        this.code = contentType;
    }

    public String getCode() {
        return code;
    }
}

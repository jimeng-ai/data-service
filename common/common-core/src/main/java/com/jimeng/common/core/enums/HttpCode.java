package com.jimeng.common.core.enums;

/**
 * @Author Moonlight
 * @Description http 状态码枚举
 * @Date 2024/7/2 22:58
 */

public enum HttpCode {
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "拒绝访问"),
    SUCCESS(200, "成功"),
    ERROR(500, "服务异常"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),
    ;

    private final Integer code;

    private final String message;

    HttpCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}

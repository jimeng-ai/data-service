package com.jimeng.common.core.enums;

/**
 * @Author Moonlight
 * @Description 报错码枚举
 * @Date 2024/7/13 20:48
 */

public enum ExceptionCode {


    SUCCESS("2000", "成功"),
    AUTHENTICATION_FAIL("4001", "用户认证失败"),
    BODY_NOT_MATCH("4000", "请求的数据格式不符"),
    SIGNATURE_NOT_MATCH("4002", "请求的数字签名不匹配"),
    NOT_FOUND("4004", "未找到该资源"),
    INTERNAL_SERVER_ERROR("5000", "服务器内部错误"),
    SERVER_BUSY("5003", "服务器正忙，请稍后再试"),
    OPERATION_UNSUPPORTED("5004", "不支持此操作"),
    SERVICE_UNAVAILABLE("5005","服务不可用"),
    JSON_PARSE_ERROR("5005","JSON读取错误"),
    REQUEST_ERROR("5006","请求异常"),
    SSE_NOT_FOUND("5008","SSE连接不存在"),
    SSE_SEND_ERROR("5009","SSE消息发送异常"),
    INVALID_REQUEST("5007","无效请求");

    private final String resultCode;

    private final String resultMsg;

    ExceptionCode(String resultCode, String resultMsg) {
        this.resultCode = resultCode;
        this.resultMsg = resultMsg;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResultMsg() {
        return resultMsg;
    }
}

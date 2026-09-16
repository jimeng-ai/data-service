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
    CONVERSATION_GENERATING("5010","该会话正在生成回复，请稍候"),
    /**
     * 语义层 agent 回调（{@code /data/internal/semantic-agent/**}）发现批次已不是 RUNNING，或 current_run_id 已换成别的尝试。
     * 业务异常照常是 HTTP 200 + success=false；边车的 semantic 工具按这个 respCode 判为 fatal，让模型立即停手——
     * 批次结束、换片或被主动 cancel 之后，在途回调一律失效，不需要吊销列表。
     */
    SEMANTIC_GENERATION_CLOSED("4090", "语义层生成批次已结束或已换片"),
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

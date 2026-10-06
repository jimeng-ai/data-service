package com.jimeng.dataserver.ai.conversation;

/**
 * 平台内部调用模型时，模型服务返回了非 2xx。
 *
 * <p>{@link #getMessage()} 是给人看的一句话（会原样出现在管理台，比如语义层的「推导失败：…」），
 * 只说是什么问题；上游的原话留在 {@link #getUpstreamMessage()}，调用日志里也有完整的响应体。
 *
 * <p>为什么要有它：从前内部调用遇到非 2xx 不抛、把错误体当回复交回去，调用方按回复取正文只拿到空串，
 * 于是「余额不足」在界面上变成了一句 JSON 解析错误，把排查方向整个带偏。
 */
public class ModelServiceException extends RuntimeException {

    private static final int UPSTREAM_MESSAGE_MAX = 120;

    private final int httpStatus;
    private final String upstreamMessage;

    public ModelServiceException(int httpStatus, String upstreamMessage) {
        super(summary(httpStatus, upstreamMessage));
        this.httpStatus = httpStatus;
        this.upstreamMessage = upstreamMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 上游错误体里的原话（取不到就是响应体本身），可能为 null。 */
    public String getUpstreamMessage() {
        return upstreamMessage;
    }

    /** 常见状态码直接说原因；其它的带上上游原话，否则看不出是哪里不对。 */
    static String summary(int status, String upstreamMessage) {
        String code = "（HTTP " + status + "）";
        if (status == 401 || status == 403) {
            return "模型服务的密钥无效或没有权限" + code;
        }
        if (status == 402) {
            return "模型服务余额不足" + code;
        }
        if (status == 429) {
            return "模型服务限流，请稍后重试" + code;
        }
        if (status >= 500) {
            return "模型服务暂时不可用" + code;
        }
        String raw = upstreamMessage == null ? "" : upstreamMessage.strip();
        if (raw.isEmpty()) {
            return "模型服务拒绝了请求" + code;
        }
        if (raw.length() > UPSTREAM_MESSAGE_MAX) {
            raw = raw.substring(0, UPSTREAM_MESSAGE_MAX) + "…";
        }
        return "模型服务拒绝了请求" + code + "：" + raw;
    }
}

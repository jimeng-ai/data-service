package com.jimeng.dataserver.ai.connector.error;

/**
 * 连接器运行期异常。<b>携带两套文案，泾渭分明：</b>
 *
 * <ul>
 *   <li>{@link #getSafeDetail()} —— 允许出网、允许进模型上下文、允许写进审计。已由抛出方脱敏。</li>
 *   <li>{@link #getCause()} —— 只准进日志。</li>
 * </ul>
 *
 * <p>为什么要分两套：工具执行的结果会被完整 JSON 化后回灌模型、并落进
 * {@code ai_model_call_content}。而 JDBC 的 {@code SQLException} 消息里常有完整 SQL、
 * 主机名、有时还有连接参数。把它原样回灌，就等于把客户的库结构和连接信息写进了模型上下文和库表。
 */
public class ConnectorException extends RuntimeException {

    private final ConnectorErrorCode code;
    private final String safeDetail;

    public ConnectorException(ConnectorErrorCode code, String safeDetail) {
        this(code, safeDetail, null);
    }

    public ConnectorException(ConnectorErrorCode code, String safeDetail, Throwable cause) {
        // message 只用安全文案；cause 保留给日志。
        super(code.title() + (safeDetail == null || safeDetail.isBlank() ? "" : "：" + safeDetail), cause);
        this.code = code;
        this.safeDetail = safeDetail;
    }

    public static ConnectorException of(ConnectorErrorCode code, String safeDetail) {
        return new ConnectorException(code, safeDetail);
    }

    public ConnectorErrorCode getCode() {
        return code;
    }

    /** 已脱敏、可出网的补充说明；可能为 null。 */
    public String getSafeDetail() {
        return safeDetail;
    }
}

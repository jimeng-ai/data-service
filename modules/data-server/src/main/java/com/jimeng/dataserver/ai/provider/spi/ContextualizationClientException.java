package com.jimeng.dataserver.ai.provider.spi;

/**
 * 上下文化调用返回 4xx 时抛出，让上游连续计数后 fail-fast，避免在已知会失败的请求上空转。
 */
public class ContextualizationClientException extends RuntimeException {

    private final int status;
    private final String body;

    public ContextualizationClientException(int status, String body) {
        super("contextualization client error: " + status);
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}

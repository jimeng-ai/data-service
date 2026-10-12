package com.jimeng.dataserver.ai.http;

import java.util.List;
import java.util.Map;

/** 调用方原样交来的请求。url / headers / body 里可以有 {{名字}} 占位符。toString 不带请求头和正文。 */
public record HttpGatewayRequest(String method, String url, List<Map.Entry<String, String>> headers, byte[] body) {
    public HttpGatewayRequest {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    @Override
    public String toString() {
        return "HttpGatewayRequest[" + method + " " + url + "]";
    }
}

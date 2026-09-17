package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

/** {@code POST /tables} 的请求体；缺省值由服务层统一应用。 */
@Data
public class TableListRequest {
    private String query;
    private String scope;
    private Integer offset;
    private Integer limit;
}

package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

import java.util.List;

/** {@code POST /table-metadata} 的请求体。 */
@Data
public class TableMetadataRequest {
    private List<String> tables;
}

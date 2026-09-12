package com.jimeng.dataserver.ai.connection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 连接的录入/编辑载荷。credential 只写不读——读接口永远不回传凭据。 */
@Schema(description = "外部连接录入")
@Data
public class ConnectionUpsert {

    @Schema(description = "容器可见标识，出现在 $JM_CONN_BASE/<name>/ 里；^[A-Za-z0-9_-]{1,64}$")
    private String name;

    private String displayName;

    @Schema(description = "真实上游 base URL，容器不可见")
    private String baseUrl;

    @Schema(description = "bearer | api-key，默认 bearer")
    private String authScheme;

    @Schema(description = "凭据明文。新建必填；编辑时留空表示不改动。加密后入库，永不回传")
    private String credential;

    @Schema(description = "允许的 HTTP 方法，默认 [GET]（只读）。写操作必须显式声明")
    private List<String> allowMethods;

    @Schema(description = "允许的路径 glob（`**` 跨段、`*` 段内），留空等价于 [\"/**\"]")
    private List<String> allowPaths;
}
